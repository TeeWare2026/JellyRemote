package com.signalbox.jellyremote;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

public final class ChapterLoadStateTest {
    private final List<Chapter> markers = List.of(new Chapter("Opening", 0L), new Chapter("Main story", 1200000000L));

    @Test public void failedRequestRetriesWithoutChangingThePlayingItem() {
        ChapterLoadState state = new ChapterLoadState();
        state.select("film", Collections.emptyList());
        ChapterLoadState.Request first = state.requestIfDue(0L);
        assertNotNull(first);
        assertNull(state.requestIfDue(100L));
        state.fail(first, 100L);
        state.select("film", Collections.emptyList());
        assertNull(state.requestIfDue(100L + ChapterLoadState.FAILURE_RETRY_MS - 1L));
        ChapterLoadState.Request retry = state.requestIfDue(100L + ChapterLoadState.FAILURE_RETRY_MS);
        assertNotNull(retry);
        assertTrue(state.succeed(retry, markers, 6000L));
        assertEquals(2, state.chapters().size());
    }

    @Test public void emptyMetadataIsRecheckedAfterAThrottledDelay() {
        ChapterLoadState state = new ChapterLoadState();
        state.select("film", Collections.emptyList());
        state.succeed(state.requestIfDue(0L), Collections.emptyList(), 10L);
        assertNull(state.requestIfDue(1000L));
        ChapterLoadState.Request retry = state.requestIfDue(10L + ChapterLoadState.EMPTY_RETRY_MS);
        assertNotNull(retry);
        state.succeed(retry, markers, 31000L);
        assertEquals(2, state.chapters().size());
    }

    @Test public void lateSessionChaptersOverrideAnOlderEmptyRequest() {
        ChapterLoadState state = new ChapterLoadState();
        state.select("film", Collections.emptyList());
        ChapterLoadState.Request older = state.requestIfDue(0L);
        state.select("film", markers);
        assertEquals(2, state.chapters().size());
        assertFalse(state.succeed(older, Collections.emptyList(), 100L));
        assertEquals(2, state.chapters().size());
        assertNull(state.requestIfDue(60000L));
    }

    @Test public void switchingAwayAndBackRejectsTheOriginalInFlightResult() {
        ChapterLoadState state = new ChapterLoadState();
        state.select("film-a", Collections.emptyList());
        ChapterLoadState.Request oldA = state.requestIfDue(0L);
        state.select("film-b", Collections.emptyList());
        ChapterLoadState.Request oldB = state.requestIfDue(1L);
        state.select("film-a", Collections.emptyList());
        ChapterLoadState.Request newA = state.requestIfDue(2L);
        assertFalse(state.succeed(oldA, markers, 3L));
        assertFalse(state.succeed(oldB, markers, 4L));
        assertTrue(state.chapters().isEmpty());
        assertTrue(state.succeed(newA, markers, 5L));
    }

    @Test public void sessionPayloadsWithoutChaptersDoNotEraseLoadedMetadata() {
        ChapterLoadState state = new ChapterLoadState();
        state.select("film", markers);
        state.select("film", Collections.emptyList());
        assertEquals(2, state.chapters().size());
        assertNull(state.requestIfDue(100000L));
        state.reset();
        assertTrue(state.chapters().isEmpty());
        assertNull(state.requestIfDue(100001L));
    }
}
