package com.botstation.mobile;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Starts the headless agent in a separate JVM and proves it polls with no Swing window. */
public final class BackgroundAgentProcessRegressionTest {
    private BackgroundAgentProcessRegressionTest() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("bot-background-agent-");
        Path workstation = root.resolve("workstation");
        Path songBot = root.resolve("SongBot");
        Files.createDirectories(workstation);
        Files.createDirectories(songBot.resolve("data"));
        AtomicInteger polls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/mobile-relay", exchange -> relay(exchange, polls));
        server.createContext("/api/mobile-data", BackgroundAgentProcessRegressionTest::emptyChanges);
        server.start();
        Properties properties = new Properties();
        properties.setProperty("api", "http://127.0.0.1:" + server.getAddress().getPort() + "/api/announcement-cloud");
        properties.setProperty("desktopToken", "agent-test-token");
        try (Writer writer = Files.newBufferedWriter(songBot.resolve("data").resolve("cloud-announcement.properties"), StandardCharsets.UTF_8)) {
            properties.store(writer, "test");
        }

        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        Process process = new ProcessBuilder(java,
            "-Dfile.encoding=UTF-8",
            "-Dbotstation.background.agent.lock=" + root.resolve("agent.lock"),
            "-Dbotstation.home=" + workstation,
            "-Dbotstation.songbot.home=" + songBot,
            "-cp", System.getProperty("java.class.path"),
            "com.botstation.BotStationApp", "--background-agent")
            .redirectErrorStream(true).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (polls.get() == 0 && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(100);
            require(process.isAlive(), "background agent exited instead of remaining resident");
            require(polls.get() > 0, "background agent did not poll cloud controls without the PC UI");
            System.out.println("BACKGROUND_AGENT_GREEN");
        } finally {
            process.destroy();
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
            server.stop(0);
        }
    }

    private static void relay(HttpExchange exchange, AtomicInteger polls) throws java.io.IOException {
        require("Desktop agent-test-token".equals(exchange.getRequestHeaders().getFirst("Authorization")),
            "agent relay request did not use the desktop credential");
        String query = exchange.getRequestURI().getRawQuery();
        JSONObject response;
        if (query != null && query.contains("action=desktop-poll")) {
            polls.incrementAndGet();
            response = new JSONObject().put("items", new JSONArray());
        } else {
            response = new JSONObject().put("ok", true);
        }
        respond(exchange, response);
    }

    private static void emptyChanges(HttpExchange exchange) throws java.io.IOException {
        require("Desktop agent-test-token".equals(exchange.getRequestHeaders().getFirst("Authorization")),
            "agent library request did not use the desktop credential");
        respond(exchange, new JSONObject().put("items", new JSONArray())
            .put("revision", 0).put("nextRevision", 0).put("hasMore", false));
    }

    private static void respond(HttpExchange exchange, JSONObject value) throws java.io.IOException {
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
