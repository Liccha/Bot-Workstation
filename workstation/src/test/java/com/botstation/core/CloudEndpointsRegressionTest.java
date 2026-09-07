package com.botstation.core;

import java.net.URI;

public final class CloudEndpointsRegressionTest {
    private CloudEndpointsRegressionTest() {}

    public static void main(String[] args) {
        assertEquals(CloudEndpoints.HOST, CloudEndpoints.MOBILE_DATA.getHost());
        assertEquals("/api/mobile-data", CloudEndpoints.MOBILE_DATA.getPath());
        assertTrue(CloudEndpoints.isProductionHost(CloudEndpoints.HOST));
        assertTrue(!CloudEndpoints.isProductionHost("attacker.fcapp.run"));
        URI migrated = CloudEndpoints.migrateLegacy(
            URI.create("https://portfolio.invalid/api/mobile-data"));
        assertEquals(CloudEndpoints.HOST, migrated.getHost());
        assertEquals("/api/mobile-data", migrated.getPath());
        URI untouched = CloudEndpoints.migrateLegacy(
            URI.create("https://example.invalid/api/announcement-cloud"));
        assertEquals("example.invalid", untouched.getHost());
        assertEquals("/api/announcement-cloud", untouched.getPath());
        System.out.println("CloudEndpointsRegressionTest passed");
    }

    private static void assertTrue(boolean value) {
        if (!value) throw new AssertionError("expected true");
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }
}
