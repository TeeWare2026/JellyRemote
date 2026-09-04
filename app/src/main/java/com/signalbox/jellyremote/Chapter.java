package com.signalbox.jellyremote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class Chapter {
    final String name;
    final long startTicks;

    Chapter(String name, long startTicks) {
        this.name = name;
        this.startTicks = Math.max(0L, startTicks);
    }

    String displayName(int position) {
        String clean = name == null ? "" : name.trim();
        return clean.isEmpty() ? "Chapter " + (position + 1) : clean;
    }

    String timeLabel() {
        long seconds = startTicks / 10_000_000L;
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long remainder = seconds % 60L;
        return hours > 0
                ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, remainder)
                : String.format(Locale.US, "%d:%02d", minutes, remainder);
    }

    static List<Chapter> fromItem(JSONObject item) {
        JSONArray values = item == null ? null : item.optJSONArray("Chapters");
        if (values == null || values.length() == 0) return Collections.emptyList();
        List<Chapter> chapters = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.optJSONObject(i);
            if (value == null) continue;
            long start = value.optLong("StartPositionTicks", -1L);
            if (start >= 0L) chapters.add(new Chapter(value.optString("Name", ""), start));
        }
        return Collections.unmodifiableList(chapters);
    }
}
