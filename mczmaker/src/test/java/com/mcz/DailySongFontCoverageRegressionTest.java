package com.mcz;

import java.awt.Font;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Exercises the exact font and CSV path used by the daily-song list. */
public final class DailySongFontCoverageRegressionTest {
    public static void main(String[] args) throws Exception {
        File csv = new File(new File(System.getProperty("user.home"), "Desktop/SongBot"), "daily_songs.csv");
        String text = new String(Files.readAllBytes(csv.toPath()), StandardCharsets.UTF_8);
        if (text.indexOf('\uFFFD') >= 0 || text.indexOf('\0') >= 0) {
            throw new AssertionError("daily_songs.csv contains damaged Unicode characters");
        }
        Font font = DailySongManager.dailySongListFont();
        String allText = text.replace("\uFEFF", "").replace(',', ' ').replace('"', ' ');
        for (String family : new String[]{"Malgun Gothic", "Microsoft YaHei", "Microsoft YaHei UI", "DengXian",
                "Yu Gothic UI", "Yu Gothic", "Meiryo UI", "Segoe UI", Font.DIALOG}) {
            Font candidate = new Font(family, Font.PLAIN, 13);
            int missing = candidate.canDisplayUpTo(allText);
            String missingCodePoint = missing < 0 ? "none" : "U+" + String.format("%04X", allText.codePointAt(missing));
            System.out.println("COVERAGE_PROBE family=" + family + " resolved=" + candidate.getFontName()
                + " firstUnsupported=" + missing + " codePoint=" + missingCodePoint);
        }
        List<String> failures = new ArrayList<>();
        String[] lines = text.split("\\R");
        for (int index = 1; index < lines.length; index++) {
            String[] parts = DailySongManager.parseCsvLine(lines[index], 3);
            if (parts.length < 3) continue;
            String rendered = parts[0] + ". " + DailySongManager.cleanField(parts[1]) + " — " + DailySongManager.cleanField(parts[2]);
            int unsupportedCodePoint = rendered.codePoints()
                .filter(codePoint -> !DailySongManager.dailySongFontForCodePoint(codePoint, Font.PLAIN, 13).canDisplay(codePoint))
                .findFirst().orElse(-1);
            if (unsupportedCodePoint >= 0) {
                int codePoint = unsupportedCodePoint;
                failures.add("line=" + (index + 1) + " char=U+" + String.format("%04X", codePoint) + " text=" + rendered);
                if (failures.size() == 8) break;
            }
        }
        if (!failures.isEmpty()) {
            System.err.println("FONT_COVERAGE_RED font=" + font.getFontName());
            for (String failure : failures) System.err.println(failure);
            System.exit(2);
        }
        System.out.println("FONT_COVERAGE_GREEN primary=" + font.getFontName() + " fallback=per-glyph");
    }
}
