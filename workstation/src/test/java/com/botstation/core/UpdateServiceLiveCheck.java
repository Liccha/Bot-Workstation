package com.botstation.core;

/** Manual release-gate check against the public CDN manifest. */
public final class UpdateServiceLiveCheck {
    private UpdateServiceLiveCheck() {}

    public static void main(String[] args) throws Exception {
        BotPaths paths = BotPaths.detect();
        UpdateService updates = new UpdateService(paths, new LogBus(paths.logs()));
        UpdateService.ReleaseInfo release = updates.check();
        if (!UpdateService.CURRENT_VERSION.equals(release.version)) {
            throw new AssertionError("public PC manifest is " + release.version
                + ", expected " + UpdateService.CURRENT_VERSION);
        }
        if (updates.available(release)) {
            throw new AssertionError("current PC build incorrectly reports an update");
        }
        System.out.println("UPDATE_LIVE_GREEN version=" + release.version + " size=" + release.size);
    }
}
