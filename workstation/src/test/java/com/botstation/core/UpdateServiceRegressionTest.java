package com.botstation.core;

public final class UpdateServiceRegressionTest {
    private UpdateServiceRegressionTest() {}
    public static void main(String[] args) {
        require(UpdateService.compareVersions("1.1.1", "1.1.0") > 0, "patch version compare failed");
        require(UpdateService.compareVersions("1.2", "1.1.99") > 0, "minor version compare failed");
        require(UpdateService.compareVersions("1.1.0", "1.1") == 0, "normalized version compare failed");
        System.out.println("UPDATE_SERVICE_GREEN");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
