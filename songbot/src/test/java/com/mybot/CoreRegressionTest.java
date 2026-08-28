package com.mybot;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
final class CoreRegressionTest {
    @Test
    @Order(3)
    void announcementsRemainRevisionSafe() throws Exception {
        AnnouncementStoreRegressionTest.main(new String[0]);
    }

    @Test
    @Order(2)
    void songAndStableImportsRejectDestructiveEmptyData() throws Exception {
        SongImportRegressionTest.main(new String[0]);
        StableImportRegressionTest.main(new String[0]);
    }

    @Test
    @Order(1)
    void unicodeAttachmentNamesSurviveNapCatUpload() throws Exception {
        NapCatUnicodeFilenameRegressionTest.main(new String[0]);
    }

    @Test
    @Order(4)
    void textCommandsKeepEveryLegacyLookupField() throws Exception {
        SongTextCommandRegressionTest.main(new String[0]);
    }
}
