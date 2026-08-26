package com.mybot;

import org.junit.jupiter.api.Test;

final class CoreRegressionTest {
    @Test
    void announcementsRemainRevisionSafe() throws Exception {
        AnnouncementStoreRegressionTest.main(new String[0]);
    }

    @Test
    void songAndStableImportsRejectDestructiveEmptyData() throws Exception {
        SongImportRegressionTest.main(new String[0]);
        StableImportRegressionTest.main(new String[0]);
    }

    @Test
    void unicodeAttachmentNamesSurviveNapCatUpload() throws Exception {
        NapCatUnicodeFilenameRegressionTest.main(new String[0]);
    }
}
