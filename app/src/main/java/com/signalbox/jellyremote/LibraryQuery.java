package com.signalbox.jellyremote;

import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

/** Immutable navigation state, also used when retrying or moving between pages. */
final class LibraryQuery {
    static final int PAGE_SIZE = 48;
    enum Kind { MOVIES, SERIES, RESUME, SEARCH, SEASONS, EPISODES }

    final Kind kind;
    final String title;
    final String parentId;
    final String seriesId;
    final String term;
    final int offset;

    LibraryQuery(Kind kind, String title, String parentId, String seriesId, String term, int offset) {
        this.kind = kind;
        this.title = title;
        this.parentId = parentId;
        this.seriesId = seriesId;
        this.term = term;
        this.offset = Math.max(0, offset);
    }

    static LibraryQuery movies() { return root(Kind.MOVIES, "Movies"); }
    static LibraryQuery root(Kind kind, String title) {
        return new LibraryQuery(kind, title, "", "", "", 0);
    }

    LibraryQuery page(int offset) {
        return new LibraryQuery(kind, title, parentId, seriesId, term, offset);
    }

    String path(String userId) {
        String common = "userId=" + encode(userId)
                + "&enableUserData=true&enableImages=true&imageTypeLimit=1"
                + "&fields=Overview,PrimaryImageAspectRatio&isMissing=false"
                + "&startIndex=" + offset + "&limit=" + PAGE_SIZE;
        if (kind == Kind.SEASONS) return "/Shows/" + encode(seriesId) + "/Seasons?" + common;
        if (kind == Kind.EPISODES) return "/Shows/" + encode(seriesId) + "/Episodes?"
                + common + "&seasonId=" + encode(parentId);
        if (kind == Kind.RESUME) return "/UserItems/Resume?" + common + "&mediaTypes=Video";
        String path = "/Items?" + common + "&recursive=true&enableTotalRecordCount=true";
        if (kind == Kind.SEARCH) return path + "&includeItemTypes=Movie,Series,Episode&searchTerm=" + encode(term);
        return path + "&sortBy=SortName&sortOrder=Ascending&includeItemTypes="
                + (kind == Kind.SERIES ? "Series" : "Movie");
    }

    static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
