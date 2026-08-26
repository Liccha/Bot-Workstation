package com.botstation.features;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;

/** An empty Stable editor table must never replace the usable database. */
public final class StableRepositoryRegressionTest {
    private StableRepositoryRegressionTest() {}

    public static void main(String[] args) throws Exception {
        Method validate = StableRepository.class.getDeclaredMethod("validate", java.util.List.class, java.util.List.class);
        validate.setAccessible(true);
        boolean rejected = false;
        try {
            validate.invoke(null,
                Arrays.asList("sid", "title", "artist", "bpm", "length", "creator", "update_time", "cover"),
                new ArrayList<>());
        } catch (InvocationTargetException error) {
            rejected = error.getCause() instanceof IllegalArgumentException;
        }
        require(rejected, "empty Stable dataset is rejected before any database or workbook write");
        System.out.println("STABLE_REPOSITORY_GREEN");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
