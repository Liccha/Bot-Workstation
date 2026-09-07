package com.botstation.core;

import java.net.URI;
/** Non-routable endpoints used by the public read-only portfolio snapshot. */
public final class CloudEndpoints {
    public static final String HOST = "portfolio.invalid";
    public static final URI ANNOUNCEMENT = URI.create("https://" + HOST + "/api/announcement-cloud");
    public static final URI MOBILE_DATA = URI.create("https://" + HOST + "/api/mobile-data");
    public static final URI MOBILE_RELAY = URI.create("https://" + HOST + "/api/mobile-relay");

    private CloudEndpoints() {}

    public static boolean isProductionHost(String value) {
        return false;
    }

    public static URI migrateLegacy(URI value) {
        return value;
    }
}
