package com.signalbox.jellyremote;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class JellyfinClient {
    static final String CLIENT_NAME = "JellyRemote";
    static final String VERSION = "1.2.0";

    final String serverUrl;
    private final String deviceId;
    private String token;
    private String userId;

    JellyfinClient(String serverUrl, String deviceId, String token, String userId) {
        this.serverUrl = normalizeServer(serverUrl);
        this.deviceId = deviceId;
        this.token = token == null ? "" : token;
        this.userId = userId == null ? "" : userId;
    }

    static String normalizeServer(String value) {
        String server = value == null ? "" : value.trim();
        if (!server.matches("(?i)^https?://.*")) server = "http://" + server;
        while (server.endsWith("/")) server = server.substring(0, server.length() - 1);
        try {
            URI uri = URI.create(server);
            if (uri.getHost() == null || (!"http".equalsIgnoreCase(uri.getScheme())
                    && !"https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Enter a valid Jellyfin address, such as http://server-address:8096");
        }
        return server;
    }

    AuthResult login(String username, String password) throws IOException, JSONException {
        JSONObject body = new JSONObject();
        body.put("Username", username);
        body.put("Pw", password);
        JSONObject result = requestJson("POST", "/Users/AuthenticateByName", body, false);
        String accessToken = result.optString("AccessToken", "");
        JSONObject user = result.optJSONObject("User");
        if (accessToken.isEmpty() || user == null) throw new IOException("The server returned an incomplete login response.");
        token = accessToken;
        userId = user.optString("Id", "");
        return new AuthResult(accessToken, userId, user.optString("Name", username));
    }

    List<RemoteState> getSessions() throws IOException, JSONException {
        String path = "/Sessions?controllableByUserId=" + encode(userId) + "&activeWithinSeconds=120";
        JSONArray data = requestArray("GET", path, null, true);
        List<RemoteState> sessions = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject value = data.optJSONObject(i);
            if (value != null && !value.optString("Id", "").isEmpty()) sessions.add(RemoteState.fromJson(value));
        }
        return sessions;
    }

    void playState(String sessionId, String command, Long seekTicks) throws IOException, JSONException {
        String path = "/Sessions/" + encode(sessionId) + "/Playing/" + encode(command)
                + "?controllingUserId=" + encode(userId);
        if (seekTicks != null) path += "&seekPositionTicks=" + seekTicks;
        requestEmpty("POST", path, null, true);
    }

    void generalCommand(String sessionId, String command, String argument, int value)
            throws IOException, JSONException {
        JSONObject arguments = new JSONObject();
        arguments.put(argument, Integer.toString(value));
        JSONObject body = new JSONObject();
        body.put("Name", command);
        body.put("ControllingUserId", userId);
        body.put("Arguments", arguments);
        requestEmpty("POST", "/Sessions/" + encode(sessionId) + "/Command", body, true);
    }

    void simpleCommand(String sessionId, String command) throws IOException, JSONException {
        requestEmpty("POST", "/Sessions/" + encode(sessionId) + "/Command/" + encode(command), null, true);
    }

    LibraryPage getLibraryPage(LibraryQuery query) throws IOException, JSONException {
        JSONObject response = requestJson("GET", query.path(userId), null, true);
        // GetSeasons does not support server paging. Slice locally so large series still paginate.
        if (query.kind == LibraryQuery.Kind.SEASONS) {
            JSONArray all = response.optJSONArray("Items");
            if (all != null) {
                JSONArray page = new JSONArray();
                for (int i = query.offset; i < Math.min(all.length(), query.offset + LibraryQuery.PAGE_SIZE); i++) {
                    page.put(all.get(i));
                }
                response.put("TotalRecordCount", all.length()).put("Items", page);
            }
        }
        return new LibraryPage(response, query.offset);
    }

    LibraryItem getLibraryItem(String itemId) throws IOException, JSONException {
        return LibraryItem.fromJson(getItemDetails(itemId));
    }

    void playItem(String sessionId, LibraryItem item, boolean resume) throws IOException, JSONException {
        if (item.id.isEmpty() || !item.playable) throw new IOException("This title is not available to play.");
        boolean connected = false;
        for (RemoteState session : getSessions()) {
            if (session.sessionId.equals(sessionId) && session.supportsControl) connected = true;
        }
        if (!connected) throw new IOException("That playback device disconnected. Return to the remote and select a connected device.");
        List<String> ids = new ArrayList<>();
        ids.add(item.id);
        if (item.isEpisode() && !item.seriesId.isEmpty()) {
            // Queue following episodes so Next episode continues from the chosen episode.
            try {
                JSONObject result = requestJson("GET", "/Shows/" + encode(item.seriesId)
                        + "/Episodes?userId=" + encode(userId) + "&isMissing=false&startItemId="
                        + encode(item.id) + "&limit=100", null, true);
                JSONArray episodes = result.optJSONArray("Items");
                JSONObject first = episodes == null ? null : episodes.optJSONObject(0);
                if (first != null && item.id.equals(first.optString("Id"))) {
                    for (int i = 1; i < episodes.length(); i++) {
                        JSONObject episode = episodes.optJSONObject(i);
                        if (episode == null) continue;
                        LibraryItem next = LibraryItem.fromJson(episode);
                        if (next.playable && !next.id.isEmpty() && !ids.contains(next.id)) ids.add(next.id);
                    }
                }
            } catch (HttpError error) {
                if (error.status != 404) throw error;
            }
        }
        String path = "/Sessions/" + encode(sessionId) + "/Playing?playCommand=PlayNow&itemIds="
                + encode(String.join(",", ids)) + "&startPositionTicks=" + (resume ? item.resumeTicks : 0L);
        requestEmpty("POST", path, null, true);
    }

    Bitmap getLibraryImage(LibraryItem item, boolean backdrop) throws IOException {
        String path = backdrop ? "/Items/" + encode(item.id) + "/Images/Backdrop/0?maxWidth=1000&quality=85"
                : "/Items/" + encode(item.imageId) + "/Images/Primary?maxWidth=360&quality=85&tag=" + encode(item.imageTag);
        HttpURLConnection connection = open(path, "GET", true);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("No artwork is available.");
            try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
                Bitmap bitmap = BitmapFactory.decodeStream(input);
                if (bitmap == null) throw new IOException("No artwork is available.");
                return bitmap;
            }
        } finally {
            connection.disconnect();
        }
    }

    int findFirstSubtitleIndex(String itemId) throws IOException, JSONException {
        JSONObject item = getItemDetails(itemId);
        JSONArray streams = item.optJSONArray("MediaStreams");
        if (streams == null) return -1;
        for (int i = 0; i < streams.length(); i++) {
            JSONObject stream = streams.optJSONObject(i);
            if (stream != null && "Subtitle".equalsIgnoreCase(stream.optString("Type"))) {
                return stream.optInt("Index", i);
            }
        }
        return -1;
    }

    List<Chapter> getChapters(String itemId) throws IOException, JSONException {
        return Chapter.fromItem(getItemDetails(itemId));
    }

    List<MediaSegment> getMediaSegments(String itemId) throws IOException, JSONException {
        HttpError jellyfinError = null;
        try {
            List<MediaSegment> segments = MediaSegment.fromJellyfin(
                    requestJson("GET", "/MediaSegments/" + encode(itemId), null, true));
            if (!segments.isEmpty()) return segments;
        } catch (HttpError error) {
            if (error.status == 401) throw error;
            jellyfinError = error;
        }

        // Intro Skipper versions for Jellyfin 10.8/10.9 expose their own endpoint.
        // Besides broadening compatibility, it supplies the server's exact prompt window.
        try {
            return MediaSegment.fromLegacy(requestJson("GET",
                    "/Episode/" + encode(itemId) + "/IntroSkipperSegments", null, true));
        } catch (HttpError error) {
            if (error.status == 401) throw error;
            if (jellyfinError != null && jellyfinError.status != 404 && jellyfinError.status != 400) {
                throw jellyfinError;
            }
            if (error.status != 404 && error.status != 400) throw error;
            return new ArrayList<>();
        }
    }

    private JSONObject getItemDetails(String itemId) throws IOException, JSONException {
        try {
            return requestJson("GET", "/Items/" + encode(itemId) + "?userId=" + encode(userId), null, true);
        } catch (HttpError error) {
            if (error.status != 404) throw error;
            // Jellyfin 10.x exposed this route beneath the user resource.
            return requestJson("GET", "/Users/" + encode(userId) + "/Items/" + encode(itemId), null, true);
        }
    }

    Bitmap getArtwork(RemoteState state) throws IOException {
        List<String> paths = new ArrayList<>();
        if (!state.backdropItemId.isEmpty()) {
            paths.add("/Items/" + encode(state.backdropItemId) + "/Images/Backdrop/0?maxWidth=1280&quality=86");
        }
        if (!state.itemId.isEmpty() && !state.itemId.equals(state.backdropItemId)) {
            paths.add("/Items/" + encode(state.itemId) + "/Images/Backdrop/0?maxWidth=1280&quality=86");
        }
        if (!state.itemId.isEmpty()) {
            paths.add("/Items/" + encode(state.itemId) + "/Images/Primary?maxWidth=1000&quality=86");
        }
        IOException finalError = null;
        for (String path : paths) {
            HttpURLConnection connection = null;
            try {
                connection = open(path, "GET", true);
                int status = connection.getResponseCode();
                if (status >= 200 && status < 300) {
                    try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
                        Bitmap bitmap = BitmapFactory.decodeStream(input);
                        if (bitmap != null) return bitmap;
                    }
                }
                finalError = new IOException("Artwork returned HTTP " + status);
            } catch (IOException error) {
                finalError = error;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        throw finalError == null ? new IOException("No artwork is available.") : finalError;
    }

    private JSONObject requestJson(String method, String path, JSONObject body, boolean authenticated)
            throws IOException, JSONException {
        String text = request(method, path, body, authenticated);
        return text.isEmpty() ? new JSONObject() : new JSONObject(text);
    }

    private JSONArray requestArray(String method, String path, JSONObject body, boolean authenticated)
            throws IOException, JSONException {
        String text = request(method, path, body, authenticated);
        return text.isEmpty() ? new JSONArray() : new JSONArray(text);
    }

    private void requestEmpty(String method, String path, JSONObject body, boolean authenticated)
            throws IOException, JSONException {
        request(method, path, body, authenticated);
    }

    private String request(String method, String path, JSONObject body, boolean authenticated) throws IOException {
        HttpURLConnection connection = open(path, method, authenticated);
        try {
            if (body != null) {
                connection.setDoOutput(true);
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String response = read(stream);
            if (status < 200 || status >= 300) {
                String message = response;
                try {
                    JSONObject error = new JSONObject(response);
                    message = error.optString("message", error.optString("Message", response));
                } catch (JSONException ignored) { }
                if (status == 401) message = "Sign-in expired or the credentials were rejected.";
                if (message == null || message.trim().isEmpty()) message = "Jellyfin returned HTTP " + status + ".";
                throw new HttpError(status, message);
            }
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection open(String path, String method, boolean authenticated) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(serverUrl + path).toURL().openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        String authorization = "MediaBrowser Client=\"" + CLIENT_NAME + "\", Device=\"Android\", "
                + "DeviceId=\"" + deviceId + "\", Version=\"" + VERSION + "\"";
        if (authenticated && !token.isEmpty()) {
            authorization += ", Token=\"" + token + "\"";
            connection.setRequestProperty("X-Emby-Token", token);
        }
        connection.setRequestProperty("Authorization", authorization);
        return connection;
    }

    private static String read(InputStream stream) throws IOException {
        if (stream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line).append('\n');
        }
        return result.toString().trim();
    }

    private static String encode(String value) {
        return LibraryQuery.encode(value);
    }

    static final class AuthResult {
        final String token;
        final String userId;
        final String userName;

        AuthResult(String token, String userId, String userName) {
            this.token = token;
            this.userId = userId;
            this.userName = userName;
        }
    }

    static final class HttpError extends IOException {
        final int status;
        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
