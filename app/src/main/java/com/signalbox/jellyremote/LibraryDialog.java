package com.signalbox.jellyremote;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Browsing only reads the server; playback is sent only from an explicit Play/Resume action. */
@SuppressLint("SetTextI18n")
final class LibraryDialog extends Dialog {
    interface Listener {
        void onPlay(LibraryItem item, boolean resume, Completion completion);
    }

    interface Completion { void done(String error); }

    private final Activity activity;
    private final JellyfinClient client;
    private final String deviceLabel;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService requests = Executors.newSingleThreadExecutor();
    private final ExecutorService images = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> imageCache = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private final Deque<LibraryQuery> history = new ArrayDeque<>();
    private final List<TextView> tabs = new ArrayList<>();
    private ThemeColors theme;
    private LibraryQuery query = LibraryQuery.movies();
    private LibraryPage page;
    private LinearLayout root;
    private FrameLayout body;
    private EditText search;
    private TextView heading;
    private TextView back;
    private TextView count;
    private TextView previous;
    private TextView next;
    private TextView pageLabel;
    private LinearLayout paging;
    private boolean ignoreSearch;
    private boolean detail;
    private boolean playing;
    private volatile boolean closed;
    private volatile int revision;
    private final Runnable searchTask = this::applySearch;

    LibraryDialog(Activity activity, JellyfinClient client, String deviceLabel,
                  ThemeColors theme, Listener listener) {
        super(activity);
        this.activity = activity;
        this.client = client;
        this.deviceLabel = deviceLabel;
        this.theme = theme;
        this.listener = listener;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        root = column();
        root.setPadding(dp(20), dp(12), dp(20), dp(12));
        root.setBackground(background());

        LinearLayout masthead = row();
        LinearLayout brand = column();
        TextView kicker = text("YOUR COLLECTION", 10, theme.primary, true);
        kicker.setLetterSpacing(.16f);
        brand.addView(kicker);
        TextView title = text("Find your next watch.", 26, Color.WHITE, true);
        title.setPadding(0, dp(5), 0, 0);
        brand.addView(title);
        masthead.addView(brand, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView close = button("Remote  ×", false, this::dismiss);
        masthead.addView(close, new LinearLayout.LayoutParams(dp(96), dp(48)));
        root.addView(masthead, new LinearLayout.LayoutParams(-1, dp(76)));

        TextView target = text("PLAY ON   ·   " + deviceLabel, 11, 0xE0FFFFFF, false);
        target.setSingleLine(true);
        target.setEllipsize(TextUtils.TruncateAt.END);
        target.setGravity(Gravity.CENTER_VERTICAL);
        target.setPadding(dp(16), 0, dp(16), 0);
        target.setBackground(surface(theme.secondary, .14f, 16));
        root.addView(target, new LinearLayout.LayoutParams(-1, dp(44)));

        search = new EditText(activity);
        search.setTextSize(15);
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(0xA8FFFFFF);
        search.setHint("Search movies, series & episodes");
        search.setContentDescription("Search library");
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setPadding(dp(18), 0, dp(18), 0);
        search.setBackground(surface(theme.primary, .12f, 18));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-1, dp(52));
        searchLp.topMargin = dp(14);
        root.addView(search, searchLp);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (ignoreSearch) return;
                main.removeCallbacks(searchTask);
                // Invalidate any in-flight result as soon as the query changes.
                revision++;
                main.postDelayed(searchTask, 350);
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        search.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
            main.removeCallbacks(searchTask);
            applySearch();
            hideKeyboard();
            return true;
        });

        HorizontalScrollView tabScroll = new HorizontalScrollView(activity);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabRow = row();
        LibraryQuery.Kind[] kinds = {LibraryQuery.Kind.MOVIES, LibraryQuery.Kind.SERIES, LibraryQuery.Kind.RESUME};
        String[] names = {"Movies", "Series", "Continue watching"};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            TextView tab = button(names[i], false, () -> {
                hideKeyboard();
                clearSearch();
                history.clear();
                load(LibraryQuery.root(kinds[index], names[index]));
            });
            tabs.add(tab);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(44));
            lp.rightMargin = dp(8);
            tabRow.addView(tab, lp);
        }
        tabScroll.addView(tabRow);
        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(-1, dp(44));
        tabLp.topMargin = dp(14);
        root.addView(tabScroll, tabLp);

        LinearLayout section = row();
        back = button("‹", false, this::goBack);
        back.setContentDescription("Back in library");
        back.setTextSize(24);
        section.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        heading = text("Movies", 18, Color.WHITE, true);
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        heading.setPadding(dp(12), 0, dp(8), 0);
        section.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        count = text("", 11, 0xBFFFFFFF, false);
        section.addView(count);
        root.addView(section, new LinearLayout.LayoutParams(-1, dp(62)));

        body = new FrameLayout(activity);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        paging = row();
        previous = button("‹  Previous", false, () -> load(query.page(Math.max(0, query.offset - LibraryQuery.PAGE_SIZE))));
        next = button("Next  ›", false, () -> load(query.page(query.offset + LibraryQuery.PAGE_SIZE)));
        pageLabel = text("", 11, 0xBFFFFFFF, false);
        pageLabel.setGravity(Gravity.CENTER);
        paging.addView(previous, new LinearLayout.LayoutParams(dp(104), dp(48)));
        paging.addView(pageLabel, new LinearLayout.LayoutParams(0, -2, 1));
        paging.addView(next, new LinearLayout.LayoutParams(dp(104), dp(48)));
        root.addView(paging, new LinearLayout.LayoutParams(-1, dp(58)));
        root.setFocusableInTouchMode(true);
        setContentView(root);
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setStatusBarColor(theme.deep);
            window.setNavigationBarColor(theme.deep);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        load(query);
    }

    @Override public void show() {
        super.show();
        Window window = getWindow();
        if (window != null) window.setLayout(-1, -1);
    }

    @Override public void dismiss() {
        closed = true;
        revision++;
        main.removeCallbacksAndMessages(null);
        requests.shutdownNow();
        images.shutdownNow();
        imageCache.evictAll();
        super.dismiss();
    }

    @Override public void onBackPressed() {
        if (detail || !history.isEmpty() || query.offset > 0) goBack();
        else super.onBackPressed();
    }

    private void clearSearch() {
        main.removeCallbacks(searchTask);
        ignoreSearch = true;
        search.setText("");
        ignoreSearch = false;
    }

    private void applySearch() {
        String term = search.getText().toString().trim();
        history.clear();
        load(term.isEmpty() ? LibraryQuery.movies()
                : new LibraryQuery(LibraryQuery.Kind.SEARCH, "Search results", "", "", term, 0));
    }

    private void goBack() {
        if (playing) return;
        if (detail) { load(query); return; }
        if (!history.isEmpty()) { load(history.pop()); return; }
        if (query.offset > 0) { load(query.page(0)); return; }
        clearSearch();
        load(LibraryQuery.movies());
    }

    private void load(LibraryQuery destination) {
        if (closed || playing) return;
        main.removeCallbacks(searchTask);
        query = destination;
        detail = false;
        int requestRevision = ++revision;
        heading.setText(query.title);
        back.setVisibility(history.isEmpty() && query.offset == 0
                && query.kind != LibraryQuery.Kind.SEARCH ? View.GONE : View.VISIBLE);
        count.setText("");
        paging.setVisibility(View.GONE);
        updateTabs();
        loading();
        requests.execute(() -> {
            if (!current(requestRevision)) return;
            try {
                LibraryPage result = client.getLibraryPage(destination);
                main.post(() -> {
                    if (!current(requestRevision)) return;
                    page = result;
                    showPage(requestRevision);
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (current(requestRevision)) message(errorText(error), () -> load(destination));
                });
            }
        });
    }

    private void showPage(int requestRevision) {
        body.removeAllViews();
        if (page.items.isEmpty()) {
            String empty = query.kind == LibraryQuery.Kind.SEARCH ? "No titles match your search."
                    : query.kind == LibraryQuery.Kind.RESUME ? "You're all caught up. Start something new."
                    : "No titles available here.";
            message(empty, null);
        } else {
            GridView grid = new GridView(activity);
            grid.setNumColumns(GridView.AUTO_FIT);
            grid.setColumnWidth(dp(142));
            grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
            grid.setHorizontalSpacing(dp(12));
            grid.setVerticalSpacing(dp(16));
            grid.setClipToPadding(false);
            grid.setPadding(0, 0, 0, dp(12));
            grid.setSelector(android.R.color.transparent);
            grid.setAdapter(new PosterAdapter(page.items, requestRevision));
            grid.setOnItemClickListener((parent, view, position, id) -> open(page.items.get(position)));
            body.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        }
        if (page.total >= 0) count.setText(page.total + (page.total == 1 ? " title" : " titles"));
        previous.setEnabled(query.offset > 0);
        previous.setAlpha(query.offset > 0 ? 1f : .3f);
        next.setEnabled(page.hasNext());
        next.setAlpha(page.hasNext() ? 1f : .3f);
        pageLabel.setText("Page " + (query.offset / LibraryQuery.PAGE_SIZE + 1));
        paging.setVisibility(query.offset > 0 || page.hasNext() ? View.VISIBLE : View.GONE);
    }

    private void open(LibraryItem item) {
        hideKeyboard();
        if (item.isSeries()) {
            history.push(query);
            load(new LibraryQuery(LibraryQuery.Kind.SEASONS, item.name, item.id, item.id, "", 0));
        } else if (item.isSeason()) {
            history.push(query);
            load(new LibraryQuery(LibraryQuery.Kind.EPISODES, item.name, item.id,
                    item.seriesId.isEmpty() ? query.seriesId : item.seriesId, "", 0));
        } else showDetail(item);
    }

    private void showDetail(LibraryItem selected) {
        if (closed || playing) return;
        detail = true;
        int requestRevision = ++revision;
        heading.setText("About this title");
        count.setText("");
        back.setVisibility(View.VISIBLE);
        paging.setVisibility(View.GONE);
        loading();
        requests.execute(() -> {
            if (!current(requestRevision)) return;
            try {
                LibraryItem item = client.getLibraryItem(selected.id);
                main.post(() -> { if (current(requestRevision)) renderDetail(item, requestRevision); });
            } catch (Exception error) {
                main.post(() -> {
                    if (current(requestRevision)) message(errorText(error), () -> showDetail(selected));
                });
            }
        });
    }

    private void renderDetail(LibraryItem item, int requestRevision) {
        body.removeAllViews();
        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout panel = column();
        scroll.addView(panel);
        FrameLayout hero = new FrameLayout(activity);
        hero.setBackground(surface(theme.primary, .16f, 24));
        hero.setClipToOutline(true);
        hero.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        hero.addView(image, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(activity);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0xDD070E16}));
        hero.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        TextView heroLabel = text(item.isEpisode() ? item.seriesName : "MOVIE NIGHT", 11, Color.WHITE, true);
        heroLabel.setLetterSpacing(.1f);
        heroLabel.setPadding(dp(20), dp(16), dp(20), dp(16));
        FrameLayout.LayoutParams heroLabelLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        hero.addView(heroLabel, heroLabelLp);
        panel.addView(hero, new LinearLayout.LayoutParams(-1, dp(170)));
        loadImage(item, image, requestRevision, true);

        TextView title = text(item.name, 28, Color.WHITE, true);
        title.setPadding(0, dp(18), 0, dp(8));
        panel.addView(title);
        TextView metadata = text(item.subtitle() + (item.watched ? "  ·  Watched" : ""), 12, theme.primary, false);
        metadata.setPadding(0, 0, 0, dp(18));
        panel.addView(metadata);
        TextView destination = text("Ready to play on " + deviceLabel, 12, 0xCCFFFFFF, false);
        destination.setPadding(0, 0, 0, dp(12));
        panel.addView(destination);

        TextView feedback = text("", 12, 0xFFFFB4B0, false);
        List<TextView> actions = new ArrayList<>();
        TextView play = button(item.resumeTicks > 0 ? "▶  Resume from " + item.resumeLabel()
                : "▶  Play on " + deviceLabel, true, () -> start(item, item.resumeTicks > 0, actions, feedback));
        play.setEnabled(item.playable);
        play.setAlpha(item.playable ? 1f : .4f);
        actions.add(play);
        panel.addView(play, new LinearLayout.LayoutParams(-1, dp(54)));
        if (item.resumeTicks > 0 && item.playable) {
            TextView beginning = button("Play from beginning", false, () -> start(item, false, actions, feedback));
            actions.add(beginning);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
            lp.topMargin = dp(10);
            panel.addView(beginning, lp);
        }
        feedback.setPadding(0, dp(10), 0, dp(8));
        if (!item.playable) feedback.setText("This title is not currently available to play.");
        panel.addView(feedback);
        TextView overview = text(item.overview.isEmpty() ? "No description available." : item.overview,
                15, 0xD9FFFFFF, false);
        overview.setLineSpacing(dp(4), 1f);
        overview.setPadding(0, dp(8), 0, dp(24));
        panel.addView(overview);
        body.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    }

    private void start(LibraryItem item, boolean resume, List<TextView> actions, TextView feedback) {
        if (playing || closed || !item.playable) return;
        playing = true;
        hideKeyboard();
        for (TextView action : actions) { action.setEnabled(false); action.setAlpha(.45f); }
        search.setEnabled(false);
        for (TextView tab : tabs) tab.setEnabled(false);
        feedback.setTextColor(theme.primary);
        feedback.setText("Sending to " + deviceLabel + "…");
        listener.onPlay(item, resume, error -> {
            if (closed) return;
            playing = false;
            if (error == null) { dismiss(); return; }
            for (TextView action : actions) { action.setEnabled(true); action.setAlpha(1f); }
            search.setEnabled(true);
            for (TextView tab : tabs) tab.setEnabled(true);
            feedback.setTextColor(0xFFFFB4B0);
            feedback.setText(error);
        });
    }

    private void updateTabs() {
        LibraryQuery.Kind[] kinds = {LibraryQuery.Kind.MOVIES, LibraryQuery.Kind.SERIES, LibraryQuery.Kind.RESUME};
        for (int i = 0; i < tabs.size(); i++) {
            boolean selected = query.kind == kinds[i];
            tabs.get(i).setTextColor(selected ? theme.deep : 0xE0FFFFFF);
            tabs.get(i).setBackground(touchBackground(selected));
        }
    }

    private boolean current(int requestRevision) { return !closed && revision == requestRevision; }

    private void loading() {
        body.removeAllViews();
        ProgressBar spinner = new ProgressBar(activity);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(theme.primary));
        body.addView(spinner, new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER));
    }

    private void message(String value, Runnable retry) {
        body.removeAllViews();
        LinearLayout panel = column();
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(24), dp(24), dp(24), dp(24));
        TextView label = text(value, 16, 0xDEFFFFFF, false);
        label.setGravity(Gravity.CENTER);
        panel.addView(label);
        if (retry != null) {
            TextView action = button("Try again", false, retry);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(140), dp(48));
            lp.topMargin = dp(20);
            panel.addView(action, lp);
        }
        body.addView(panel, new FrameLayout.LayoutParams(-1, -1));
    }

    private void loadImage(LibraryItem item, ImageView view, int requestRevision, boolean backdrop) {
        String key = item.imageId + ":" + item.imageTag + ":" + (backdrop ? item.id : "poster");
        view.setTag(key);
        Bitmap cached = imageCache.get(key);
        if (cached != null) { view.setImageBitmap(cached); return; }
        if (closed) return;
        images.execute(() -> {
            if (!current(requestRevision)) return;
            try {
                Bitmap bitmap;
                try { bitmap = client.getLibraryImage(item, backdrop); }
                catch (Exception error) {
                    if (!backdrop) return;
                    bitmap = client.getLibraryImage(item, false);
                }
                imageCache.put(key, bitmap);
                Bitmap result = bitmap;
                main.post(() -> {
                    if (current(requestRevision) && key.equals(view.getTag())) view.setImageBitmap(result);
                });
            } catch (Exception ignored) { /* The text and placeholder remain usable without artwork. */ }
        });
    }

    private final class PosterAdapter extends BaseAdapter {
        private final List<LibraryItem> items;
        private final int requestRevision;

        PosterAdapter(List<LibraryItem> items, int requestRevision) {
            this.items = items;
            this.requestRevision = requestRevision;
        }

        @Override public int getCount() { return items.size(); }
        @Override public LibraryItem getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout card;
            ImageView image;
            TextView name;
            TextView info;
            if (recycled instanceof LinearLayout) {
                card = (LinearLayout) recycled;
                image = (ImageView) ((FrameLayout) card.getChildAt(0)).getChildAt(0);
                name = (TextView) card.getChildAt(1);
                info = (TextView) card.getChildAt(2);
            } else {
                card = column();
                FrameLayout art = new FrameLayout(activity);
                art.setBackground(surface(theme.secondary, .16f, 18));
                art.setClipToOutline(true);
                art.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
                image = new ImageView(activity);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                art.addView(image, new FrameLayout.LayoutParams(-1, -1));
                TextView badge = text("", 9, Color.WHITE, true);
                badge.setGravity(Gravity.CENTER);
                badge.setPadding(dp(9), dp(5), dp(9), dp(5));
                badge.setBackground(surface(theme.primary, .18f, 10));
                FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
                badgeLp.topMargin = dp(8);
                badgeLp.rightMargin = dp(8);
                art.addView(badge, badgeLp);
                card.addView(art, new LinearLayout.LayoutParams(-1, dp(query.kind == LibraryQuery.Kind.EPISODES ? 104 : 202)));
                name = text("", 14, Color.WHITE, true);
                name.setPadding(dp(2), dp(10), dp(2), 0);
                name.setMaxLines(2);
                name.setMinLines(2);
                name.setEllipsize(TextUtils.TruncateAt.END);
                card.addView(name);
                info = text("", 11, 0xBFFFFFFF, false);
                info.setPadding(dp(2), dp(4), dp(2), dp(4));
                info.setSingleLine(true);
                info.setEllipsize(TextUtils.TruncateAt.END);
                card.addView(info);
            }
            LibraryItem item = getItem(position);
            FrameLayout art = (FrameLayout) card.getChildAt(0);
            TextView badge = (TextView) art.getChildAt(1);
            badge.setText(item.resumeTicks > 0 ? "RESUME" : "✓ WATCHED");
            badge.setVisibility(item.resumeTicks > 0 || item.watched ? View.VISIBLE : View.GONE);
            name.setText(item.name);
            info.setText(item.subtitle());
            image.setImageDrawable(null);
            card.setContentDescription(item.name + ", " + item.subtitle());
            loadImage(item, image, requestRevision, false);
            return card;
        }
    }

    private void hideKeyboard() {
        InputMethodManager input = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        if (input != null) input.hideSoftInputFromWindow(search.getWindowToken(), 0);
        root.requestFocus();
    }

    private String errorText(Exception error) {
        if (error instanceof JellyfinClient.HttpError) {
            int status = ((JellyfinClient.HttpError) error).status;
            if (status == 401) return "Your sign-in expired. Return to the remote to sign in again.";
            if (status == 403) return "Your Jellyfin account cannot access this content.";
        }
        return "Couldn't load your library. Check the server connection and try again.";
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans", bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        return view;
    }

    private TextView button(String value, boolean primary, Runnable action) {
        TextView view = text(value, 12, primary ? theme.deep : Color.WHITE, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(16), 0, dp(16), 0);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setBackground(touchBackground(primary));
        view.setClickable(true);
        view.setFocusable(true);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private RippleDrawable touchBackground(boolean primary) {
        GradientDrawable shape = surface(theme.primary, primary ? 1f : .12f, 18);
        return new RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), shape, null);
    }

    private GradientDrawable surface(int accent, float strength, int radius) {
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{ThemeColors.blend(theme.deep, accent, strength),
                        ThemeColors.blend(theme.deep, accent, strength * .80f)});
        shape.setCornerRadius(dp(radius));
        shape.setStroke(dp(1), 0x30FFFFFF);
        return shape;
    }

    private GradientDrawable background() {
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{
                ThemeColors.blend(theme.deep, theme.primary, .16f), theme.deep,
                ThemeColors.blend(theme.deep, theme.secondary, .10f)});
    }

    private int dp(float value) { return (int) (value * activity.getResources().getDisplayMetrics().density + .5f); }
}
