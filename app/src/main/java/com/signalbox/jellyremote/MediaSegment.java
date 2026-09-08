package com.signalbox.jellyremote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

final class MediaSegment {
    private static final long TICKS_PER_SECOND = 10_000_000L;
    private static final long DEFAULT_PROMPT_TICKS = 8L * TICKS_PER_SECOND;
    private static final long MINIMUM_PROMPT_SEGMENT_TICKS = 3L * TICKS_PER_SECOND;

    enum Type {
        INTRO,
        OUTRO
    }

    final Type type;
    final long startTicks;
    final long endTicks;
    final long promptStartTicks;
    final long promptEndTicks;
    final String key;

    MediaSegment(Type type, long startTicks, long endTicks, long promptStartTicks, long promptEndTicks) {
        this.type = type;
        this.startTicks = Math.max(0L, startTicks);
        this.endTicks = Math.max(this.startTicks, endTicks);
        this.promptStartTicks = Math.max(0L, promptStartTicks);
        this.promptEndTicks = Math.min(this.endTicks, Math.max(this.promptStartTicks, promptEndTicks));
        this.key = type.name() + ":" + this.startTicks + ":" + this.endTicks;
    }

    boolean promptVisibleAt(long positionTicks) {
        return positionTicks >= promptStartTicks && positionTicks < promptEndTicks;
    }

    String buttonText() {
        return type == Type.INTRO ? "SKIP INTRO" : "SKIP OUTRO";
    }

    static MediaSegment promptAt(List<MediaSegment> segments, long positionTicks) {
        if (segments == null) return null;
        for (MediaSegment segment : segments) {
            if (segment.promptVisibleAt(positionTicks)) return segment;
        }
        return null;
    }

    static List<MediaSegment> fromJellyfin(JSONObject response) {
        JSONArray items = response == null ? null : response.optJSONArray("Items");
        if (items == null) return Collections.emptyList();
        List<MediaSegment> result = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            Type type = parseType(item.optString("Type", ""));
            if (type == null) continue;
            long start = item.optLong("StartTicks", -1L);
            long end = item.optLong("EndTicks", -1L);
            if (start < 0L || end <= start || end - start < MINIMUM_PROMPT_SEGMENT_TICKS) continue;
            result.add(new MediaSegment(type, start, end, start, Math.min(end, start + DEFAULT_PROMPT_TICKS)));
        }
        return Collections.unmodifiableList(result);
    }

    static List<MediaSegment> fromLegacy(JSONObject response) {
        if (response == null) return Collections.emptyList();
        List<MediaSegment> result = new ArrayList<>();
        Iterator<String> keys = response.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Type type = parseType(key);
            JSONObject item = response.optJSONObject(key);
            if (type == null || item == null || !item.optBoolean("Valid", true)) continue;
            long start = secondsToTicks(item.optDouble("IntroStart", -1d));
            long end = secondsToTicks(item.optDouble("IntroEnd", -1d));
            long promptStart = secondsToTicks(item.optDouble("ShowSkipPromptAt", start / (double) TICKS_PER_SECOND));
            long promptEnd = secondsToTicks(item.optDouble("HideSkipPromptAt", end / (double) TICKS_PER_SECOND));
            if (start < 0L || end <= start || promptEnd <= promptStart) continue;
            result.add(new MediaSegment(type, start, end, promptStart, promptEnd));
        }
        return Collections.unmodifiableList(result);
    }

    private static Type parseType(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("intro") || normalized.equals("introduction") || normalized.equals("0")) {
            return Type.INTRO;
        }
        if (normalized.equals("outro") || normalized.equals("credits") || normalized.equals("credit")
                || normalized.equals("endingcredits") || normalized.equals("1")) {
            return Type.OUTRO;
        }
        return null;
    }

    private static long secondsToTicks(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0d) return -1L;
        return Math.round(seconds * TICKS_PER_SECOND);
    }
}
