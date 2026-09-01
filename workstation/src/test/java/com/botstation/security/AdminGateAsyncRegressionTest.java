package com.botstation.security;

import com.botstation.core.BotPaths;
import com.botstation.core.LogBus;
import com.botstation.core.TaskRunner;
import com.sun.net.httpserver.HttpServer;

import javax.swing.SwingUtilities;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** A slow cloud trust endpoint must never freeze Swing while opening the admin page. */
public final class AdminGateAsyncRegressionTest {
    private AdminGateAsyncRegressionTest() {}

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path root = Files.createTempDirectory("botstation-admin-async-");
        Path app = root.resolve("installed-app");
        Path songBot = app.resolve("components").resolve("SongBot");
        Files.createDirectories(songBot.resolve("data"));

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/admin", exchange -> {
            try { Thread.sleep(700); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            byte[] body = "{\"trusted\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        Files.writeString(songBot.resolve("data").resolve("cloud-announcement.properties"),
            "backend=cloud\napi=http://127.0.0.1:" + server.getAddress().getPort()
                + "/admin\ndesktopToken=test-token\n", StandardCharsets.UTF_8);

        System.setProperty("botstation.home", app.toString());
        TaskRunner tasks = new TaskRunner();
        try {
            AdminGate gate = new AdminGate(BotPaths.detect(), new LogBus(root.resolve("logs")));
            CountDownLatch finished = new CountDownLatch(1);
            CountDownLatch heartbeat = new CountDownLatch(1);
            AtomicBoolean authorized = new AtomicBoolean();
            AtomicLong callMillis = new AtomicLong();
            SwingUtilities.invokeAndWait(() -> {
                long started = System.nanoTime();
                gate.authorizeAsync(null, tasks, value -> {
                    authorized.set(value);
                    finished.countDown();
                });
                callMillis.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                SwingUtilities.invokeLater(heartbeat::countDown);
            });
            require(callMillis.get() < 150, "admin open still blocks the EDT for " + callMillis.get() + " ms");
            require(heartbeat.await(250, TimeUnit.MILLISECONDS), "Swing heartbeat was blocked by cloud authentication");
            require(finished.await(3, TimeUnit.SECONDS), "asynchronous authentication never completed");
            require(authorized.get() && gate.isAuthorized(), "trusted cloud response did not authorize the session");
            System.out.println("ADMIN_GATE_ASYNC_GREEN");
        } finally {
            tasks.close();
            server.stop(0);
            System.clearProperty("botstation.home");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
