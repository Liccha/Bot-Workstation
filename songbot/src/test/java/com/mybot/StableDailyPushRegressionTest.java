package com.mybot;

import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the scheduled Stable recommendation through the real NapCat JSON boundary. */
public final class StableDailyPushRegressionTest {
    private StableDailyPushRegressionTest() { }

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
        Path fixtureRoot = Files.createTempDirectory("stable-daily-send-");
        Path coverDirectory = Files.createDirectories(fixtureRoot.resolve("stable_cover"));
        Path cover = coverDirectory.resolve("27271.webp");
        Files.write(cover, new byte[]{1, 2, 3});
        Path database = fixtureRoot.resolve("stable.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE stable_info (sid TEXT PRIMARY KEY,title TEXT,artist TEXT,bpm TEXT,length TEXT,creator TEXT,update_time TEXT,cover TEXT)");
            try (var insert = connection.prepareStatement("INSERT INTO stable_info VALUES (?,?,?,?,?,?,?,?)")) {
                insert.setString(1, "27271");
                insert.setString(2, "Continue");
                insert.setString(3, "tokiwa feat. 小鳥遊めぐみ");
                insert.setString(4, "145");
                insert.setString(5, "282");
                insert.setString(6, "Liccha");
                insert.setString(7, "2025/8/10 16:29");
                insert.setString(8, "\"" + coverDirectory + "\\\"&A38&\".webp\"");
                insert.executeUpdate();
            }
            Stable stable = new Stable(connection, new NapCatClient());
            Method daily = Stable.class.getDeclaredMethod("doDailyPush");
            daily.setAccessible(true);
            daily.invoke(stable);

            JSONObject sent = payload.get();
            require(sent != null, "scheduled Stable recommendation sent no NapCat message");
            String message = sent.optString("message", "");
            require(message.contains("【每日上架谱面推荐】") && message.contains("sid:27271"),
                "scheduled Stable recommendation lost song data");
            require(message.endsWith("[CQ:image,file=base64://AQID]"),
                "scheduled Stable recommendation must place its self-contained cover after all text");
        } finally {
            server.stop(0);
            System.clearProperty("napcat.api.url");
            Files.deleteIfExists(database);
            Files.deleteIfExists(cover);
            Files.deleteIfExists(coverDirectory);
            Files.deleteIfExists(fixtureRoot);
        }
        System.out.println("STABLE_DAILY_PUSH_GREEN");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
