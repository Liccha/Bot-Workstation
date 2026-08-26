package com.mcz;

/** Regression guard for the announcement grid clipping seen inside Bot 工作站. */
public final class AnnouncementEditorLayoutTest {
    private AnnouncementEditorLayoutTest() {}

    public static void main(String[] args) {
        require(AnnouncementEditor.cardColumnsForWidth(520) == 1, "520px should use one column");
        require(AnnouncementEditor.cardColumnsForWidth(760) == 2, "760px should use two columns");
        require(AnnouncementEditor.cardColumnsForWidth(1120) == 3, "1120px should use three columns");
        require(AnnouncementEditor.cardColumnsForWidth(1400) == 4, "1400px should use four columns");
        System.out.println("ANNOUNCEMENT_LAYOUT_GREEN");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
