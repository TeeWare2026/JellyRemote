package com.signalbox.jellyremote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public final class JellyfinLibraryTest {
    @Test public void searchingOnlyReadsLibraryAndEncodesUserText() throws Exception {
        try (FakeServer server = new FakeServer()) {
            server.reply(200, "{\"Items\":[{\"Id\":\"film\",\"Name\":\"A film\",\"Type\":\"Movie\"}],\"TotalRecordCount\":1}");
            LibraryQuery query = new LibraryQuery(LibraryQuery.Kind.SEARCH, "Search", "", "", "a & b/é?", 0);
            LibraryPage page = server.client().getLibraryPage(query);
            assertEquals(1, page.items.size());
            assertFalse(page.hasNext());
            assertTrue(server.requests.get(0).startsWith("GET /jellyfin/Items?"));
            assertTrue(server.requests.get(0).contains("searchTerm=a%20%26%20b%2F%C3%A9%3F"));
            assertTrue(server.requests.get(0).contains("userId=user"));
        }
    }

    @Test public void movieResumeTargetsExactlyTheSelectedSessionAndPosition() throws Exception {
        try (FakeServer server = new FakeServer()) {
            server.reply(200, sessions("receiver"));
            server.reply(204, "");
            LibraryItem item = LibraryItem.fromJson(new JSONObject("{\"Id\":\"film\",\"Type\":\"Movie\","
                    + "\"RunTimeTicks\":1000000000,\"UserData\":{\"PlaybackPositionTicks\":123000000}}"));
            server.client().playItem("receiver", item, true);
            assertEquals(2, server.requests.size());
            assertEquals("POST /jellyfin/Sessions/receiver/Playing?playCommand=PlayNow&itemIds=film&startPositionTicks=123000000 HTTP/1.1",
                    server.requests.get(1));
        }
    }

    @Test public void playFromBeginningIgnoresSavedResumePosition() throws Exception {
        try (FakeServer server = new FakeServer()) {
            server.reply(200, sessions("receiver"));
            server.reply(204, "");
            LibraryItem item = LibraryItem.fromJson(new JSONObject("{\"Id\":\"film\",\"Type\":\"Movie\","
                    + "\"UserData\":{\"PlaybackPositionTicks\":123000000}}"));
            server.client().playItem("receiver", item, false);
            assertTrue(server.requests.get(1).contains("startPositionTicks=0"));
        }
    }

    @Test public void selectingAnEpisodeQueuesAvailableFollowingEpisodesInOrder() throws Exception {
        try (FakeServer server = new FakeServer()) {
            server.reply(200, sessions("receiver"));
            server.reply(200, "{\"Items\":[{\"Id\":\"e2\",\"Type\":\"Episode\"},"
                    + "{\"Id\":\"missing\",\"Type\":\"Episode\",\"IsMissing\":true},"
                    + "{\"Id\":\"e3\",\"Type\":\"Episode\"}]}");
            server.reply(204, "");
            LibraryItem item = LibraryItem.fromJson(new JSONObject("{\"Id\":\"e2\",\"Type\":\"Episode\",\"SeriesId\":\"series\"}"));
            server.client().playItem("receiver", item, false);
            assertTrue(server.requests.get(1).contains("/Shows/series/Episodes?"));
            assertTrue(server.requests.get(1).contains("startItemId=e2"));
            assertTrue(server.requests.get(2).contains("itemIds=e2%2Ce3"));
        }
    }

    @Test public void disconnectedReceiverNeverReceivesAPlaybackPost() throws Exception {
        try (FakeServer server = new FakeServer()) {
            server.reply(200, sessions("other-device"));
            LibraryItem item = LibraryItem.fromJson(new JSONObject("{\"Id\":\"film\",\"Type\":\"Movie\"}"));
            try {
                server.client().playItem("receiver", item, false);
                fail("A disconnected target must fail before sending playback.");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("disconnected"));
            }
            assertEquals(1, server.requests.size());
            assertTrue(server.requests.get(0).startsWith("GET "));
        }
    }

    @Test public void unavailableItemsNeverReachTheNetwork() throws Exception {
        try (FakeServer server = new FakeServer()) {
            LibraryItem missing = LibraryItem.fromJson(new JSONObject("{\"Id\":\"e1\",\"Type\":\"Episode\",\"LocationType\":\"Virtual\"}"));
            try {
                server.client().playItem("receiver", missing, false);
                fail("Virtual items must not play.");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("not available"));
            }
            assertTrue(server.requests.isEmpty());
        }
    }

    @Test public void seasonsArePagedLocallyWhenServerIgnoresOffset() throws Exception {
        try (FakeServer server = new FakeServer()) {
            org.json.JSONArray items = new org.json.JSONArray();
            for (int i = 0; i < 50; i++) items.put(new JSONObject().put("Id", "season-" + i).put("Type", "Season"));
            server.reply(200, new JSONObject().put("Items", items).toString());
            LibraryQuery query = new LibraryQuery(LibraryQuery.Kind.SEASONS, "Seasons", "series", "series", "", 48);
            LibraryPage page = server.client().getLibraryPage(query);
            assertEquals(2, page.items.size());
            assertEquals("season-48", page.items.get(0).id);
            assertEquals(50, page.total);
            assertFalse(page.hasNext());
        }
    }

    @Test public void libraryFailuresPreserveHttpStatusForRetryAndSignInHandling() throws Exception {
        try (FakeServer server = new FakeServer()) {
            server.reply(401, "");
            try {
                server.client().getLibraryPage(LibraryQuery.movies());
                fail("Expired authorization must be reported.");
            } catch (JellyfinClient.HttpError expected) {
                assertEquals(401, expected.status);
            }
        }
    }

    private static String sessions(String id) {
        return "[{\"Id\":\"" + id + "\",\"SupportsRemoteControl\":true}]";
    }

    /** An isolated HTTP endpoint: these playback tests cannot reach a user's server. */
    private static final class FakeServer implements AutoCloseable {
        final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        private final BlockingQueue<String> responses = new LinkedBlockingQueue<>();
        private final ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        private final Thread thread;

        FakeServer() throws IOException {
            thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket connection = server.accept()) {
                        connection.setSoTimeout(3000);
                        BufferedReader input = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                        String first = input.readLine();
                        if (first == null) continue;
                        requests.add(first);
                        String line;
                        while ((line = input.readLine()) != null && !line.isEmpty()) { }
                        String response = responses.poll(3, java.util.concurrent.TimeUnit.SECONDS);
                        if (response == null) response = "HTTP/1.1 500 Error\r\nContent-Length: 0\r\n\r\n";
                        connection.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                        connection.getOutputStream().flush();
                    } catch (IOException error) {
                        if (!server.isClosed()) throw new RuntimeException(error);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "jellyremote-fake-server");
            thread.setDaemon(true);
            thread.start();
        }

        void reply(int status, String body) {
            responses.add("HTTP/1.1 " + status + " Response\r\nContent-Type: application/json\r\nContent-Length: "
                    + body.getBytes(StandardCharsets.UTF_8).length + "\r\nConnection: close\r\n\r\n" + body);
        }

        JellyfinClient client() {
            return new JellyfinClient("http://127.0.0.1:" + server.getLocalPort() + "/jellyfin", "test-device", "test-token", "user");
        }

        @Override public void close() throws Exception {
            server.close();
            thread.interrupt();
            thread.join(1000);
        }
    }
}
