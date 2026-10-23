package com.signalbox.jellyremote;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Chapter metadata can arrive after playback starts or after a failed request. */
final class ChapterLoadState {
    static final long FAILURE_RETRY_MS = 5_000L;
    static final long EMPTY_RETRY_MS = 30_000L;

    private String itemId = "";
    private List<Chapter> chapters = Collections.emptyList();
    private long revision;
    private long retryAt;
    private boolean loading;
    private boolean loaded;

    void reset() {
        itemId = "";
        chapters = Collections.emptyList();
        revision++;
        retryAt = 0L;
        loading = false;
        loaded = false;
    }

    void select(String id, List<Chapter> embedded) {
        if (!itemId.equals(id)) {
            reset();
            itemId = id;
        }
        if (embedded != null && !embedded.isEmpty()) {
            chapters = Collections.unmodifiableList(new ArrayList<>(embedded));
            loaded = true;
            loading = false;
            revision++;
        }
    }

    Request requestIfDue(long now) {
        if (itemId.isEmpty() || loaded || loading || now < retryAt) return null;
        loading = true;
        return new Request(itemId, ++revision);
    }

    boolean succeed(Request request, List<Chapter> result, long now) {
        if (!matches(request)) return false;
        chapters = Collections.unmodifiableList(new ArrayList<>(result));
        loading = false;
        loaded = !chapters.isEmpty();
        retryAt = now + EMPTY_RETRY_MS;
        return true;
    }

    void fail(Request request, long now) {
        if (!matches(request)) return;
        loading = false;
        retryAt = now + FAILURE_RETRY_MS;
    }

    List<Chapter> chapters() { return chapters; }

    private boolean matches(Request request) {
        return request != null && loading && request.revision == revision && itemId.equals(request.itemId);
    }

    static final class Request {
        final String itemId;
        private final long revision;

        private Request(String itemId, long revision) {
            this.itemId = itemId;
            this.revision = revision;
        }
    }
}
