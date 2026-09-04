package com.signalbox.jellyremote;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Space;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

@SuppressLint({"ViewConstructor", "SetTextI18n"})
final class RemoteView extends FrameLayout {
    interface Listener {
        void onPlayState(String command);
        void onSeek(long ticks);
        void onVolume(int volume);
        void onMute();
        void onSubtitleToggle();
        void onCycleSession();
        void onLogout();
    }

    private final Listener listener;
    private final ImageView backdrop;
    private final ImageView artTile;
    private final AmbientScrim scrim;
    private final TextView connectionLabel;
    private final TextView sessionTitle;
    private final TextView sessionMeta;
    private final TextView eyebrow;
    private final TextView title;
    private final TextView stateBadge;
    private final TextView elapsed;
    private final TextView remaining;
    private final TextView helper;
    private final TextView chapterButton;
    private final TextView subtitleButton;
    private final TextView stopButton;
    private final SeekBar progress;
    private final SeekBar volume;
    private final IconButton previous;
    private final IconButton rewind;
    private final IconButton playPause;
    private final IconButton forward;
    private final IconButton next;
    private final IconButton mute;
    private final View sessionCard;
    private final View mediaCard;
    private final View controlCard;
    private final TextView logoMark;
    private RemoteState state;
    private List<RemoteState> sessions = Collections.emptyList();
    private ThemeColors theme = ThemeColors.defaults();
    private List<Chapter> chapters = Collections.emptyList();
    private boolean seeking;
    private boolean changingVolume;

    RemoteView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setBackgroundColor(theme.deep);

        backdrop = new ImageView(context);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(.75f);
        addView(backdrop, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        scrim = new AmbientScrim(context);
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(42), dp(20), dp(34));
        scroll.addView(content, new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LinearLayout masthead = row(context, Gravity.CENTER_VERTICAL);
        logoMark = text("JELLYREMOTE", 13, Color.WHITE, Typeface.BOLD);
        logoMark.setLetterSpacing(.15f);
        masthead.addView(logoMark, new LinearLayout.LayoutParams(0, dp(42), 1));
        connectionLabel = text("●  CONNECTING", 10, 0xCCFFFFFF, Typeface.BOLD);
        connectionLabel.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        masthead.addView(connectionLabel, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(42)));
        content.addView(masthead);

        LinearLayout session = new LinearLayout(context);
        session.setOrientation(LinearLayout.VERTICAL);
        session.setGravity(Gravity.CENTER_VERTICAL);
        session.setPadding(dp(18), dp(12), dp(18), dp(12));
        session.setClickable(true);
        session.setFocusable(true);
        session.setContentDescription("Current playback session. Tap to switch sessions.");
        session.setOnClickListener(v -> listener.onCycleSession());
        sessionTitle = text("Looking for a playback session…", 14, Color.WHITE, Typeface.BOLD);
        sessionMeta = text("Jellyfin session", 11, 0xBFFFFFFF, Typeface.NORMAL);
        sessionMeta.setPadding(0, dp(3), 0, 0);
        session.addView(sessionTitle);
        session.addView(sessionMeta);
        sessionCard = session;
        LinearLayout.LayoutParams sessionLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(72));
        sessionLp.topMargin = dp(2);
        content.addView(session, sessionLp);

        Space atmosphere = new Space(context);
        content.addView(atmosphere, new LinearLayout.LayoutParams(1, dp(86)));

        LinearLayout media = row(context, Gravity.BOTTOM);
        media.setPadding(dp(14), dp(14), dp(16), dp(14));
        artTile = new ImageView(context);
        artTile.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artTile.setClipToOutline(true);
        artTile.setOutlineProvider(new RoundedOutline(dp(15)));
        media.addView(artTile, new LinearLayout.LayoutParams(dp(92), dp(126)));

        LinearLayout mediaWords = new LinearLayout(context);
        mediaWords.setOrientation(LinearLayout.VERTICAL);
        mediaWords.setGravity(Gravity.BOTTOM);
        mediaWords.setPadding(dp(16), 0, 0, dp(2));
        eyebrow = text("READY WHEN YOU ARE", 10, 0xD9FFFFFF, Typeface.BOLD);
        eyebrow.setLetterSpacing(.12f);
        title = text("Nothing playing", 27, Color.WHITE, Typeface.BOLD);
        title.setMaxLines(3);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, dp(6), 0, dp(10));
        stateBadge = pill("WAITING");
        mediaWords.addView(eyebrow);
        mediaWords.addView(title);
        mediaWords.addView(stateBadge, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(28)));
        media.addView(mediaWords, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
        mediaCard = media;
        content.addView(media, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(154)));

        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(16), dp(15), dp(16), dp(14));
        LinearLayout timeRow = row(context, Gravity.CENTER_VERTICAL);
        elapsed = text("0:00", 11, Color.WHITE, Typeface.BOLD);
        remaining = text("−0:00", 11, 0xBFFFFFFF, Typeface.NORMAL);
        remaining.setGravity(Gravity.END);
        timeRow.addView(elapsed, new LinearLayout.LayoutParams(0, dp(22), 1));
        timeRow.addView(remaining, new LinearLayout.LayoutParams(0, dp(22), 1));
        controls.addView(timeRow);

        progress = new SeekBar(context);
        progress.setMax(1000);
        progress.setPadding(0, 0, 0, 0);
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar seekBar) { seeking = true; }
            @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (fromUser && state != null) {
                    long position = state.durationTicks * value / 1000L;
                    elapsed.setText(formatTicks(position));
                    remaining.setText("−" + formatTicks(Math.max(0L, state.durationTicks - position)));
                }
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                seeking = false;
                if (state != null && state.durationTicks > 0) {
                    listener.onSeek(state.durationTicks * seekBar.getProgress() / 1000L);
                }
            }
        });
        controls.addView(progress, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(30)));

        LinearLayout transport = row(context, Gravity.CENTER);
        previous = icon(IconButton.Kind.PREVIOUS, "Previous episode", () -> listener.onPlayState("PreviousTrack"));
        rewind = icon(IconButton.Kind.REWIND, "Back 10 seconds", () -> seekRelative(-10));
        playPause = icon(IconButton.Kind.PLAY, "Play", () -> listener.onPlayState(state != null && state.paused ? "Unpause" : "Pause"));
        playPause.setHero(true);
        forward = icon(IconButton.Kind.FORWARD, "Forward 10 seconds", () -> seekRelative(10));
        next = icon(IconButton.Kind.NEXT, "Next episode", () -> listener.onPlayState("NextTrack"));
        addTransport(transport, previous, false);
        addTransport(transport, rewind, false);
        addTransport(transport, playPause, true);
        addTransport(transport, forward, false);
        addTransport(transport, next, false);
        controls.addView(transport, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(72)));

        LinearLayout volumeRow = row(context, Gravity.CENTER_VERTICAL);
        mute = icon(IconButton.Kind.VOLUME, "Mute", listener::onMute);
        volumeRow.addView(mute, new LinearLayout.LayoutParams(dp(44), dp(44)));
        volume = new SeekBar(context);
        volume.setMax(100);
        volume.setPadding(dp(8), 0, 0, 0);
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar seekBar) { changingVolume = true; }
            @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                changingVolume = false;
                listener.onVolume(seekBar.getProgress());
            }
        });
        volumeRow.addView(volume, new LinearLayout.LayoutParams(0, dp(44), 1));
        controls.addView(volumeRow);

        chapterButton = actionText("▤   CHAPTERS");
        chapterButton.setContentDescription("Choose a chapter");
        chapterButton.setOnClickListener(v -> showChapterPicker());
        chapterButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams chapterLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44));
        chapterLp.topMargin = dp(8);
        controls.addView(chapterButton, chapterLp);

        LinearLayout utility = row(context, Gravity.CENTER_VERTICAL);
        subtitleButton = actionText("CC   SUBTITLES");
        subtitleButton.setOnClickListener(v -> listener.onSubtitleToggle());
        utility.addView(subtitleButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        Space gap = new Space(context);
        utility.addView(gap, new LinearLayout.LayoutParams(dp(10), 1));
        stopButton = actionText("■   STOP");
        stopButton.setOnClickListener(v -> listener.onPlayState("Stop"));
        utility.addView(stopButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        LinearLayout.LayoutParams utilityLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44));
        utilityLp.topMargin = dp(8);
        controls.addView(utility, utilityLp);

        helper = text("Controls become available when playback starts on another device.", 11, 0xAFFFFFFF, Typeface.NORMAL);
        helper.setGravity(Gravity.CENTER);
        helper.setPadding(dp(4), dp(14), dp(4), 0);
        controls.addView(helper);
        controlCard = controls;
        LinearLayout.LayoutParams controlLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        controlLp.topMargin = dp(12);
        content.addView(controls, controlLp);

        TextView logout = text("DISCONNECT SERVER", 10, 0x99FFFFFF, Typeface.BOLD);
        logout.setGravity(Gravity.CENTER);
        logout.setLetterSpacing(.12f);
        logout.setClickable(true);
        logout.setFocusable(true);
        logout.setOnClickListener(v -> listener.onLogout());
        LinearLayout.LayoutParams logoutLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(52));
        logoutLp.topMargin = dp(8);
        content.addView(logout, logoutLp);

        applyTheme(theme);
        setState(null, Collections.emptyList());
    }

    void setChapters(List<Chapter> chapters) {
        this.chapters = chapters == null ? Collections.emptyList() : chapters;
        boolean available = state != null && state.hasMedia && !this.chapters.isEmpty();
        chapterButton.setVisibility(available ? View.VISIBLE : View.GONE);
        chapterButton.setEnabled(available);
        chapterButton.setText(available
                ? "▤   CHAPTERS  ·  " + this.chapters.size()
                : "▤   CHAPTERS");
        updateMediaHelper();
    }

    void setConnectionStatus(boolean online, String text) {
        connectionLabel.setText((online ? "●  " : "○  ") + text.toUpperCase(Locale.US));
        connectionLabel.setTextColor(online ? theme.primary : 0xBFFFFFFF);
    }

    void setState(RemoteState state, List<RemoteState> sessions) {
        this.state = state;
        this.sessions = sessions == null ? Collections.emptyList() : sessions;
        boolean ready = state != null && state.hasMedia && state.supportsControl;

        if (state == null) {
            sessionTitle.setText("Waiting for a playback session");
            sessionMeta.setText("Open Jellyfin on another device and start a video");
            eyebrow.setText("READY WHEN YOU ARE");
            title.setText("Nothing playing");
            stateBadge.setText("WAITING");
            helper.setText("Controls become available when playback starts on another device.");
            updateTimes(0, 0);
        } else {
            sessionTitle.setText(state.deviceLabel());
            String account = state.userName.isEmpty() ? "Jellyfin user" : state.userName;
            String sessionHint = this.sessions.size() > 1
                    ? account + "  •  tap to switch  •  " + this.sessions.size() + " sessions"
                    : account + "  •  active session";
            sessionMeta.setText(sessionHint);
            if (state.hasMedia) {
                eyebrow.setText(state.eyebrow());
                title.setText(state.title);
                stateBadge.setText(state.paused ? "PAUSED" : "NOW PLAYING");
                updateMediaHelper();
                updateTimes(state.positionTicks, state.durationTicks);
            } else {
                eyebrow.setText("BROWSER CONNECTED");
                title.setText("Choose something in Jellyfin");
                stateBadge.setText("IDLE");
                helper.setText("This session is ready for remote control.");
                updateTimes(0, 0);
            }
        }

        playPause.setKind(state != null && state.paused ? IconButton.Kind.PLAY : IconButton.Kind.PAUSE);
        playPause.setContentDescription(state != null && state.paused ? "Play" : "Pause");
        mute.setKind(state != null && state.muted ? IconButton.Kind.MUTED : IconButton.Kind.VOLUME);
        mute.setContentDescription(state != null && state.muted ? "Unmute" : "Mute");
        if (!changingVolume) volume.setProgress(state == null ? 100 : state.volume);
        subtitleButton.setText(state != null && state.subtitleIndex >= 0 ? "CC   SUBTITLES ON" : "CC   SUBTITLES");
        setControlsEnabled(ready);
    }

    private void updateMediaHelper() {
        if (state == null || !state.hasMedia) return;
        if (!chapters.isEmpty()) {
            helper.setText(chapters.size() + (chapters.size() == 1
                    ? " chapter available  •  tap Chapters to jump"
                    : " chapters available  •  tap Chapters to jump"));
        } else {
            helper.setText("Remote commands are sent directly to this playback session");
        }
    }

    void setArtwork(Bitmap bitmap, ThemeColors colors) {
        if (bitmap != null) {
            backdrop.setImageBitmap(bitmap);
            artTile.setImageBitmap(bitmap);
            if (Build.VERSION.SDK_INT >= 31) {
                backdrop.setRenderEffect(RenderEffect.createBlurEffect(18f, 18f, Shader.TileMode.MIRROR));
            }
        } else {
            backdrop.setImageDrawable(null);
            artTile.setImageDrawable(null);
        }
        applyTheme(colors == null ? ThemeColors.defaults() : colors);
    }

    private void updateTimes(long position, long duration) {
        if (!seeking) {
            int value = duration <= 0 ? 0 : (int) Math.min(1000L, position * 1000L / duration);
            progress.setProgress(value);
            elapsed.setText(formatTicks(position));
            remaining.setText("−" + formatTicks(Math.max(0L, duration - position)));
        }
    }

    private void setControlsEnabled(boolean enabled) {
        float alpha = enabled ? 1f : .38f;
        for (View view : new View[]{previous, rewind, playPause, forward, next, mute, volume, chapterButton, subtitleButton, stopButton, progress}) {
            view.setEnabled(enabled);
            view.setAlpha(alpha);
        }
        chapterButton.setEnabled(enabled && !chapters.isEmpty());
    }

    private void seekRelative(int seconds) {
        if (state == null) return;
        long target = state.positionTicks + seconds * 10_000_000L;
        target = Math.max(0L, Math.min(state.durationTicks, target));
        listener.onSeek(target);
    }

    private void showChapterPicker() {
        if (chapters.isEmpty()) return;
        Dialog dialog = new Dialog(getContext());
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout panel = new LinearLayout(getContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(18), dp(20), dp(22));
        panel.setBackground(glass(theme.primary, theme.secondary, .30f, 28));

        LinearLayout heading = row(getContext(), Gravity.CENTER_VERTICAL);
        TextView headingText = text("SELECT CHAPTER", 11, Color.WHITE, Typeface.BOLD);
        headingText.setLetterSpacing(.13f);
        headingText.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(headingText, new LinearLayout.LayoutParams(0, dp(42), 1));
        TextView close = text("CLOSE", 10, theme.primary, Typeface.BOLD);
        close.setGravity(Gravity.CENTER);
        close.setClickable(true);
        close.setFocusable(true);
        close.setPadding(dp(14), 0, dp(8), 0);
        close.setOnClickListener(v -> dialog.dismiss());
        heading.addView(close, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(42)));
        panel.addView(heading);

        ScrollView chapterScroll = new ScrollView(getContext());
        chapterScroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(getContext());
        list.setOrientation(LinearLayout.VERTICAL);
        chapterScroll.addView(list, new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * .62f);
        int listHeight = Math.min(maxHeight, dp(62 * chapters.size() + 4));
        panel.addView(chapterScroll, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, listHeight));

        for (int i = 0; i < chapters.size(); i++) {
            Chapter chapter = chapters.get(i);
            LinearLayout row = row(getContext(), Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(16), 0);
            row.setBackground(pillBackground(i % 2 == 0 ? theme.primary : theme.secondary, .18f));
            row.setClickable(true);
            row.setFocusable(true);
            row.setContentDescription(chapter.displayName(i) + ", " + chapter.timeLabel());
            row.setOnClickListener(v -> {
                listener.onSeek(chapter.startTicks);
                dialog.dismiss();
            });
            TextView name = text(chapter.displayName(i), 14, Color.WHITE, Typeface.BOLD);
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(name, new LinearLayout.LayoutParams(0, dp(54), 1));
            TextView time = text(chapter.timeLabel(), 12, theme.primary, Typeface.BOLD);
            time.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            row.addView(time, new LinearLayout.LayoutParams(dp(76), dp(54)));
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(54));
            rowLp.topMargin = dp(8);
            list.addView(row, rowLp);
        }

        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.gravity = Gravity.BOTTOM;
            params.dimAmount = .72f;
            window.setAttributes(params);
        }
        dialog.show();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private void applyTheme(ThemeColors colors) {
        theme = colors;
        setBackgroundColor(theme.deep);
        scrim.setColors(theme.primary, theme.secondary, theme.deep);
        sessionCard.setBackground(glass(theme.primary, theme.secondary, .27f, 22));
        mediaCard.setBackground(glass(theme.secondary, theme.primary, .32f, 25));
        controlCard.setBackground(glass(theme.primary, theme.secondary, .24f, 27));
        stateBadge.setBackground(pillBackground(theme.primary, .34f));
        stateBadge.setTextColor(theme.primary);
        chapterButton.setBackground(pillBackground(theme.primary, .24f));
        subtitleButton.setBackground(pillBackground(theme.secondary, .24f));
        int stopTint = ThemeColors.blend(theme.secondary, Color.rgb(255, 139, 132), .42f);
        stopButton.setBackground(pillBackground(stopTint, .21f));
        progress.setProgressTintList(ColorStateList.valueOf(theme.primary));
        progress.setThumbTintList(ColorStateList.valueOf(theme.primary));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(withAlpha(theme.secondary, 62)));
        volume.setProgressTintList(ColorStateList.valueOf(theme.secondary));
        volume.setThumbTintList(ColorStateList.valueOf(theme.secondary));
        volume.setProgressBackgroundTintList(ColorStateList.valueOf(withAlpha(theme.primary, 62)));
        for (IconButton icon : new IconButton[]{previous, rewind, playPause, forward, next}) icon.setAccent(theme.primary);
        mute.setAccent(theme.secondary);
        connectionLabel.setTextColor(theme.primary);
        logoMark.setTextColor(ThemeColors.blend(Color.WHITE, theme.primary, .30f));
        sessionMeta.setTextColor(ThemeColors.blend(Color.WHITE, theme.primary, .28f));
        eyebrow.setTextColor(ThemeColors.blend(Color.WHITE, theme.secondary, .38f));
        elapsed.setTextColor(theme.primary);
        remaining.setTextColor(ThemeColors.blend(Color.WHITE, theme.secondary, .25f));
        helper.setTextColor(ThemeColors.blend(Color.WHITE, theme.secondary, .18f));
    }

    private GradientDrawable glass(int tint, int companion, float strength, int radius) {
        int first = ThemeColors.blend(theme.deep, tint, strength);
        int second = ThemeColors.blend(theme.deep, companion, strength * .64f);
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{withAlpha(first, 222), withAlpha(second, 194)});
        shape.setCornerRadius(dp(radius));
        shape.setStroke(dp(1), withAlpha(ThemeColors.blend(Color.WHITE, tint, .42f), 76));
        return shape;
    }

    private GradientDrawable pillBackground(int tint, float strength) {
        int mixed = ThemeColors.blend(theme.deep, tint, strength);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(withAlpha(mixed, 224));
        shape.setCornerRadius(dp(50));
        shape.setStroke(dp(1), withAlpha(tint, 80));
        return shape;
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private TextView pill(String value) {
        TextView view = text(value, 9, Color.WHITE, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setLetterSpacing(.12f);
        view.setPadding(dp(13), 0, dp(13), 0);
        return view;
    }

    private TextView actionText(String value) {
        TextView view = text(value, 10, Color.WHITE, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setLetterSpacing(.08f);
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans", style));
        view.setIncludeFontPadding(false);
        return view;
    }

    private LinearLayout row(Context context, int gravity) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(gravity);
        return row;
    }

    private IconButton icon(IconButton.Kind kind, String description, Runnable action) {
        IconButton button = new IconButton(getContext(), kind);
        button.setContentDescription(description);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private void addTransport(LinearLayout row, View view, boolean hero) {
        int size = hero ? 64 : 51;
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(size), 1);
        params.leftMargin = dp(2);
        params.rightMargin = dp(2);
        row.addView(view, params);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }

    private static String formatTicks(long ticks) {
        long seconds = Math.max(0L, ticks / 10_000_000L);
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainder = seconds % 60;
        return hours > 0
                ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, remainder)
                : String.format(Locale.US, "%d:%02d", minutes, remainder);
    }

    private static final class RoundedOutline extends ViewOutlineProvider {
        private final int radius;
        RoundedOutline(int radius) { this.radius = radius; }
        @Override public void getOutline(View view, android.graphics.Outline outline) {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
        }
    }

    private static final class AmbientScrim extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int primary;
        private int secondary;
        private int deep;
        private Shader firstGlow;
        private Shader secondGlow;
        private Shader shade;

        AmbientScrim(Context context) {
            super(context);
            setColors(0xFF71E1F4, 0xFFA27FF1, 0xFF071018);
        }

        void setColors(int primary, int secondary, int deep) {
            this.primary = primary;
            this.secondary = secondary;
            this.deep = deep;
            rebuildShaders(getWidth(), getHeight());
            invalidate();
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            rebuildShaders(width, height);
        }

        private void rebuildShaders(float width, float height) {
            if (width <= 0 || height <= 0) return;
            firstGlow = new RadialGradient(width * .12f, height * .33f, width * .85f,
                    Color.argb(95, Color.red(primary), Color.green(primary), Color.blue(primary)),
                    Color.TRANSPARENT, Shader.TileMode.CLAMP);
            secondGlow = new RadialGradient(width * .92f, height * .73f, width,
                    Color.argb(80, Color.red(secondary), Color.green(secondary), Color.blue(secondary)),
                    Color.TRANSPARENT, Shader.TileMode.CLAMP);
            shade = new LinearGradient(0, 0, 0, height,
                    new int[]{Color.argb(105, 2, 7, 11), Color.argb(70, Color.red(deep), Color.green(deep), Color.blue(deep)), Color.argb(238, 3, 8, 13)},
                    new float[]{0f, .40f, 1f}, Shader.TileMode.CLAMP);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            paint.setShader(firstGlow);
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(secondGlow);
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(shade);
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
        }
    }

    static final class IconButton extends View {
        enum Kind { PREVIOUS, REWIND, PLAY, PAUSE, FORWARD, NEXT, VOLUME, MUTED }
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private Kind kind;
        private int accent = 0xFF71E1F4;
        private boolean hero;

        IconButton(Context context, Kind kind) {
            super(context);
            this.kind = kind;
            setClickable(true);
            setFocusable(true);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        void setKind(Kind kind) { this.kind = kind; invalidate(); }
        void setAccent(int color) { accent = color; invalidate(); }
        void setHero(boolean hero) { this.hero = hero; invalidate(); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = Math.min(getWidth(), getHeight()) * (hero ? .43f : .37f);
            paint.setStyle(Paint.Style.FILL);
            int base = hero ? accent : Color.WHITE;
            paint.setColor(hero
                    ? Color.argb(235, Color.red(base), Color.green(base), Color.blue(base))
                    : 0x20FFFFFF);
            paint.setShadowLayer(hero ? 15 : 0, 0, hero ? 7 : 0,
                    Color.argb(105, Color.red(accent), Color.green(accent), Color.blue(accent)));
            canvas.drawCircle(cx, cy, radius, paint);
            paint.clearShadowLayer();
            paint.setColor(hero ? 0xFF071018 : Color.WHITE);
            paint.setStrokeWidth(Math.max(2.4f, getWidth() / 18f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);

            float s = Math.min(getWidth(), getHeight()) * .20f;
            switch (kind) {
                case PLAY:
                    path.reset();
                    path.moveTo(cx - s * .55f, cy - s);
                    path.lineTo(cx + s, cy);
                    path.lineTo(cx - s * .55f, cy + s);
                    path.close();
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawPath(path, paint);
                    break;
                case PAUSE:
                    paint.setStyle(Paint.Style.STROKE);
                    canvas.drawLine(cx - s * .48f, cy - s, cx - s * .48f, cy + s, paint);
                    canvas.drawLine(cx + s * .48f, cy - s, cx + s * .48f, cy + s, paint);
                    break;
                case PREVIOUS:
                case NEXT:
                    drawSkip(canvas, cx, cy, s, kind == Kind.NEXT);
                    break;
                case REWIND:
                case FORWARD:
                    drawTen(canvas, cx, cy, s, kind == Kind.FORWARD);
                    break;
                case VOLUME:
                case MUTED:
                    drawVolume(canvas, cx, cy, s, kind == Kind.MUTED);
                    break;
            }
        }

        private void drawSkip(Canvas canvas, float cx, float cy, float s, boolean right) {
            paint.setStyle(Paint.Style.FILL);
            float direction = right ? 1 : -1;
            path.reset();
            path.moveTo(cx - direction * s * .7f, cy - s);
            path.lineTo(cx + direction * s * .65f, cy);
            path.lineTo(cx - direction * s * .7f, cy + s);
            path.close();
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE);
            float x = cx + direction * s * .83f;
            canvas.drawLine(x, cy - s, x, cy + s, paint);
        }

        private void drawTen(Canvas canvas, float cx, float cy, float s, boolean right) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, getWidth() / 24f));
            float direction = right ? 1 : -1;
            canvas.drawArc(cx - s, cy - s, cx + s, cy + s, right ? -70 : -110, right ? 275 : -275, false, paint);
            path.reset();
            path.moveTo(cx + direction * s * .82f, cy - s * .82f);
            path.lineTo(cx + direction * s * 1.16f, cy - s * .72f);
            path.lineTo(cx + direction * s * .95f, cy - s * .42f);
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.create("sans", Typeface.BOLD));
            paint.setTextSize(s * .86f);
            canvas.drawText("10", cx, cy + s * .34f, paint);
        }

        private void drawVolume(Canvas canvas, float cx, float cy, float s, boolean muted) {
            paint.setStyle(Paint.Style.FILL);
            path.reset();
            path.moveTo(cx - s, cy - s * .35f);
            path.lineTo(cx - s * .42f, cy - s * .35f);
            path.lineTo(cx + s * .12f, cy - s * .9f);
            path.lineTo(cx + s * .12f, cy + s * .9f);
            path.lineTo(cx - s * .42f, cy + s * .35f);
            path.lineTo(cx - s, cy + s * .35f);
            path.close();
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE);
            if (muted) {
                canvas.drawLine(cx + s * .35f, cy - s * .55f, cx + s, cy + s * .55f, paint);
                canvas.drawLine(cx + s, cy - s * .55f, cx + s * .35f, cy + s * .55f, paint);
            } else {
                canvas.drawArc(cx - s * .34f, cy - s * .64f, cx + s * .83f, cy + s * .64f, -52, 104, false, paint);
            }
        }

    }
}
