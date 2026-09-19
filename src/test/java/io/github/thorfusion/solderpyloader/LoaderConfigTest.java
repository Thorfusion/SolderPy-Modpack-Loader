package io.github.thorfusion.solderpyloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoaderConfigTest {
    @TempDir Path temporary;

    @Test
    void acceptsLoopbackHttpAndNormalizesApi() throws Exception {
        Path configFile = temporary.resolve("loader.json");
        Files.write(configFile, ("{\n" +
            "  \"api\": \"http://127.0.0.1:8080/api\",\n" +
            "  \"modpack\": \"pack\"\n" +
            "}\n").getBytes(StandardCharsets.UTF_8));

        LoaderConfig config = LoaderConfig.load(configFile);

        assertEquals("http://127.0.0.1:8080/api/", config.api);
        assertEquals("recommended", config.build);
        assertEquals("auto", config.target);
        assertEquals(25, config.bootstrapJavaMajor);
        assertEquals(4, config.limits.maxConcurrentDownloads);
        assertEquals(1, config.limits.maxConcurrentExtractions);
        assertTrue(config.enabled);
    }

    @Test
    void acceptsAnExplicitBootstrapJavaRuntime() throws Exception {
        Path configFile = temporary.resolve("loader.json");
        Files.write(configFile, ("{\"api\":\"https://example.com/api/\"," +
            "\"modpack\":\"pack\",\"bootstrapJava\":\"  C:/Java/bin/java.exe  \"}")
            .getBytes(StandardCharsets.UTF_8));

        LoaderConfig config = LoaderConfig.load(configFile);

        assertEquals("C:/Java/bin/java.exe", config.bootstrapJava);
    }

    @Test
    void acceptsAnExplicitBootstrapJavaMajor() throws Exception {
        Path configFile = temporary.resolve("loader.json");
        Files.write(configFile, ("{\"api\":\"https://example.com/api/\"," +
            "\"modpack\":\"pack\",\"bootstrapJavaMajor\":17}")
            .getBytes(StandardCharsets.UTF_8));

        LoaderConfig config = LoaderConfig.load(configFile);

        assertEquals(17, config.bootstrapJavaMajor);
    }

    @Test
    void rejectsBootstrapJavaOlderThanEight() throws Exception {
        Path configFile = temporary.resolve("loader.json");
        Files.write(configFile, ("{\"api\":\"https://example.com/api/\"," +
            "\"modpack\":\"pack\",\"bootstrapJavaMajor\":7}")
            .getBytes(StandardCharsets.UTF_8));

        assertThrows(LoaderException.class, () -> LoaderConfig.load(configFile));
    }

    @Test
    void resolvesAutomaticTargetFromRelauncher() throws Exception {
        Path configFile = temporary.resolve("loader.json");
        Files.write(configFile, ("{\"api\":\"https://example.com/api/\"," +
            "\"modpack\":\"pack\"}").getBytes(StandardCharsets.UTF_8));
        LoaderConfig config = LoaderConfig.load(configFile);

        config.resolveTarget("SERVER");

        assertEquals("server", config.target);
    }

    @Test
    void explicitTargetOverridesDetectedSide() throws Exception {
        Path configFile = temporary.resolve("loader.json");
        Files.write(configFile, ("{\"api\":\"https://example.com/api/\"," +
            "\"modpack\":\"pack\",\"target\":\"client\"}")
            .getBytes(StandardCharsets.UTF_8));
        LoaderConfig config = LoaderConfig.load(configFile);

        config.resolveTarget("server");

        assertEquals("client", config.target);
    }

    @Test
    void automaticTargetRejectsUnknownSide() throws Exception {
        Path configFile = temporary.resolve("loader.json");
        Files.write(configFile, ("{\"api\":\"https://example.com/api/\"," +
            "\"modpack\":\"pack\"}").getBytes(StandardCharsets.UTF_8));
        LoaderConfig config = LoaderConfig.load(configFile);

        assertThrows(LoaderException.class, () -> config.resolveTarget(null));
    }

    @Test
    void rejectsRemotePlainHttp() throws Exception {
        Path configFile = temporary.resolve("loader.json");
        Files.write(configFile, ("{\"api\":\"http://example.com/api/\"," +
            "\"modpack\":\"pack\"}").getBytes(StandardCharsets.UTF_8));

        assertThrows(LoaderException.class, () -> LoaderConfig.load(configFile));
    }

    @Test
    void rejectsLimitsAboveHardCeiling() throws Exception {
        Path configFile = temporary.resolve("loader.json");
        Files.write(configFile, ("{\"api\":\"https://example.com/api/\"," +
            "\"modpack\":\"pack\",\"limits\":{\"maxArchiveEntries\":100001}}")
            .getBytes(StandardCharsets.UTF_8));

        assertThrows(LoaderException.class, () -> LoaderConfig.load(configFile));
    }

    @Test
    void rejectsExcessiveDownloadConcurrency() throws Exception {
        Path configFile = temporary.resolve("loader.json");
        Files.write(configFile, ("{\"api\":\"https://example.com/api/\"," +
            "\"modpack\":\"pack\",\"limits\":{\"maxConcurrentDownloads\":17}}")
            .getBytes(StandardCharsets.UTF_8));

        assertThrows(LoaderException.class, () -> LoaderConfig.load(configFile));
    }

    @Test
    void rejectsExcessiveExtractionConcurrency() throws Exception {
        Path configFile = temporary.resolve("loader.json");
        Files.write(configFile, ("{\"api\":\"https://example.com/api/\"," +
            "\"modpack\":\"pack\",\"limits\":{\"maxConcurrentExtractions\":17}}")
            .getBytes(StandardCharsets.UTF_8));

        assertThrows(LoaderException.class, () -> LoaderConfig.load(configFile));
    }

    @Test
    void rejectsFileBasedSelections() throws Exception {
        Path configFile = temporary.resolve("loader.json");
        Files.write(configFile, ("{\"api\":\"https://example.com/api/\"," +
            "\"modpack\":\"pack\",\"selections\":{\"optional\":[\"map\"]}}")
            .getBytes(StandardCharsets.UTF_8));

        assertThrows(LoaderException.class, () -> LoaderConfig.load(configFile));
    }
}
