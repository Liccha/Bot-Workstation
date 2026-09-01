package com.botstation.features;

import com.botstation.ui.UiKit;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.concurrent.atomic.AtomicReference;

/** Guards the resource/decision grouping at the bottom of the desktop song editor. */
public final class SongLibraryActionBarRegressionTest {
    private SongLibraryActionBarRegressionTest() {}

    public static void main(String[] args) throws Exception {
        FlatLightLaf.setup();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                JButton cover = UiKit.button("更换歌曲图片");
                JButton audio = UiKit.button("更换歌曲音频");
                JButton delete = UiKit.button("删除歌曲");
                JButton cancel = UiKit.button("取消");
                JButton save = UiKit.primaryButton("保存修改");
                JPanel bar = SongLibraryPanel.editorActionBar(cover, audio, delete, cancel, save);
                bar.setSize(700, 68);
                layoutRecursively(bar);

                require(cover.getWidth() == audio.getWidth(), "resource actions are not equal width");
                require(cancel.getWidth() == save.getWidth(), "decision actions are not equal width");
                require(cover.getX() < audio.getX(), "resource action order changed");
                require(absoluteX(bar, cover) + cover.getWidth() <= absoluteX(bar, audio), "resource buttons overlap");
                require(cancel.getX() < save.getX(), "decision action order changed");
                require(absoluteX(bar, audio) + audio.getWidth() + 16 < absoluteX(bar, cancel),
                    "resource and decision groups no longer have a clear visual break");
                require(absoluteX(bar, save) + save.getWidth() <= bar.getWidth() - 16,
                    "primary action is not aligned to the right inset");
                for (JButton button : new JButton[]{cover, audio, delete, cancel, save})
                    require(button.getPreferredSize().width >= button.getFontMetrics(button.getFont()).stringWidth(button.getText()) + 24,
                        "button text may be ellipsized: " + button.getText());
                for (String field : new String[]{"4k_ez", "4k_nm", "4k_hd", "4k_mx", "4k_sp"})
                    require(field.equals(SongLibraryPanel.editorFieldLabel(field)),
                        "difficulty field name was translated: " + field);
                System.out.println("SONG_EDITOR_ACTION_BAR_GREEN");
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
        System.exit(0);
    }

    private static int absoluteX(Container root, Component component) {
        int x = component.getX();
        for (Container parent = component.getParent(); parent != null && parent != root; parent = parent.getParent()) {
            x += parent.getX();
        }
        return x;
    }

    private static void layoutRecursively(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container) layoutRecursively((Container) child);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
