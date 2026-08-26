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
        Path database = Files.createTempFile("stable-query-send-", ".db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE stable_info (sid TEXT PRIMARY KEY,title TEXT,artist TEXT,bpm TEXT,length TEXT,creator TEXT,update_time TEXT,cover TEXT)");
            statement.execute("INSERT INTO stable_info VALUES ('12518','Crazy Jackpot','Hommarju','170','114','AnChenOwO','2025/3/18 21:01','')");
            Stable stable = new Stable(connection, new NapCatClient());
            stable.handleGroupMessage(2000000006L, 42L, "！s12518");
            JSONObject sent = payload.get();
            require(sent != null, "Stable lookup sent no NapCat message");
            require(sent.optLong("group_id") == 2000000006L, "Stable response targeted the wrong group");
            String message = sent.optString("message", "");
            require(message.contains("Crazy Jackpot") && message.contains("sid:12518"),
                "Stable response lost song data");
        } finally {
            server.stop(0);
            System.clearProperty("napcat.api.url");
            Files.deleteIfExists(database);
        }
        System.out.println("STABLE_QUERY_SEND_GREEN");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
