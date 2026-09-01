package com.mcz;

import com.sun.net.httpserver.HttpServer;

import javax.swing.SwingUtilities;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Cloud announcement loading may be slow, but constructing/opening the page must be immediate. */
public final class AnnouncementEditorAsyncRegressionTest {
    private AnnouncementEditorAsyncRegressionTest() {}

    public static void main(String[] args) throws Exception {
        Path songBot = Files.createTempDirectory("announcement-editor-async-");
        Files.createDirectories(songBot.resolve("data"));
        CountDownLatch requested = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/announcements", exchange -> {
            requested.countDown();
            try { Thread.sleep(700); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        Files.writeString(songBot.resolve("data").resolve("cloud-announcement.properties"),
            "backend=cloud\napi=http://127.0.0.1:" + server.getAddress().getPort()
                + "/announcements\ndesktopToken=test-token\n", StandardCharsets.UTF_8);
        try {
            AtomicLong elapsed = new AtomicLong();
            CountDownLatch heartbeat = new CountDownLatch(1);
            SwingUtilities.invokeAndWait(() -> {
                long started = System.nanoTime();
                new AnnouncementEditor(() -> { }, songBot.toFile());
                elapsed.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                SwingUtilities.invokeLater(heartbeat::countDown);
            });
            require(elapsed.get() < 200, "announcement page constructor blocked for " + elapsed.get() + " ms");
            require(heartbeat.await(250, TimeUnit.MILLISECONDS), "Swing heartbeat was blocked by announcement loading");
            require(requested.await(2, TimeUnit.SECONDS), "cloud load was not started in the background");
            System.out.println("ANNOUNCEMENT_EDITOR_ASYNC_GREEN");
        } finally {
            server.stop(0);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
