package com.signalbox.jellyremote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class RemoteState {
    final String sessionId;
    final String userName;
    final String client;
    final String deviceName;
    final boolean active;
    final boolean supportsControl;
    final boolean hasMedia;
    final String itemId;
    final String title;
    final String series;
    final String mediaType;
    final int season;
    final int episode;
    final long positionTicks;
    final long durationTicks;
    final boolean paused;
    final boolean muted;
    final int volume;
    final int subtitleIndex;
    final List<Integer> subtitleStreams;
    final List<Chapter> chapters;
    final String backdropItemId;
    final boolean ownBackdrop;
    final String artKey;

    private RemoteState(
            String sessionId, String userName, String client, String deviceName,
            boolean active, boolean supportsControl, boolean hasMedia, String itemId,
            String title, String series, String mediaType, int season, int episode,
            long positionTicks, long durationTicks, boolean paused, boolean muted,
            int volume, int subtitleIndex, List<Integer> subtitleStreams, List<Chapter> chapters,
            String backdropItemId, boolean ownBackdrop) {
        this.sessionId = sessionId;
        this.userName = userName;
        this.client = client;
        this.deviceName = deviceName;
        this.active = active;
        this.supportsControl = supportsControl;
        this.hasMedia = hasMedia;
        this.itemId = itemId;
        this.title = title;
        this.series = series;
        this.mediaType = mediaType;
        this.season = season;
        this.episode = episode;
        this.positionTicks = positionTicks;
        this.durationTicks = durationTicks;
        this.paused = paused;
        this.muted = muted;
        this.volume = volume;
        this.subtitleIndex = subtitleIndex;
        this.subtitleStreams = subtitleStreams;
        this.chapters = chapters;
        this.backdropItemId = backdropItemId;
        this.ownBackdrop = ownBackdrop;
        this.artKey = itemId + ":" + backdropItemId + ":" + ownBackdrop;
    }

    static RemoteState fromJson(JSONObject session) {
        JSONObject item = session.optJSONObject("NowPlayingItem");
        JSONObject play = session.optJSONObject("PlayState");
        if (play == null) play = new JSONObject();
        boolean hasMedia = item != null && !item.optString("Id").isEmpty();
        if (item == null) item = new JSONObject();

        List<Integer> subtitles = new ArrayList<>();
        JSONArray streams = item.optJSONArray("MediaStreams");
        if (streams != null) {
            for (int i = 0; i < streams.length(); i++) {
                JSONObject stream = streams.optJSONObject(i);
                if (stream != null && "Subtitle".equalsIgnoreCase(stream.optString("Type"))) {
                    subtitles.add(stream.optInt("Index", i));
                }
            }
        }

        JSONArray ownTags = item.optJSONArray("BackdropImageTags");
        boolean ownBackdrop = ownTags != null && ownTags.length() > 0;
        String itemId = item.optString("Id", "");
        String backdropId = ownBackdrop ? itemId : item.optString("ParentBackdropItemId", "");
        if (backdropId.isEmpty()) backdropId = item.optString("SeriesId", "");
        if (backdropId.isEmpty()) backdropId = itemId;

        return new RemoteState(
                session.optString("Id", ""),
                session.optString("UserName", ""),
                session.optString("Client", "Jellyfin"),
                session.optString("DeviceName", "Unknown device"),
                session.optBoolean("IsActive", false),
                session.optBoolean("SupportsMediaControl", false)
                        || session.optBoolean("SupportsRemoteControl", false),
                hasMedia,
                itemId,
                item.optString("Name", "Nothing playing"),
                item.optString("SeriesName", ""),
                item.optString("Type", ""),
                item.optInt("ParentIndexNumber", -1),
                item.optInt("IndexNumber", -1),
                play.optLong("PositionTicks", 0L),
                item.optLong("RunTimeTicks", 0L),
                play.optBoolean("IsPaused", false),
                play.optBoolean("IsMuted", false),
                clamp(play.optInt("VolumeLevel", 100), 0, 100),
                play.optInt("SubtitleStreamIndex", -1),
                Collections.unmodifiableList(subtitles),
                Chapter.fromItem(item),
                backdropId,
                ownBackdrop);
    }

    String deviceLabel() {
        if (deviceName.equalsIgnoreCase(client)) return deviceName;
        return deviceName + "  •  " + client;
    }

    String eyebrow() {
        if (!series.isEmpty() && season >= 0 && episode >= 0) {
            return series + "  •  S" + two(season) + " E" + two(episode);
        }
        if (!series.isEmpty()) return series;
        return mediaType.isEmpty() ? "NOW PLAYING" : mediaType.toUpperCase(Locale.ROOT);
    }

    int firstSubtitleIndex() {
        return subtitleStreams.isEmpty() ? -1 : subtitleStreams.get(0);
    }

    private static String two(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }
}
