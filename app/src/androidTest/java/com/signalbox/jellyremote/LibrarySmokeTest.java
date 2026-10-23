package com.signalbox.jellyremote;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Device UI checks use an in-process catalog and a recording callback, never real playback. */
public final class LibrarySmokeTest extends Instrumentation {
    private LibraryDialog dialog;
    private MainActivity activity;
    private final AtomicInteger plays = new AtomicInteger();
    private volatile String failure;
    private boolean promotion;
    private boolean chapterChecks;
    private boolean chapterLive;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        promotion = arguments != null && "true".equals(arguments.getString("promotion"));
        chapterChecks = arguments != null && "true".equals(arguments.getString("chapters"));
        chapterLive = arguments != null && "true".equals(arguments.getString("chapterLive"));
        start();
    }

    @Override public void onStart() {
        Bundle results = new Bundle();
        if (chapterLive) {
            readOnlyChapterDiagnostic(results);
            return;
        }
        try (Catalog catalog = new Catalog(promotion)) {
            Intent launch = new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = (MainActivity) startActivitySync(launch);
            if (chapterChecks) {
                runOnMainSync(this::verifyChapterControls);
                results.putString("stream", "PASS: chapter visibility, late metadata, picker selection, item changes, stale selection, and stopped playback. No live playback commands.\n");
                finish(Activity.RESULT_OK, results);
                return;
            }
            runOnMainSync(this::verifyIdleRemote);
            runOnMainSync(() -> {
                dialog = new LibraryDialog(activity, new JellyfinClient(catalog.url(), "test", "test", "user"),
                        promotion ? "Living room" : "Test screen", ThemeColors.defaults(), (item, resume, completion) -> {
                            if (!"episode".equals(item.id) || !resume) failure = "Wrong episode or resume position";
                            plays.incrementAndGet();
                            completion.done("Simulated offline receiver. Try again.");
                        });
                dialog.show();
            });
            if (promotion) {
                capturePromotion();
                if (plays.get() != 0 || catalog.posts.get() != 0) throw new AssertionError("Demo changed playback");
                results.putString("stream", "PASS: captured demo screens using fictional titles and a local catalog; no playback commands.\n");
                finish(Activity.RESULT_OK, results);
                return;
            }
            awaitText("The Night Garden");
            awaitImage();
            screenshot("library-movies.png");
            click("Next  ›");
            awaitText("Page 2");
            awaitText("The Night Garden: Part II");
            click("Series");
            awaitText("The Little Astronaut");
            selectFirst();
            awaitText("Season 1");
            selectFirst();
            awaitText("First Light");
            selectFirst();
            awaitText("▶  Resume from 2:00");
            awaitImage();
            screenshot("library-episode.png");
            if (plays.get() != 0 || catalog.posts.get() != 0) throw new AssertionError("Browsing changed playback");
            click("▶  Resume from 2:00");
            awaitText("Simulated offline receiver. Try again.");
            if (plays.get() != 1 || failure != null) throw new AssertionError(failure);
            // Error recovery re-enables the action, while the catalog still receives no POST.
            runOnMainSync(() -> {
                View retry = findText(dialog.getWindow().getDecorView(), "▶  Resume from 2:00");
                if (retry == null || !retry.isEnabled()) failure = "Play action did not recover";
            });
            if (failure != null) throw new AssertionError(failure);
            click("‹");
            awaitText("First Light");
            runOnMainSync(() -> {
                EditText search = findType(dialog.getWindow().getDecorView(), EditText.class);
                search.setText("no match & unicode é");
            });
            awaitText("No titles match your search.");
            if (catalog.posts.get() != 0) throw new AssertionError("Read-only catalog received a POST");
            results.putString("stream", "PASS: idle-session browse entry, pagination, series/seasons/episodes, detail/resume, playback-error recovery, back, search, artwork, and read-only browsing.\n");
            results.putString("screenshots", new File(activity.getExternalFilesDir(null), "ui-checks").getAbsolutePath());
            finish(Activity.RESULT_OK, results);
        } catch (Throwable error) {
            results.putString("stream", "FAIL: " + error + "\n");
            finish(Activity.RESULT_CANCELED, results);
        } finally {
            if (dialog != null) runOnMainSync(() -> dialog.dismiss());
        }
    }

    private void readOnlyChapterDiagnostic(Bundle results) {
        try {
            android.content.SharedPreferences prefs = getTargetContext().getSharedPreferences("jelly_remote", android.content.Context.MODE_PRIVATE);
            JellyfinClient client = new JellyfinClient(prefs.getString("server", ""), prefs.getString("device_id", ""),
                    prefs.getString("token", ""), prefs.getString("user_id", ""));
            java.util.List<RemoteState> sessions = client.getSessions();
            StringBuilder report = new StringBuilder("GET-only diagnostic: sessions=").append(sessions.size());
            int playing = 0;
            for (RemoteState session : sessions) {
                if (!session.hasMedia || !session.supportsControl) continue;
                playing++;
                report.append("; playing_session=").append(playing)
                        .append(", embedded_chapters=").append(session.chapters.size())
                        .append(", fetched_chapters=").append(client.getChapters(session.itemId).size());
            }
            report.append("; no playback commands sent.\n");
            results.putString("stream", report.toString());
            finish(Activity.RESULT_OK, results);
        } catch (Exception error) {
            results.putString("stream", "GET-only diagnostic unavailable: " + error.getClass().getSimpleName() + ". No playback commands sent.\n");
            finish(Activity.RESULT_CANCELED, results);
        }
    }

    private void verifyChapterControls() {
        java.util.concurrent.atomic.AtomicLong seek = new java.util.concurrent.atomic.AtomicLong(-1L);
        AtomicInteger seekCount = new AtomicInteger();
        RemoteView remote = new RemoteView(activity, new RemoteView.Listener() {
            @Override public void onPlayState(String command) { throw new AssertionError("Unexpected control"); }
            @Override public void onSeek(long ticks) { seek.set(ticks); seekCount.incrementAndGet(); }
            @Override public void onVolume(int volume) { throw new AssertionError("Unexpected volume"); }
            @Override public void onMute() { throw new AssertionError("Unexpected mute"); }
            @Override public void onSubtitleToggle() { throw new AssertionError("Unexpected subtitles"); }
            @Override public void onSkipSegment(MediaSegment segment) { throw new AssertionError("Unexpected skip"); }
            @Override public void onCycleSession() { throw new AssertionError("Unexpected session change"); }
            @Override public void onBrowse() { throw new AssertionError("Unexpected browsing"); }
            @Override public void onLogout() { throw new AssertionError("Unexpected sign-out"); }
        });
        try {
            java.util.List<Chapter> chapters = java.util.List.of(new Chapter("Opening", 0L), new Chapter("Main story", 1200000000L));
            JSONObject item = new JSONObject().put("Id", "chapter-film").put("Name", "Sample film").put("RunTimeTicks", 6000000000L);
            JSONObject session = new JSONObject().put("Id", "chapter-screen").put("SupportsRemoteControl", true).put("NowPlayingItem", item);
            RemoteState state = RemoteState.fromJson(session);
            remote.setChapters(chapters);
            remote.setState(state, java.util.List.of(state));
            View button = findText(remote, "▤   CHAPTERS  ·  2");
            if (button == null || button.getVisibility() != View.VISIBLE || !button.isEnabled()) {
                throw new AssertionError("Preloaded chapters remain hidden when playback arrives");
            }
            remote.setChapters(java.util.Collections.emptyList());
            if (button.getVisibility() != View.GONE) throw new AssertionError("Missing metadata remains visible");
            remote.setChapters(chapters);
            remote.setState(state, java.util.List.of(state));
            if (button.getVisibility() != View.VISIBLE) throw new AssertionError("Late metadata did not reveal chapters");

            java.lang.reflect.Field pickerField = RemoteView.class.getDeclaredField("chapterDialog");
            pickerField.setAccessible(true);
            button.performClick();
            android.app.Dialog picker = (android.app.Dialog) pickerField.get(remote);
            if (picker == null || !picker.isShowing()) throw new AssertionError("Chapter picker did not open");
            View chapterName = findText(picker.getWindow().getDecorView(), "Main story");
            if (chapterName == null) throw new AssertionError("Chapter is missing from picker");
            ((View) chapterName.getParent()).performClick();
            if (seek.get() != 1200000000L || seekCount.get() != 1 || picker.isShowing()) {
                throw new AssertionError("Chapter selection did not seek to the recorded timestamp");
            }

            button.performClick();
            picker = (android.app.Dialog) pickerField.get(remote);
            View staleName = findText(picker.getWindow().getDecorView(), "Main story");
            item.put("Id", "another-film");
            RemoteState next = RemoteState.fromJson(session);
            remote.setState(next, java.util.List.of(next));
            if (picker.isShowing() || button.getVisibility() != View.GONE) throw new AssertionError("Old chapters survived title change");
            ((View) staleName.getParent()).performClick();
            if (seekCount.get() != 1) throw new AssertionError("Stale chapter sent a seek");

            remote.setChapters(chapters);
            remote.setState(null, java.util.Collections.emptyList());
            if (button.getVisibility() != View.GONE) throw new AssertionError("Chapter control survived playback stop");
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private void capturePromotion() throws Exception {
        awaitText("The Night Garden");
        settleAndCapture("library-movies.png");
        click("Series");
        awaitText("The Little Astronaut");
        settleAndCapture("library-series.png");
        selectFirst();
        awaitText("Season 1");
        settleAndCapture("library-seasons.png");
        selectFirst();
        awaitText("First Light");
        settleAndCapture("library-episodes.png");
        selectFirst();
        awaitText("▶  Resume from 2:00");
        settleAndCapture("library-episode.png");
    }

    private void settleAndCapture(String name) throws Exception {
        awaitImage();
        Thread.sleep(1800);
        waitForIdleSync();
        screenshot(name);
    }

    private void click(String label) {
        runOnMainSync(() -> {
            View view = findText(dialog.getWindow().getDecorView(), label);
            if (view == null || !view.isEnabled()) throw new AssertionError("Missing or disabled: " + label);
            view.performClick();
        });
    }

    private void selectFirst() {
        runOnMainSync(() -> {
            GridView grid = findType(dialog.getWindow().getDecorView(), GridView.class);
            if (grid == null || grid.getCount() == 0) throw new AssertionError("No catalog grid");
            grid.performItemClick(grid.getChildAt(0), 0, grid.getAdapter().getItemId(0));
        });
    }

    private void awaitText(String label) throws Exception {
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            java.util.concurrent.atomic.AtomicBoolean found = new java.util.concurrent.atomic.AtomicBoolean();
            runOnMainSync(() -> found.set(findText(dialog.getWindow().getDecorView(), label) != null));
            if (found.get()) { waitForIdleSync(); return; }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for " + label);
    }

    private void awaitImage() throws Exception {
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            java.util.concurrent.atomic.AtomicBoolean loaded = new java.util.concurrent.atomic.AtomicBoolean();
            runOnMainSync(() -> {
                android.widget.ImageView image = findType(dialog.getWindow().getDecorView(), android.widget.ImageView.class);
                loaded.set(image != null && image.getDrawable() != null);
            });
            if (loaded.get()) { waitForIdleSync(); return; }
            Thread.sleep(100);
        }
        throw new AssertionError("Artwork did not load");
    }

    private void screenshot(String name) {
        runOnMainSync(() -> {
            View view = dialog.getWindow().getDecorView();
            saveView(view, name);
        });
    }

    private void verifyIdleRemote() {
        AtomicInteger browseClicks = new AtomicInteger();
        AtomicInteger controlClicks = new AtomicInteger();
        RemoteView remote = new RemoteView(activity, new RemoteView.Listener() {
            @Override public void onPlayState(String command) { controlClicks.incrementAndGet(); }
            @Override public void onSeek(long ticks) { controlClicks.incrementAndGet(); }
            @Override public void onVolume(int volume) { controlClicks.incrementAndGet(); }
            @Override public void onMute() { controlClicks.incrementAndGet(); }
            @Override public void onSubtitleToggle() { controlClicks.incrementAndGet(); }
            @Override public void onSkipSegment(MediaSegment segment) { controlClicks.incrementAndGet(); }
            @Override public void onCycleSession() { controlClicks.incrementAndGet(); }
            @Override public void onBrowse() { browseClicks.incrementAndGet(); }
            @Override public void onLogout() { controlClicks.incrementAndGet(); }
        });
        try {
            JSONObject session = new JSONObject().put("Id", "preview").put("DeviceName", "Living room")
                    .put("Client", "Jellyfin Web").put("UserName", "Jellyfin user").put("SupportsRemoteControl", true);
            RemoteState idle = RemoteState.fromJson(session);
            remote.setState(idle, java.util.List.of(idle));
            View browse = findText(remote, "Browse library   ›");
            if (browse == null || !browse.isEnabled()) throw new AssertionError("Idle receiver cannot browse");
            browse.performClick();
            if (browseClicks.get() != 1 || controlClicks.get() != 0) throw new AssertionError("Browse sent a control command");
            session.put("NowPlayingItem", new JSONObject().put("Id", "preview-film").put("Name", "The Night Garden")
                    .put("Type", "Movie").put("RunTimeTicks", 63000000000L));
            session.put("PlayState", new JSONObject().put("IsPaused", true).put("PositionTicks", 12000000000L).put("VolumeLevel", 65));
            RemoteState playing = RemoteState.fromJson(session);
            remote.setState(playing, java.util.List.of(playing));
            remote.setConnectionStatus(true, "live");
            Bitmap art = DemoArtwork.create("ambient-film0", 720, 1080);
            remote.setArtwork(art, ThemeColors.from(art));
            android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            remote.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY));
            remote.layout(0, 0, metrics.widthPixels, metrics.heightPixels);
            saveView(remote, "remote.png");
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private void saveView(View view, String name) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        File directory = new File(activity.getExternalFilesDir(null), "ui-checks");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new AssertionError("Screenshot directory");
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        } catch (Exception error) { throw new AssertionError(error); }
        bitmap.recycle();
    }

    private static View findText(View view, String value) {
        if (view instanceof TextView && value.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view;
            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = findText(parent.getChildAt(i), value);
                if (child != null) return child;
            }
        }
        return null;
    }

    private static <T> T findType(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view;
            for (int i = 0; i < parent.getChildCount(); i++) {
                T child = findType(parent.getChildAt(i), type);
                if (child != null) return child;
            }
        }
        return null;
    }

    private static final class Catalog implements AutoCloseable {
        private final ServerSocket socket = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        private final AtomicInteger posts = new AtomicInteger();
        private final Thread worker;
        private final boolean promotion;

        Catalog(boolean promotion) throws Exception {
            this.promotion = promotion;
            worker = new Thread(() -> {
                while (!socket.isClosed()) {
                    try (Socket connection = socket.accept()) {
                        connection.setSoTimeout(2000);
                        BufferedReader input = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                        String request = input.readLine();
                        if (request == null) continue;
                        String header;
                        while ((header = input.readLine()) != null && !header.isEmpty()) { }
                        if (!request.startsWith("GET ")) posts.incrementAndGet();
                        byte[] body;
                        String mime;
                        if (request.contains("/Images/")) {
                            String itemId = request.split("/Items/", 2)[1].split("/", 2)[0];
                            Bitmap image = DemoArtwork.create(itemId, 600, 900);
                            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                            image.compress(Bitmap.CompressFormat.PNG, 100, output);
                            image.recycle();
                            body = output.toByteArray();
                            mime = "image/png";
                        } else {
                            body = response(request).getBytes(StandardCharsets.UTF_8);
                            mime = "application/json";
                        }
                        String headers = "HTTP/1.1 200 OK\r\nContent-Type: " + mime + "\r\nContent-Length: "
                                + body.length + "\r\nConnection: close\r\n\r\n";
                        connection.getOutputStream().write(headers.getBytes(StandardCharsets.UTF_8));
                        connection.getOutputStream().write(body);
                        connection.getOutputStream().flush();
                    } catch (Exception ignored) { }
                }
            }, "jellyremote-ui-catalog");
            worker.start();
        }

        String url() { return "http://127.0.0.1:" + socket.getLocalPort(); }

        private String response(String request) throws Exception {
            if (request.contains("searchTerm=")) return "{\"Items\":[],\"TotalRecordCount\":0}";
            JSONObject episode = new JSONObject().put("Id", "episode").put("Name", "First Light")
                    .put("Type", "Episode").put("SeriesId", "series").put("SeriesName", "The Little Astronaut")
                    .put("ParentIndexNumber", 1).put("IndexNumber", 1).put("RunTimeTicks", 14000000000L)
                    .put("Overview", "A quiet signal from the edge of the solar system begins an unexpected journey.")
                    .put("UserData", new JSONObject().put("PlaybackPositionTicks", 1200000000L));
            if (request.startsWith("GET /Items/episode?")) return episode.toString();
            JSONArray items = new JSONArray();
            int total = 1;
            if (request.contains("/Seasons?")) {
                items.put(new JSONObject().put("Id", "season").put("Type", "Season").put("Name", "Season 1").put("SeriesId", "series"));
            } else if (request.contains("/Episodes?")) {
                items.put(episode);
            } else if (request.contains("includeItemTypes=Series")) {
                items.put(new JSONObject().put("Id", "series").put("Type", "Series").put("Name", "The Little Astronaut"));
            } else {
                total = promotion ? 6 : 49;
                boolean second = request.contains("startIndex=48");
                String[] titles = {"The Night Garden", "Beyond the Blue", "Amber Coast", "Quiet Orbit", "After the Rain", "Northern Light"};
                for (int i = 0; i < (promotion ? 6 : second ? 1 : 48); i++) {
                    items.put(new JSONObject().put("Id", "film" + i).put("Type", "Movie")
                            .put("ProductionYear", 2025).put("RunTimeTicks", 63000000000L)
                            .put("Name", promotion ? titles[i] : second ? "The Night Garden: Part II" : i % 2 == 0 ? "The Night Garden" : "Beyond the Blue"));
                }
            }
            return new JSONObject().put("Items", items).put("TotalRecordCount", total).toString();
        }

        @Override public void close() throws Exception {
            socket.close();
            worker.join(1000);
        }
    }
}
