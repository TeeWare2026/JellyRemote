package com.signalbox.jellyremote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class LibraryPage {
    final List<LibraryItem> items;
    final int total;
    final int offset;

    LibraryPage(JSONObject response, int offset) {
        JSONArray rows = response.optJSONArray("Items");
        List<LibraryItem> parsed = new ArrayList<>();
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row != null && !row.optString("Id", "").isEmpty()) parsed.add(LibraryItem.fromJson(row));
            }
        }
        items = Collections.unmodifiableList(parsed);
        total = response.optInt("TotalRecordCount", -1);
        this.offset = offset;
    }

    boolean hasNext() {
        return !items.isEmpty() && (total >= 0 ? offset + items.size() < total
                : items.size() == LibraryQuery.PAGE_SIZE);
    }
}
