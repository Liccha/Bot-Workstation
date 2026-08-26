package com.botstation.security;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Executable regression seam for the password-once trusted-IP client. */
public final class AdminIpTrustClientRegressionTest {
    private AdminIpTrustClientRegressionTest() {}

    public static void main(String[] args) throws Exception {
        AtomicInteger checks = new AtomicInteger();
        AtomicInteger grants = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/announcement-cloud", exchange -> handle(exchange, checks, grants));
        server.start();
        try {
            URI api = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/announcement-cloud");
            AdminIpTrustClient client = AdminIpTrustClient.forTest(api, "test-token");
            require(!client.isTrusted(), "untrusted IP check");
            client.grant();
            require(client.isTrusted(), "trusted IP check");
            require(checks.get() == 2 && grants.get() == 1, "request counts");
            System.out.println("ADMIN_IP_TRUST_GREEN");
        } finally {
            server.stop(0);
        }
    }

    private static void handle(HttpExchange exchange, AtomicInteger checks, AtomicInteger grants) throws java.io.IOException {
        require("Desktop test-token".equals(exchange.getRequestHeaders().getFirst("Authorization")), "desktop token header");
        require(exchange.getRequestHeaders().getFirst("X-Admin-Device").startsWith("bot-workstation"), "device header");
        String query = exchange.getRequestURI().getRawQuery();
        boolean grant = query != null && query.contains("action=desktop-ip-grant");
        if (grant) grants.incrementAndGet(); else checks.incrementAndGet();
        boolean trusted = grant || grants.get() > 0;
        byte[] body = ("{\"trusted\":" + trusted + "}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
