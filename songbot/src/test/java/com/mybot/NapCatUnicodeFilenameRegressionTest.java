package com.mybot;

import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

public final class NapCatUnicodeFilenameRegressionTest {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> receivedName = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        server.createContext("/upload_group_file", exchange -> {
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] body = exchange.getRequestBody().readAllBytes();
            JSONObject payload = new JSONObject(new String(body, StandardCharsets.UTF_8));
            receivedName.set(payload.optString("name", ""));
            byte[] response = "{\"status\":\"ok\",\"retcode\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        try {
            server.start();
            System.setProperty("napcat.api.url", "http://127.0.0.1:" + server.getAddress().getPort());
            String expected = "次元音符 第6期（终稿）-하루🎵.pdf";
            String response = new NapCatClient().uploadGroupFile(2000000004L, "C:\\cache\\hash.pdf", expected);
            require(response != null && response.contains("\"retcode\":0"), "mock NapCat response was not returned");
            require(expected.equals(receivedName.get()), "NapCat filename did not survive UTF-8 JSON transport");
            require("application/json; charset=utf-8".equalsIgnoreCase(contentType.get()), "request charset is not explicit UTF-8");
            System.out.println("NapCat Unicode filename regression test passed");
        } finally {
            server.stop(0);
            System.clearProperty("napcat.api.url");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
