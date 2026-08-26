package com.mcz;

import org.junit.jupiter.api.Test;

final class CoreRegressionTest {
    @Test
    void catchChartsKeepAnExclusiveKeyBlock() {
        CatchChartBlockAllocatorTest.main(new String[0]);
        CatchChartRowMergerTest.main(new String[0]);
    }
}
