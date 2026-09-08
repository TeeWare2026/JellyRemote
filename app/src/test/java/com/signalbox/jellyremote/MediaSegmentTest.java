package com.signalbox.jellyremote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

public final class MediaSegmentTest {
    @Test public void promptWindowUsesInclusiveStartAndExclusiveEnd() {
        MediaSegment segment = new MediaSegment(
                MediaSegment.Type.INTRO, 100L, 500L, 80L, 180L);

        assertFalse(segment.promptVisibleAt(79L));
        assertTrue(segment.promptVisibleAt(80L));
        assertTrue(segment.promptVisibleAt(179L));
        assertFalse(segment.promptVisibleAt(180L));
    }

    @Test public void findsOnlyThePromptAtTheCurrentPosition() {
        MediaSegment intro = new MediaSegment(
                MediaSegment.Type.INTRO, 100L, 500L, 100L, 180L);
        MediaSegment outro = new MediaSegment(
                MediaSegment.Type.OUTRO, 900L, 1000L, 900L, 980L);
        List<MediaSegment> segments = List.of(intro, outro);

        assertSame(intro, MediaSegment.promptAt(segments, 120L));
        assertNull(MediaSegment.promptAt(segments, 600L));
        assertSame(outro, MediaSegment.promptAt(segments, 940L));
    }

    @Test public void findsAnActiveSegmentOutsideItsShortPromptWindow() {
        MediaSegment intro = new MediaSegment(
                MediaSegment.Type.INTRO, 100L, 500L, 100L, 180L);

        assertNull(MediaSegment.promptAt(List.of(intro), 300L));
        assertSame(intro, MediaSegment.activeAt(List.of(intro), 300L));
        assertNull(MediaSegment.activeAt(List.of(intro), 500L));
    }

    @Test public void usesClearIntroAndOutroLabels() {
        MediaSegment intro = new MediaSegment(
                MediaSegment.Type.INTRO, 0L, 100L, 0L, 80L);
        MediaSegment outro = new MediaSegment(
                MediaSegment.Type.OUTRO, 100L, 200L, 100L, 180L);

        assertEquals("SKIP INTRO", intro.buttonText());
        assertEquals("SKIP OUTRO", outro.buttonText());
    }

    @Test public void parsesCurrentJellyfinMediaSegments() throws Exception {
        JSONObject response = new JSONObject("{\"Items\":["
                + "{\"Type\":\"Intro\",\"StartTicks\":100000000,\"EndTicks\":1000000000},"
                + "{\"Type\":\"Commercial\",\"StartTicks\":1200000000,\"EndTicks\":1300000000},"
                + "{\"Type\":\"Outro\",\"StartTicks\":2000000000,\"EndTicks\":2500000000}]}" );

        List<MediaSegment> segments = MediaSegment.fromJellyfin(response);

        assertEquals(2, segments.size());
        assertEquals(MediaSegment.Type.INTRO, segments.get(0).type);
        assertEquals(180000000L, segments.get(0).promptEndTicks);
        assertEquals(MediaSegment.Type.OUTRO, segments.get(1).type);
        assertEquals(2080000000L, segments.get(1).promptEndTicks);
    }

    @Test public void preservesLegacyPluginPromptTiming() throws Exception {
        JSONObject response = new JSONObject("{\"Introduction\":{"
                + "\"Valid\":true,\"IntroStart\":30.0,\"IntroEnd\":90.0,"
                + "\"ShowSkipPromptAt\":25.0,\"HideSkipPromptAt\":45.0},"
                + "\"Credits\":{\"Valid\":true,\"IntroStart\":1200.0,\"IntroEnd\":1260.0,"
                + "\"ShowSkipPromptAt\":1195.0,\"HideSkipPromptAt\":1215.0}}" );

        List<MediaSegment> segments = MediaSegment.fromLegacy(response);

        assertEquals(2, segments.size());
        MediaSegment intro = segments.stream()
                .filter(segment -> segment.type == MediaSegment.Type.INTRO)
                .findFirst().orElseThrow();
        assertEquals(250000000L, intro.promptStartTicks);
        assertEquals(450000000L, intro.promptEndTicks);
        assertEquals(900000000L, intro.endTicks);
    }
}
