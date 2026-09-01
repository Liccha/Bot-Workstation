package com.mybot;

import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises Stable SID lookup through the real NapCat JSON send boundary. */
public final class StableQuerySendRegressionTest {
    private StableQuerySendRegressionTest() {}

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<JSONObject> payload = new AtomicReference<>();
        server.createContext("/send_group_msg", exchange -> {
            payload.set(new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] response = "{\"status\":\"ok\",\"retcode\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        System.setProperty("napcat.api.url", "http://127.0.0.1:" + server.getAddress().getPort());
        Path fixtureRoot = Files.createTempDirectory("stable-query-send-");
        Path coverDirectory = Files.createDirectories(fixtureRoot.resolve("stable_cover"));
        Path expectedCover = coverDirectory.resolve("12518.webp");
        Files.write(expectedCover, new byte[]{1, 2, 3});
        Path database = fixtureRoot.resolve("stable.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE stable_info (sid TEXT PRIMARY KEY,title TEXT,artist TEXT,bpm TEXT,length TEXT,creator TEXT,update_time TEXT,cover TEXT)");
            String brokenSpreadsheetCover = "\"" + coverDirectory + "\\\"&A2&\".webp\"";
            try (var insert = connection.prepareStatement("INSERT INTO stable_info VALUES (?,?,?,?,?,?,?,?)")) {
                insert.setString(1, "12518");
                insert.setString(2, "Crazy Jackpot");
                insert.setString(3, "Hommarju");
                insert.setString(4, "170");
                insert.setString(5, "114");
                insert.setString(6, "AnChenOwO");
                insert.setString(7, "2025/3/18 21:01");
                insert.setString(8, brokenSpreadsheetCover);
                insert.executeUpdate();
            }
            Stable stable = new Stable(connection, new NapCatClient());
            stable.handleGroupMessage(2000000006L, 42L, "！s12518");
            JSONObject sent = payload.get();
            require(sent != null, "Stable lookup sent no NapCat message");
            require(sent.optLong("group_id") == 2000000006L, "Stable response targeted the wrong group");
            String message = sent.optString("message", "");
            require(message.contains("Crazy Jackpot") && message.contains("sid:12518"),
                "Stable response lost song data");
            require(message.endsWith("[CQ:image,file=base64://AQID]"),
                "Stable response must place its self-contained cover after all text");
        } finally {
            server.stop(0);
            System.clearProperty("napcat.api.url");
            Files.deleteIfExists(database);
            Files.deleteIfExists(expectedCover);
            Files.deleteIfExists(coverDirectory);
            Files.deleteIfExists(fixtureRoot);
        }
        System.out.println("STABLE_QUERY_SEND_GREEN");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
