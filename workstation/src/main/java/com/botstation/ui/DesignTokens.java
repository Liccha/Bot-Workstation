package com.botstation.ui;

import javax.swing.BorderFactory;
import javax.swing.border.Border;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;

/**
 * Hallmark · Side-rail Control Room · studied dashboard DNA · modern-minimal.
 * Pre-emit critique P5 H5 E5 S5 R5 V5 · designed-as-app · design system: design.md.
 */
public final class DesignTokens {
    public static final Color PAPER = new Color(252, 249, 252);
    public static final Color GRADIENT_INDIGO = new Color(238, 242, 255);
    public static final Color GRADIENT_CENTER = new Color(255, 255, 255);
    public static final Color GRADIENT_PINK = new Color(253, 242, 248);
    public static final Color SURFACE = new Color(255, 255, 255);
    public static final Color SURFACE_TINT = new Color(255, 249, 252);
    public static final Color SURFACE_ALT = new Color(250, 247, 250);
    public static final Color NAV = new Color(255, 249, 252);
    public static final Color NAV_HOVER = new Color(255, 240, 245);
    public static final Color NAV_TEXT = new Color(52, 48, 56);
    public static final Color NAV_MUTED = new Color(112, 105, 116);
    public static final Color NAV_ACCENT = new Color(243, 59, 124);
    public static final Color ACCENT = new Color(243, 59, 124);
    public static final Color ACCENT_HOVER = new Color(226, 47, 111);
    public static final Color ACCENT_PRESSED = new Color(198, 38, 91);
    public static final Color ACCENT_SOFT = new Color(255, 228, 233);
    public static final Color ICON_SURFACE = new Color(249, 244, 248);
    public static final Color VIOLET_SOFT = new Color(246, 241, 250);
    public static final Color INK = new Color(45, 42, 48);
    public static final Color MUTED = new Color(104, 98, 108);
    public static final Color BORDER = new Color(239, 233, 238);
    public static final Color BORDER_STRONG = new Color(218, 208, 216);
    public static final Color CONTROL_HOVER = new Color(248, 242, 247);
    public static final Color CONTROL_PRESSED = new Color(239, 231, 237);
    public static final Color SUCCESS = new Color(32, 137, 89);
    public static final Color SUCCESS_SOFT = new Color(231, 246, 237);
    public static final Color WARNING = new Color(181, 116, 20);
    public static final Color DANGER = new Color(190, 63, 72);
    public static final Color UNKNOWN = new Color(137, 144, 159);

    private static final String UI_FAMILY = hasFont("Microsoft YaHei UI")
        ? "Microsoft YaHei UI" : Font.SANS_SERIF;
    private static final String LATIN_FAMILY = hasFont("Segoe UI")
        ? "Segoe UI" : UI_FAMILY;
    private static final String JAPANESE_FAMILY = firstFont("Yu Gothic UI", "Meiryo UI", "Meiryo", UI_FAMILY);
    private static final String KOREAN_FAMILY = firstFont("Malgun Gothic", "Noto Sans CJK KR", UI_FAMILY);

    public static final Font TITLE = displayFont(Font.BOLD, 28);
    public static final Font SECTION = displayFont(Font.BOLD, 18);
    public static final Font CARD_TITLE = displayFont(Font.BOLD, 17);
    public static final Font BRAND = displayFont(Font.BOLD, 18);
    // Tables and editable values may contain mixed CJK text in one cell. Java's
    // Logical composite font remains the final multilingual fallback.
    public static final Font BODY = new Font(Font.SANS_SERIF, Font.PLAIN, 14);
    public static final Font BODY_MEDIUM = font(Font.BOLD, 14);
    public static final Font BODY_SMALL = font(Font.PLAIN, 13);
    public static final Font BUTTON = font(Font.BOLD, 13);
    public static final Font NAV_ITEM = font(Font.PLAIN, 14);
    public static final Font NAV_ACTIVE = font(Font.BOLD, 14);
    public static final Font CAPTION = font(Font.PLAIN, 12);
    // Java's logical families keep CJK fallback enabled and avoid tofu glyphs.
    public static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 12);

    private DesignTokens() {}

    public static Font font(int style, int size) {
        return new Font(UI_FAMILY, style, size);
    }

    public static Font displayFont(int style, int size) {
        return new Font(UI_FAMILY, style, size);
    }

    public static Font forText(String text, Font preferred) {
        if (text == null) return preferred;
        String family = containsHangul(text) ? KOREAN_FAMILY
            : containsJapanese(text) ? JAPANESE_FAMILY
            : containsCjk(text) ? UI_FAMILY : LATIN_FAMILY;
        Font candidate = new Font(family, preferred.getStyle(), preferred.getSize());
        if (candidate.canDisplayUpTo(text) < 0) return candidate;
        if (preferred.canDisplayUpTo(text) < 0) return preferred;
        return new Font(Font.SANS_SERIF, preferred.getStyle(), preferred.getSize());
    }

    private static boolean containsHangul(String text) {
        return text.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HANGUL);
    }

    private static boolean containsJapanese(String text) {
        return text.codePoints().anyMatch(codePoint -> {
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            return script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA;
        });
    }

    private static boolean containsCjk(String text) {
        return text.codePoints().anyMatch(codePoint -> {
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
        });
    }

    private static boolean hasFont(String family) {
        for (String available : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) {
            if (available.equalsIgnoreCase(family)) return true;
        }
        return false;
    }

    private static String firstFont(String... families) {
        for (String family : families) if (hasFont(family)) return family;
        return Font.SANS_SERIF;
    }

    public static Border cardBorder() {
        return BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER, 1, true),
            BorderFactory.createEmptyBorder(16, 16, 16, 16));
    }

    public static Border serviceCardBorder() {
        return BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER, 1, true),
            BorderFactory.createEmptyBorder(14, 16, 16, 16));
    }
}
