package com.signalbox.jellyremote;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class LibraryItem {
    final String id;
    final String name;
    final String type;
    final String seriesId;
    final String seriesName;
    final String overview;
    final String imageId;
    final String imageTag;
    final int year;
    final int season;
    final int episode;
    final long durationTicks;
    final long resumeTicks;
    final boolean playable;
    final boolean watched;

    private LibraryItem(JSONObject item) {
        id = item.optString("Id", "");
        name = item.optString("Name", "Untitled");
        type = item.optString("Type", "");
        seriesId = item.optString("SeriesId", "");
        seriesName = item.optString("SeriesName", "");
        overview = item.optString("Overview", "");
        year = item.optInt("ProductionYear", 0);
        season = item.optInt("ParentIndexNumber", -1);
        episode = item.optInt("IndexNumber", -1);
        durationTicks = Math.max(0L, item.optLong("RunTimeTicks", 0L));
        JSONObject user = item.optJSONObject("UserData");
        watched = user != null && user.optBoolean("Played", false);
        long position = user == null ? 0L : user.optLong("PlaybackPositionTicks", 0L);
        resumeTicks = position > 0 && (durationTicks == 0 || position < durationTicks) ? position : 0L;
        playable = ("Movie".equals(type) || "Episode".equals(type))
                && !item.optBoolean("IsMissing", false)
                && !item.optBoolean("IsPlaceHolder", false)
                && !"Virtual".equals(item.optString("LocationType"));
        JSONObject tags = item.optJSONObject("ImageTags");
        String primary = tags == null ? "" : tags.optString("Primary", "");
        imageId = primary.isEmpty() ? item.optString("SeriesPrimaryImageItemId", seriesId.isEmpty() ? id : seriesId) : id;
        imageTag = primary.isEmpty() ? item.optString("SeriesPrimaryImageTag", "") : primary;
    }

    static LibraryItem fromJson(JSONObject item) {
        return new LibraryItem(item);
    }

    boolean isSeries() { return "Series".equals(type); }
    boolean isSeason() { return "Season".equals(type); }
    boolean isEpisode() { return "Episode".equals(type); }

    String subtitle() {
        List<String> parts = new ArrayList<>();
        if (isEpisode()) {
            if (!seriesName.isEmpty()) parts.add(seriesName);
            if (season >= 0 && episode >= 0) parts.add(String.format(Locale.US, "S%02d · E%02d", season, episode));
        } else {
            if (year > 0) parts.add(Integer.toString(year));
            parts.add(isSeries() ? "Series" : isSeason() ? "Season" : "Movie");
        }
        if (durationTicks > 0L) parts.add((durationTicks / 600_000_000L) + " min");
        return String.join("  ·  ", parts);
    }

    String resumeLabel() {
        long seconds = resumeTicks / 10_000_000L;
        return seconds >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }
}
