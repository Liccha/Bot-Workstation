package com.botstation.core;

import java.net.URI;
import java.util.Locale;

/** Canonical domestic cloud endpoints used by every workstation module. */
public final class CloudEndpoints {
    public static final String HOST = "songbotstic-api-cwpfgfkkpj.cn-beijing.fcapp.run";
    public static final URI ANNOUNCEMENT = URI.create("https://" + HOST + "/api/announcement-cloud");
    public static final URI MOBILE_DATA = URI.create("https://" + HOST + "/api/mobile-data");
    public static final URI MOBILE_RELAY = URI.create("https://" + HOST + "/api/mobile-relay");

    private CloudEndpoints() {}

    public static boolean isProductionHost(String value) {
        String host = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return HOST.equals(host)
            || "editor.teacharm.moe".equals(host)
            || "bot-editor.vercel.app".equals(host);
    }

    public static URI migrateLegacy(URI value) {
        if (value == null || value.getHost() == null) return value;
        String host = value.getHost().toLowerCase(Locale.ROOT);
        if (!"editor.teacharm.moe".equals(host) && !"bot-editor.vercel.app".equals(host)) return value;
        return URI.create("https://" + HOST + value.getRawPath()
            + (value.getRawQuery() == null ? "" : "?" + value.getRawQuery()));
    }
}
