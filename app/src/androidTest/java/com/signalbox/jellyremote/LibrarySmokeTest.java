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

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle results = new Bundle();
        try (Catalog catalog = new Catalog()) {
            Intent launch = new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = (MainActivity) startActivitySync(launch);
            runOnMainSync(this::verifyIdleRemote);
            runOnMainSync(() -> {
                dialog = new LibraryDialog(activity, new JellyfinClient(catalog.url(), "test", "test", "user"),
                        "Test screen", ThemeColors.defaults(), (item, resume, completion) -> {
                            if (!"episode".equals(item.id) || !resume) failure = "Wrong episode or resume position";
                            plays.incrementAndGet();
                            completion.done("Simulated offline receiver. Try again.");
                        });
                dialog.show();
            });
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
            Bitmap art = Bitmap.createBitmap(720, 1080, Bitmap.Config.ARGB_8888);
            Canvas artCanvas = new Canvas(art);
            android.graphics.Paint paint = new android.graphics.Paint();
            paint.setShader(new android.graphics.LinearGradient(0, 0, 720, 1080,
                    new int[]{0xFF30485D, 0xFF6E548F, 0xFFC88B68}, null, android.graphics.Shader.TileMode.CLAMP));
            artCanvas.drawRect(0, 0, 720, 1080, paint);
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

        Catalog() throws Exception {
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
                            Bitmap image = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888);
                            Canvas canvas = new Canvas(image);
                            android.graphics.Paint paint = new android.graphics.Paint();
                            paint.setShader(new android.graphics.LinearGradient(0, 0, 400, 600,
                                    new int[]{0xFF293A68, 0xFF7669A8, 0xFFDAA77A}, null, android.graphics.Shader.TileMode.CLAMP));
                            canvas.drawRect(0, 0, 400, 600, paint);
                            paint.setShader(null);
                            paint.setColor(0xFFD8C9AE);
                            canvas.drawCircle(270, 190, 86, paint);
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
                total = 49;
                boolean second = request.contains("startIndex=48");
                for (int i = 0; i < (second ? 1 : 48); i++) {
                    items.put(new JSONObject().put("Id", "film" + i).put("Type", "Movie")
                            .put("ProductionYear", 2025).put("RunTimeTicks", 63000000000L)
                            .put("Name", second ? "The Night Garden: Part II" : i % 2 == 0 ? "The Night Garden" : "Beyond the Blue"));
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
