package com.botstation.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Executable regression seam for safe NapCat configuration editing. */
public final class NapCatConfigServiceRegressionTest {
    private NapCatConfigServiceRegressionTest() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("napcat-config-regression-");
        try {
            Path napCat = root.resolve("NapCat.Shell");
            Path songBot = root.resolve("SongBot");
            Path config = napCat.resolve("napcat/config");
            Files.createDirectories(config);

            Files.writeString(config.resolve("webui.json"),
                "{\"host\":\"127.0.0.1\",\"port\":6099,\"token\":\"fixture-web-secret\","
                    + "\"disableWebUI\":false,\"unknown\":{\"keep\":true}}",
                StandardCharsets.UTF_8);
            Files.writeString(config.resolve("onebot11_fixture.json"),
                "{\"network\":{\"httpServers\":["
                    + "{\"enable\":false,\"host\":\"0.0.0.0\",\"port\":3001,\"token\":\"disabled-secret\"},"
                    + "{\"enable\":true,\"host\":\"127.0.0.1\",\"port\":3000,\"token\":\"active-secret\"}],"
                    + "\"httpClients\":[{\"enable\":true,\"url\":\"http://127.0.0.1:8080/webhook\","
                    + "\"token\":\"callback-secret\"}]},\"unknownRoot\":17}",
                StandardCharsets.UTF_8);

            NapCatConfigService service = new NapCatConfigService(napCat, songBot);
            NapCatConfigService.Snapshot before = service.load();
            require(before.webUiEnabled && before.webUiPort == 6099, "WebUI discovery");
            require(before.httpEnabled && before.httpPort == 3000, "OneBot HTTP discovery");
            require(before.callbackEnabled && before.callbackUrl.endsWith(":8080/webhook"), "callback discovery");
            require(before.webUiTokenConfigured && before.httpTokenConfigured && before.callbackTokenConfigured,
                "token presence discovery");

            service.save(new NapCatConfigService.Settings(true, 6100, true, 3100, true,
                "http://127.0.0.1:8181/webhook"));

            JSONObject web = new JSONObject(Files.readString(config.resolve("webui.json"), StandardCharsets.UTF_8));
            require(web.getInt("port") == 6100 && "127.0.0.1".equals(web.getString("host")), "WebUI update");
            require("fixture-web-secret".equals(web.getString("token")), "WebUI token preservation");
            require(web.getJSONObject("unknown").getBoolean("keep"), "WebUI unknown field preservation");

            JSONObject oneBot = new JSONObject(Files.readString(config.resolve("onebot11_fixture.json"), StandardCharsets.UTF_8));
            JSONArray servers = oneBot.getJSONObject("network").getJSONArray("httpServers");
            require(!servers.getJSONObject(0).getBoolean("enable") && servers.getJSONObject(0).getInt("port") == 3001,
                "disabled HTTP server preservation");
            require(servers.getJSONObject(1).getBoolean("enable") && servers.getJSONObject(1).getInt("port") == 3100,
                "active HTTP server update");
            require("active-secret".equals(servers.getJSONObject(1).getString("token")), "HTTP token preservation");
            require(oneBot.getInt("unknownRoot") == 17, "OneBot unknown field preservation");
            require(Files.readString(songBot.resolve("data/napcat.properties"), StandardCharsets.UTF_8)
                .trim().equals("apiUrl=http://127.0.0.1:3100"), "SongBot endpoint handoff");

            expectFailure(() -> service.save(new NapCatConfigService.Settings(true, 3100, true, 3100, true,
                "http://127.0.0.1:8181/webhook")), "duplicate ports rejected");
            expectFailure(() -> service.save(new NapCatConfigService.Settings(true, 6100, true, 3100, true,
                "http://example.com:8181/webhook")), "non-loopback callback rejected");
            System.out.println("NAPCAT_CONFIG_GREEN");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) {}
                });
            }
        }
    }

    private static void expectFailure(ThrowingAction action, String label) throws Exception {
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(label);
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    @FunctionalInterface private interface ThrowingAction { void run() throws Exception; }
}
