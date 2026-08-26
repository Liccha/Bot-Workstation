package com.botstation.mobile;

import com.botstation.core.BotPaths;
import com.botstation.core.LogBus;
import com.botstation.core.ProcessSupervisor;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Verifies pairing, headers, secret isolation and mobile responsive invariants. */
public final class MobileControlServerRegressionTest {
    private MobileControlServerRegressionTest() {}

    public static void main(String[] args) throws Exception {
        BotPaths paths = BotPaths.detect(); LogBus log = new LogBus(paths.logs());
        MobileControlServer server = new MobileControlServer(paths, log, new ProcessSupervisor(paths, log), ignored -> {});
        try {
            server.start();
            String baseUrl = server.localUrl();
            Response ping = request(baseUrl, "GET", "/api/ping", null, null);
            require(ping.code == 200 && new JSONObject(ping.body).optBoolean("ok"), "LAN ping failed");
            Response index = request(baseUrl, "GET", "/", null, null);
            require(index.code == 200 && index.body.contains("Bot 工作站"), "mobile index failed");
            require(index.csp != null && index.csp.contains("frame-ancestors 'none'"), "CSP missing");
            require(!index.body.contains("desktopToken") && !index.body.contains("AccessKey") && !index.body.contains("example-admin-secret"), "secret leaked to mobile HTML");

            Response unauthorized = request(baseUrl, "GET", "/api/status", null, null);
            require(unauthorized.code == 401, "status endpoint is not protected");
            String code = server.pairCode();
            String formattedCode = code.substring(0, 2) + " " + code.substring(2, 4) + " " + code.substring(4);
            Response paired = request(baseUrl, "POST", "/api/pair", new JSONObject().put("code", formattedCode).toString(), null);
            String token = new JSONObject(paired.body).getString("token");
            Response status = request(baseUrl, "GET", "/api/status", null, token);
            require(status.code == 200 && new JSONObject(status.body).has("songBot"), "paired status failed");
            Response songs = request(baseUrl, "GET", "/api/songs?q=&limit=2", null, token);
            require(songs.code == 200 && new JSONObject(songs.body).getJSONArray("items").length() <= 2, "song data endpoint failed");
            Response stable = request(baseUrl, "GET", "/api/stable?q=&limit=2", null, token);
            require(stable.code == 200 && new JSONObject(stable.body).getJSONArray("items").length() <= 2, "stable data endpoint failed");

            String css = resource("/mobile/app.css");
            String script = resource("/mobile/app.js");
            require(css.contains("overflow-x:clip") && css.contains("minmax(0,1fr)") && !css.contains("100vw"), "responsive CSS invariant failed");
            require(script.contains("/api/songs") && script.contains("/api/stable") && script.contains("textContent"), "mobile data UI missing");
            System.out.println("MOBILE_CONTROL_GREEN");
        } finally { server.close(); }
    }

    private static Response request(String baseUrl, String method, String path, String body, String token) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path.replaceFirst("^/", "")).openConnection();
        connection.setRequestMethod(method); connection.setConnectTimeout(2000); connection.setReadTimeout(5000);
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8); connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
        }
        int code = connection.getResponseCode(); InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String text = stream == null ? "" : read(stream); String csp = connection.getHeaderField("Content-Security-Policy"); connection.disconnect();
        return new Response(code, text, csp);
    }
    private static String resource(String path) throws Exception { try (InputStream input = MobileControlServerRegressionTest.class.getResourceAsStream(path)) { return read(input); } }
    private static String read(InputStream input) throws Exception { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n; while ((n = input.read(buffer)) >= 0) out.write(buffer, 0, n); return out.toString(StandardCharsets.UTF_8.name()); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static final class Response { final int code; final String body; final String csp; Response(int code, String body, String csp) { this.code = code; this.body = body; this.csp = csp; } }
}
