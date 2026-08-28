package com.botstation;

import com.botstation.core.UpdateServiceRegressionTest;
import com.botstation.core.ProcessSupervisorRegressionTest;
import com.botstation.features.StableRepositoryRegressionTest;
import com.botstation.features.SongLibraryPersistenceRegressionTest;
import org.junit.jupiter.api.Test;

final class CoreRegressionTest {
    @Test
    void updateManifestIsStrictlyValidated() {
        UpdateServiceRegressionTest.main(new String[0]);
    }

    @Test
    void stableWritesAreBackedUpAndTransactional() throws Exception {
        StableRepositoryRegressionTest.main(new String[0]);
    }

    @Test
    void songEditsSurviveTheNextCsvImport() throws Exception {
        SongLibraryPersistenceRegressionTest.main(new String[0]);
    }

    @Test
    void runningSongBotIsIsolatedFromWorkstationUpdates() throws Exception {
        ProcessSupervisorRegressionTest.main(new String[0]);
    }
}
