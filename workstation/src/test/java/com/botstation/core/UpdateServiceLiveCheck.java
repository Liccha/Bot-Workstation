package com.botstation.core;

/** Manual no-loop check against the public CDN manifest. */
public final class UpdateServiceLiveCheck {
    private UpdateServiceLiveCheck() {}

    public static void main(String[] args) throws Exception {
        BotPaths paths = BotPaths.detect();
        UpdateService updates = new UpdateService(paths, new LogBus(paths.logs()));
        UpdateService.ReleaseInfo release = updates.check();
        if (updates.available(release)) {
            throw new AssertionError("current PC build " + UpdateService.CURRENT_VERSION
                + " incorrectly reports public " + release.version + " as an update");
        }
        System.out.println("UPDATE_LIVE_GREEN current=" + UpdateService.CURRENT_VERSION
            + " public=" + release.version + " size=" + release.size);
    }
}
