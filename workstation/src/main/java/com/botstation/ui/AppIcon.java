package com.botstation.ui;

import java.awt.Image;
import java.awt.Toolkit;
import java.net.URL;

/** Loads the packaged application icon once; no runtime dependency on the MczMaker folder. */
final class AppIcon {
    private static final Image IMAGE = load();

    private AppIcon() {}

    static Image image() { return IMAGE; }

    private static Image load() {
        URL resource = AppIcon.class.getResource("/app/icon.png");
        return resource == null ? null : Toolkit.getDefaultToolkit().getImage(resource);
    }
}
