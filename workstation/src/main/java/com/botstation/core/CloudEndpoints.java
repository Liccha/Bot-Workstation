package com.botstation.core;

import java.net.URI;
/** Isolated endpoints used only by the temporary portfolio demo builds. */
public final class CloudEndpoints {
    public static final String HOST = "songbotdemo-api-hxhuxsgwar.cn-beijing.fcapp.run";
    public static final URI ANNOUNCEMENT = URI.create("https://" + HOST + "/api/announcement-cloud");
    public static final URI MOBILE_DATA = URI.create("https://" + HOST + "/api/mobile-data");
    public static final URI MOBILE_RELAY = URI.create("https://" + HOST + "/api/mobile-relay");

    private CloudEndpoints() {}

    public static boolean isProductionHost(String value) {
        return value != null && HOST.equalsIgnoreCase(value.trim());
    }

    public static URI migrateLegacy(URI value) {
        if (value != null && "portfolio.invalid".equalsIgnoreCase(value.getHost())) {
            return URI.create("https://" + HOST + value.getPath());
        }
        return value;
    }
}
