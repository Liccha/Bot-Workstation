package com.botstation.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Prevents a packaged release from prompting to install itself forever. */
public final class ReleaseVersionConsistencyRegressionTest {
    private ReleaseVersionConsistencyRegressionTest() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        requireVersion(root.resolve("build_config.xml"),
            Pattern.compile("<txtProductVersion>([^<]+)</txtProductVersion>"));
        requireVersion(root.resolve("scripts/launch4j_ascii.xml"),
            Pattern.compile("<txtProductVersion>([^<]+)</txtProductVersion>"));
        requireVersion(root.resolve("installer.iss"),
            Pattern.compile("#define AppVersion \"([^\"]+)\""));
        requireUpgradeSafety(root.resolve("installer.iss"));
        System.out.println("RELEASE_VERSION_CONSISTENCY_GREEN version=" + UpdateService.CURRENT_VERSION);
    }

    private static void requireVersion(Path file, Pattern pattern) throws Exception {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        Matcher matcher = pattern.matcher(source);
        if (!matcher.find()) throw new AssertionError("missing release version in " + file);
        if (!UpdateService.CURRENT_VERSION.equals(matcher.group(1))) {
            throw new AssertionError(file.getFileName() + " is " + matcher.group(1)
                + ", but runtime is " + UpdateService.CURRENT_VERSION);
        }
    }

    private static void requireUpgradeSafety(Path file) throws Exception {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        require(source.contains("CloseApplications=force"),
            "installer must force-close the previous workstation during an upgrade");
        require(source.contains("CloseApplicationsFilter=*.*"),
            "installer must detect locks on JAR, DLL and runtime files, not only the launcher EXE");
        require(source.contains("RestartApplications=no"),
            "installer must not relaunch the old process during file replacement");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
