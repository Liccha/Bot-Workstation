package com.botstation;

import com.botstation.core.BotPaths;
import com.mcz.MczEmbedBridge;

import javax.swing.JComponent;
import javax.swing.JMenuBar;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.concurrent.atomic.AtomicReference;

/** Constructs the real embedded editor without showing a window or playing audio. */
final class MczEmbedSelfTest {
    private MczEmbedSelfTest() {}

    static int run(BotPaths paths) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<JComponent> embedded = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try { embedded.set(MczEmbedBridge.create(paths.mczMaker)); }
                catch (Throwable error) { failure.set(error); }
            });
            if (failure.get() != null) throw failure.get();
            JComponent root = embedded.get();
            boolean menu = contains(root, JMenuBar.class);
            boolean substantial = componentCount(root) > 60;
            System.out.println(menu && substantial ? "MCZ_EMBED_OK" : "MCZ_EMBED_INCOMPLETE");
            return menu && substantial ? 0 : 3;
        } catch (Throwable error) {
            error.printStackTrace();
            return 3;
        }
    }

    private static boolean contains(Component root, Class<?> type) {
        if (type.isInstance(root)) return true;
        if (root instanceof Container) {
            for (Component child : ((Container) root).getComponents()) if (contains(child, type)) return true;
        }
        return false;
    }

    private static int componentCount(Component root) {
        int count = 1;
        if (root instanceof Container) {
            for (Component child : ((Container) root).getComponents()) count += componentCount(child);
        }
        return count;
    }
}
