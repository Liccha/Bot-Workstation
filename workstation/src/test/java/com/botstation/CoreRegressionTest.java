package com.botstation;

import com.botstation.core.UpdateServiceRegressionTest;
import com.botstation.features.StableRepositoryRegressionTest;
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
}
