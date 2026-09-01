package com.botstation.security;

import com.botstation.core.LogBus;
import com.botstation.core.TaskRunner;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Component;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** A clean installation must unlock through cloud password verification without a local password file. */
public final class AdminGateCloudPasswordRegressionTest {
    private AdminGateCloudPasswordRegressionTest() {}

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        AtomicInteger passwordGrants = new AtomicInteger();
        AtomicReference<String> requestFailure = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/announcement-cloud", exchange -> {
            try { handle(exchange, passwordGrants); }
            catch (Throwable error) {
                requestFailure.set(error.getMessage());
                byte[] body = "{\"error\":\"test request rejected\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            }
        });
        server.start();

        Path root = Files.createTempDirectory("botstation-admin-cloud-password-");
        RecordingPrompt prompt = new RecordingPrompt("correctpassword".toCharArray());
        TaskRunner tasks = new TaskRunner();
        try {
            URI api = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/announcement-cloud");
            AdminIpTrustClient client = AdminIpTrustClient.forTest(api, "");
            Path deliberatelyMissing = root.resolve("admin_password.txt");
            AdminGate gate = new AdminGate(deliberatelyMissing, new LogBus(root.resolve("logs")), client, prompt);
            CountDownLatch finished = new CountDownLatch(1);
            AtomicBoolean authorized = new AtomicBoolean();
            gate.authorizeAsync(null, tasks, result -> {
                authorized.set(result);
                finished.countDown();
            });

            require(finished.await(3, TimeUnit.SECONDS), "authorization did not finish");
            require(authorized.get() && gate.isAuthorized(), "missing local password file still locks the admin page"
                + " requests=" + prompt.requests.get() + " errors=" + prompt.errors.get()
                + " grants=" + passwordGrants.get() + " requestFailure=" + requestFailure.get());
            require(prompt.requests.get() == 1, "password was not requested exactly once");
            require(prompt.errors.get() == 0, "missing-file error was still shown");
            require(passwordGrants.get() == 1, "password was not verified by the cloud exactly once");
            System.out.println("ADMIN_GATE_CLOUD_PASSWORD_GREEN");
        } finally {
            tasks.close();
            server.stop(0);
        }
    }

    private static void handle(HttpExchange exchange, AtomicInteger passwordGrants) throws java.io.IOException {
        require(exchange.getRequestHeaders().getFirst("Authorization") == null, "clean install must not require a desktop secret");
        String query = String.valueOf(exchange.getRequestURI().getRawQuery());
        String response;
        if (query.contains("action=workstation-admin-check")) {
            response = "{\"admin\":false}";
        } else if (query.contains("action=workstation-admin-grant")) {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            require(request.contains("\"p\":\"correctpassword\""), "password request body");
            passwordGrants.incrementAndGet();
            response = "{\"admin\":true}";
        } else {
            throw new AssertionError("unexpected action: " + query);
        }
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static final class RecordingPrompt implements AdminGate.AdminPrompt {
        private final char[] password;
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicInteger errors = new AtomicInteger();

        private RecordingPrompt(char[] password) { this.password = password; }

        @Override
        public char[] requestPassword(Component parent) {
            requests.incrementAndGet();
            return password.clone();
        }

        @Override public void warning(Component parent, String title, String message) {}
        @Override public void error(Component parent, String title, String message) { errors.incrementAndGet(); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
