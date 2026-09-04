package com.signalbox.jellyremote;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressLint("SetTextI18n")
public final class MainActivity extends Activity implements RemoteView.Listener {
    private static final String PREFS = "jelly_remote";
    private static final String SERVER = "server";
    private static final String TOKEN = "token";
    private static final String USER_ID = "user_id";
    private static final String USER_NAME = "user_name";
    private static final String DEVICE_ID = "device_id";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private JellyfinClient client;
    private RemoteView remoteView;
    private RemoteState selectedState;
    private List<RemoteState> sessions = new ArrayList<>();
    private String selectedSessionId = "";
    private String loadedArtKey = "";
    private String loadedChapterKey = "\u0000";
    private boolean polling;
    private int generation;

    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            pollNow();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        ensureDeviceId();
        if (hasSavedSession()) showRemote(); else showLogin(null);
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.rgb(5, 11, 17));
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            // Some Android 12 vendor builds do not attach PhoneWindow's controller
            // until the decor view has been created.
            WindowInsetsController controller = window.getDecorView().getWindowInsetsController();
            if (controller != null) controller.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
        } else {
            window.setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        }
    }

    private boolean hasSavedSession() {
        return !prefs.getString(SERVER, "").isEmpty()
                && !prefs.getString(TOKEN, "").isEmpty()
                && !prefs.getString(USER_ID, "").isEmpty();
    }

    private void ensureDeviceId() {
        if (prefs.getString(DEVICE_ID, "").isEmpty()) {
            prefs.edit().putString(DEVICE_ID, UUID.randomUUID().toString()).apply();
        }
    }

    private void showLogin(String message) {
        generation++;
        main.removeCallbacks(pollTask);
        selectedState = null;
        sessions.clear();
        remoteView = null;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(5, 12, 19));
        root.addView(new LoginAtmosphere(this), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(24), dp(72), dp(24), dp(38));
        scroll.addView(page, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        TextView logo = label("JELLYREMOTE", 13, Color.WHITE, Typeface.BOLD);
        logo.setLetterSpacing(.16f);
        logo.setGravity(Gravity.CENTER);
        page.addView(logo, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));

        TextView heading = label("Your screen,\nwithin reach.", 38, Color.WHITE, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);
        heading.setLineSpacing(0, .93f);
        LinearLayout.LayoutParams headingLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        headingLp.topMargin = dp(42);
        page.addView(heading, headingLp);

        TextView intro = label("Control any active Jellyfin session from your phone.", 14, 0xC9FFFFFF, Typeface.NORMAL);
        intro.setGravity(Gravity.CENTER);
        intro.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        introLp.topMargin = dp(18);
        introLp.bottomMargin = dp(34);
        page.addView(intro, introLp);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(22), dp(20), dp(21));
        card.setBackground(glass(0xFF75DFF2, .14f, 28));
        page.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView setupLabel = label("CONNECT TO JELLYFIN", 10, 0xDFFFFFFF, Typeface.BOLD);
        setupLabel.setLetterSpacing(.12f);
        card.addView(setupLabel);

        EditText server = field("Server address", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        server.setSingleLine(true);
        server.setText(prefs.getString(SERVER, ""));
        server.setHint("http://server-address:8096");
        addField(card, server, 15);

        EditText username = field("Username", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL);
        username.setSingleLine(true);
        username.setText(prefs.getString(USER_NAME, ""));
        username.setHint("Jellyfin username");
        addField(card, username, 11);

        EditText password = field("Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setSingleLine(true);
        addField(card, password, 11);

        TextView error = label(message == null ? "" : message, 12, 0xFFFFA8AE, Typeface.NORMAL);
        error.setVisibility(message == null ? View.GONE : View.VISIBLE);
        error.setPadding(dp(3), dp(12), dp(3), 0);
        card.addView(error);

        Button connect = new Button(this);
        connect.setText("SIGN IN");
        connect.setTextSize(11);
        connect.setTextColor(Color.rgb(5, 18, 24));
        connect.setTypeface(Typeface.create("sans", Typeface.BOLD));
        connect.setLetterSpacing(.1f);
        connect.setAllCaps(false);
        connect.setBackground(solid(0xFF8BE9FD, 50));
        connect.setStateListAnimator(null);
        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        buttonLp.topMargin = dp(18);
        card.addView(connect, buttonLp);

        TextView privacy = label("Credentials go only to your Jellyfin server. The password is never stored; the access token stays in this app's private storage.", 11, 0x93FFFFFF, Typeface.NORMAL);
        privacy.setGravity(Gravity.CENTER);
        privacy.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams privacyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        privacyLp.topMargin = dp(19);
        page.addView(privacy, privacyLp);

        connect.setOnClickListener(v -> {
            String serverValue = server.getText().toString().trim();
            String userValue = username.getText().toString().trim();
            if (serverValue.isEmpty() || userValue.isEmpty()) {
                error.setText("Enter the server address and username.");
                error.setVisibility(View.VISIBLE);
                return;
            }
            hideKeyboard(v);
            connect.setEnabled(false);
            connect.setAlpha(.6f);
            connect.setText("CONNECTING…");
            error.setVisibility(View.GONE);
            login(serverValue, userValue, password.getText().toString(), connect, error);
        });

        setContentView(root);
        applyBottomInset(page);
    }

    private void login(String server, String username, String password, Button button, TextView errorView) {
        final int requestGeneration = generation;
        network.execute(() -> {
            try {
                JellyfinClient candidate = new JellyfinClient(server,
                        prefs.getString(DEVICE_ID, ""), "", "");
                JellyfinClient.AuthResult auth = candidate.login(username, password);
                main.post(() -> {
                    if (generation != requestGeneration || isFinishing()) return;
                    prefs.edit()
                            .putString(SERVER, candidate.serverUrl)
                            .putString(TOKEN, auth.token)
                            .putString(USER_ID, auth.userId)
                            .putString(USER_NAME, auth.userName)
                            .apply();
                    client = candidate;
                    showRemote();
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (generation != requestGeneration || isFinishing()) return;
                    errorView.setText(friendlyError(failure));
                    errorView.setVisibility(View.VISIBLE);
                    button.setEnabled(true);
                    button.setAlpha(1f);
                    button.setText("SIGN IN");
                });
            }
        });
    }

    private void showRemote() {
        generation++;
        loadedArtKey = "";
        loadedChapterKey = "\u0000";
        selectedSessionId = "";
        client = new JellyfinClient(
                prefs.getString(SERVER, ""),
                prefs.getString(DEVICE_ID, ""),
                prefs.getString(TOKEN, ""),
                prefs.getString(USER_ID, ""));
        remoteView = new RemoteView(this, this);
        setContentView(remoteView);
        applyBottomInset(remoteView);
        remoteView.setConnectionStatus(false, "connecting");
        main.removeCallbacks(pollTask);
        main.post(pollTask);
    }

    private void pollNow() {
        if (polling || client == null || remoteView == null) return;
        polling = true;
        final int requestGeneration = generation;
        network.execute(() -> {
            try {
                List<RemoteState> result = client.getSessions();
                result.sort(Comparator.comparingInt(this::sessionScore).reversed());
                main.post(() -> {
                    polling = false;
                    if (generation != requestGeneration || remoteView == null) return;
                    sessions = result;
                    RemoteState chosen = chooseSession(result);
                    selectedState = chosen;
                    if (chosen != null) selectedSessionId = chosen.sessionId;
                    remoteView.setConnectionStatus(true, chosen == null ? "server online" : "live");
                    remoteView.setState(chosen, result);
                    refreshArtwork(chosen, requestGeneration);
                    refreshChapters(chosen, requestGeneration);
                    main.postDelayed(pollTask, 1600);
                });
            } catch (Exception failure) {
                main.post(() -> {
                    polling = false;
                    if (generation != requestGeneration || remoteView == null) return;
                    if (failure instanceof JellyfinClient.HttpError
                            && ((JellyfinClient.HttpError) failure).status == 401) {
                        clearAuth();
                        showLogin("Your Jellyfin session expired. Please sign in again.");
                        return;
                    }
                    remoteView.setConnectionStatus(false, "reconnecting");
                    main.postDelayed(pollTask, 3500);
                });
            }
        });
    }

    private RemoteState chooseSession(List<RemoteState> values) {
        if (values.isEmpty()) return null;
        if (!selectedSessionId.isEmpty()) {
            for (RemoteState session : values) if (selectedSessionId.equals(session.sessionId)) return session;
        }
        return values.get(0);
    }

    private int sessionScore(RemoteState session) {
        int score = 0;
        if (session.hasMedia) score += 160;
        if (session.active) score += 40;
        if (session.supportsControl) score += 30;
        if (session.userName.equalsIgnoreCase(prefs.getString(USER_NAME, ""))) score += 20;
        return score;
    }

    private void refreshArtwork(RemoteState state, int requestGeneration) {
        String artKey = state == null || !state.hasMedia ? "" : state.artKey;
        if (artKey.equals(loadedArtKey)) return;
        loadedArtKey = artKey;
        if (artKey.isEmpty()) {
            remoteView.setArtwork(null, ThemeColors.defaults());
            return;
        }
        network.execute(() -> {
            try {
                Bitmap bitmap = client.getArtwork(state);
                ThemeColors colors = ThemeColors.from(bitmap);
                main.post(() -> {
                    if (generation == requestGeneration && remoteView != null && artKey.equals(loadedArtKey)) {
                        remoteView.setArtwork(bitmap, colors);
                    }
                });
            } catch (Exception ignored) {
                main.post(() -> {
                    if (generation == requestGeneration && remoteView != null && artKey.equals(loadedArtKey)) {
                        remoteView.setArtwork(null, ThemeColors.defaults());
                    }
                });
            }
        });
    }

    private void refreshChapters(RemoteState state, int requestGeneration) {
        String chapterKey = state == null || !state.hasMedia ? "" : state.itemId;
        if (chapterKey.equals(loadedChapterKey)) return;
        loadedChapterKey = chapterKey;
        if (remoteView == null) return;
        if (chapterKey.isEmpty()) {
            remoteView.setChapters(new ArrayList<>());
            return;
        }
        if (!state.chapters.isEmpty()) {
            remoteView.setChapters(state.chapters);
            return;
        }
        remoteView.setChapters(new ArrayList<>());
        network.execute(() -> {
            try {
                List<Chapter> discovered = client.getChapters(state.itemId);
                main.post(() -> {
                    if (generation == requestGeneration && remoteView != null
                            && chapterKey.equals(loadedChapterKey)) {
                        remoteView.setChapters(discovered);
                    }
                });
            } catch (Exception ignored) {
                // Chapters are optional; a missing or older endpoint simply hides the control.
            }
        });
    }

    @Override public void onPlayState(String command) {
        RemoteState target = selectedState;
        if (target == null) return;
        runCommand(() -> client.playState(target.sessionId, command, null));
    }

    @Override public void onSeek(long ticks) {
        RemoteState target = selectedState;
        if (target == null) return;
        runCommand(() -> client.playState(target.sessionId, "Seek", ticks));
    }

    @Override public void onVolume(int value) {
        RemoteState target = selectedState;
        if (target == null) return;
        runCommand(() -> client.generalCommand(target.sessionId, "SetVolume", "Volume", value));
    }

    @Override public void onMute() {
        RemoteState target = selectedState;
        if (target == null) return;
        runCommand(() -> client.simpleCommand(target.sessionId, "ToggleMute"));
    }

    @Override public void onSubtitleToggle() {
        RemoteState target = selectedState;
        if (target == null) return;
        int index = target.subtitleIndex >= 0 ? -1 : target.firstSubtitleIndex();
        if (index < 0 && target.subtitleIndex < 0) {
            final int requestGeneration = generation;
            network.execute(() -> {
                try {
                    int discovered = client.findFirstSubtitleIndex(target.itemId);
                    if (discovered < 0) {
                        main.post(() -> Toast.makeText(this, "This item has no subtitle streams.", Toast.LENGTH_SHORT).show());
                        return;
                    }
                    client.generalCommand(target.sessionId, "SetSubtitleStreamIndex", "Index", discovered);
                    main.post(() -> {
                        if (generation == requestGeneration) {
                            main.removeCallbacks(pollTask);
                            main.postDelayed(pollTask, 180);
                        }
                    });
                } catch (Exception failure) {
                    main.post(() -> {
                        if (generation == requestGeneration) Toast.makeText(this, friendlyError(failure), Toast.LENGTH_SHORT).show();
                    });
                }
            });
            return;
        }
        runCommand(() -> client.generalCommand(target.sessionId,
                "SetSubtitleStreamIndex", "Index", index));
    }

    @Override public void onCycleSession() {
        if (sessions.size() < 2) {
            Toast.makeText(this, sessions.isEmpty() ? "No controllable sessions yet." : "Only one session is connected.", Toast.LENGTH_SHORT).show();
            return;
        }
        int current = -1;
        for (int i = 0; i < sessions.size(); i++) {
            if (sessions.get(i).sessionId.equals(selectedSessionId)) current = i;
        }
        RemoteState next = sessions.get((current + 1) % sessions.size());
        selectedSessionId = next.sessionId;
        selectedState = next;
        remoteView.setState(next, sessions);
        refreshArtwork(next, generation);
        refreshChapters(next, generation);
        Toast.makeText(this, "Controlling " + next.deviceName, Toast.LENGTH_SHORT).show();
    }

    @Override public void onLogout() {
        clearAuth();
        showLogin(null);
    }

    private void clearAuth() {
        prefs.edit().remove(TOKEN).remove(USER_ID).apply();
    }

    private void runCommand(ThrowingAction action) {
        final int requestGeneration = generation;
        network.execute(() -> {
            try {
                action.run();
                main.post(() -> {
                    if (generation == requestGeneration) {
                        main.removeCallbacks(pollTask);
                        main.postDelayed(pollTask, 180);
                    }
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (generation == requestGeneration) {
                        Toast.makeText(this, friendlyError(failure), Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private EditText field(String description, int inputType) {
        EditText field = new EditText(this);
        field.setContentDescription(description);
        field.setTextSize(14);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(0x7FFFFFFF);
        field.setInputType(inputType);
        field.setPadding(dp(16), 0, dp(16), 0);
        field.setBackground(solid(0x1AFFFFFF, 17));
        return field;
    }

    private void addField(LinearLayout parent, EditText field, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        params.topMargin = dp(topMargin);
        parent.addView(field, params);
    }

    private TextView label(String value, int sp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans", style));
        view.setIncludeFontPadding(false);
        return view;
    }

    private GradientDrawable solid(int color, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
    }

    private GradientDrawable glass(int tint, float strength, int radiusDp) {
        int mixed = ThemeColors.blend(Color.rgb(16, 27, 36), tint, strength);
        GradientDrawable shape = solid(Color.argb(205, Color.red(mixed), Color.green(mixed), Color.blue(mixed)), radiusDp);
        shape.setStroke(dp(1), 0x38FFFFFF);
        return shape;
    }

    private void hideKeyboard(View view) {
        InputMethodManager input = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (input != null) input.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    private void applyBottomInset(View view) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            view.setOnApplyWindowInsetsListener((target, insets) -> {
                int bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
                target.setPadding(target.getPaddingLeft(), target.getPaddingTop(), target.getPaddingRight(), bottom);
                return insets;
            });
        }
    }

    private String friendlyError(Exception failure) {
        String message = failure.getMessage();
        if (message == null || message.trim().isEmpty()) return "Unable to reach Jellyfin.";
        if (message.contains("CLEARTEXT")) return "Android blocked this HTTP address. Use a local server address or HTTPS.";
        if (message.contains("Failed to connect") || message.contains("timed out") || message.contains("Unable to resolve")) {
            return "Can't reach that Jellyfin server. Check the address and Wi-Fi connection.";
        }
        return message;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }

    @Override protected void onDestroy() {
        generation++;
        main.removeCallbacksAndMessages(null);
        network.shutdownNow();
        super.onDestroy();
    }

    private interface ThrowingAction { void run() throws Exception; }

    private static final class LoginAtmosphere extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Shader background;
        private Shader firstGlow;
        private Shader secondGlow;

        LoginAtmosphere(Context context) { super(context); }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            background = new LinearGradient(0, 0, width, height,
                    new int[]{0xFF06121B, 0xFF11132A, 0xFF07131A}, null, Shader.TileMode.CLAMP);
            firstGlow = new RadialGradient(width * .06f, height * .18f, width * .78f,
                    0xA043C5D6, Color.TRANSPARENT, Shader.TileMode.CLAMP);
            secondGlow = new RadialGradient(width, height * .7f, width * .92f,
                    0x807D55C7, Color.TRANSPARENT, Shader.TileMode.CLAMP);
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            paint.setShader(background);
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(firstGlow);
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(secondGlow);
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
        }
    }
}
