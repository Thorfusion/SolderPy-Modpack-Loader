package io.github.thorfusion.solderpyloader;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootstrapWorkerTest {
    @TempDir Path temporary;

    @AfterEach
    void clearRuntimeCandidates() {
        System.clearProperty("solderpy.loader.java");
        System.clearProperty("solderpy.loader.java.0");
        System.clearProperty("solderpy.loader.java.1");
    }

    @Test
    void keepsFallbacksWhenEarlierRuntimeIsUnavailable() throws Exception {
        Path fallbackHome = fakeJavaHome("fallback");
        System.setProperty("solderpy.loader.java.0",
            temporary.resolve("missing-java").toString());
        System.setProperty("solderpy.loader.java.1", fallbackHome.toString());

        List<Path> candidates = BootstrapWorker.javaExecutables(null);

        assertEquals(expectedExecutable(fallbackHome), candidates.get(0));
        assertTrue(candidates.size() >= 2, "the current JVM should remain a final fallback");
    }

    @Test
    void explicitRuntimeStaysAheadOfAutomaticallyDetectedRuntimes() throws Exception {
        Path explicitHome = fakeJavaHome("explicit");
        Path detectedHome = fakeJavaHome("detected");
        System.setProperty("solderpy.loader.java.0", detectedHome.toString());

        List<Path> candidates = BootstrapWorker.javaExecutables(explicitHome.toString());

        assertEquals(expectedExecutable(explicitHome), candidates.get(0));
        assertEquals(expectedExecutable(detectedHome), candidates.get(1));
    }

    private Path fakeJavaHome(String name) throws Exception {
        Path home = temporary.resolve(name);
        Path bin = Files.createDirectories(home.resolve("bin"));
        Files.createFile(bin.resolve(isWindows() ? "javaw.exe" : "java"));
        return home;
    }

    private Path expectedExecutable(Path home) {
        return home.resolve("bin").resolve(isWindows() ? "javaw.exe" : "java")
            .toAbsolutePath().normalize();
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
