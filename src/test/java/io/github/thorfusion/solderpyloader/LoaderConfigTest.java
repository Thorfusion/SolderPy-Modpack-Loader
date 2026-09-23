package io.github.thorfusion.solderpyloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals("hybrid", config.source);
        assertEquals(null, config.platform);
        assertEquals(25, config.bootstrapJavaMajor);
        assertEquals(4, config.limits.maxConcurrentDownloads);
        assertEquals(1, config.limits.maxConcurrentExtractions);
        assertTrue(config.enabled);
    }

    @Test
    void acceptsExportSourceAndPlatform() throws Exception {
        Path configFile = temporary.resolve("loader.json");
        Files.write(configFile, ("{\"api\":\"https://example.com/api/\"," +
            "\"modpack\":\"pack\",\"source\":\"solder\"," +
            "\"platform\":\"modrinth\"}").getBytes(StandardCharsets.UTF_8));

        LoaderConfig config = LoaderConfig.load(configFile);

        assertEquals("solder", config.source);
        assertEquals("modrinth", config.platform);
    }

    @Test
    void acceptsPinnedManifestVerificationFromAnExport() throws Exception {
        java.security.KeyPair keys = ManifestSigningTestSupport.keyPair();
        Path configFile = temporary.resolve("signed-loader.json");
        Files.write(configFile, ("{\"api\":\"https://example.com/api/\"," +
            "\"modpack\":\"pack\",\"manifestVerification\":" +
            ManifestSigningTestSupport.configurationJson(keys) + "}")
            .getBytes(StandardCharsets.UTF_8));

        LoaderConfig config = LoaderConfig.load(configFile);

        assertTrue(config.manifestVerification.required);
        assertEquals("SHA256withECDSA", config.manifestVerification.algorithm);
        assertTrue(config.manifestVerification.parsedPublicKey != null);
    }

    @Test
    void rejectsManifestVerificationWithAMismatchedKeyId() throws Exception {
        java.security.KeyPair keys = ManifestSigningTestSupport.keyPair();
        String verification = ManifestSigningTestSupport.configurationJson(keys)
            .replaceFirst("sha256:[0-9a-f]{64}", "sha256:" + repeat('0', 64));
        Path configFile = temporary.resolve("bad-signed-loader.json");
        Files.write(configFile, ("{\"api\":\"https://example.com/api/\"," +
            "\"modpack\":\"pack\",\"manifestVerification\":" +
            verification + "}").getBytes(StandardCharsets.UTF_8));

        LoaderException error = assertThrows(
            LoaderException.class, () -> LoaderConfig.load(configFile));

        assertTrue(error.getMessage().contains("keyId does not match"));
    }

    @Test
    void acceptsAndNormalizesExplicitLauncherOwnership() throws Exception {
        Path configFile = temporary.resolve("loader.json");
        Files.write(configFile, ("{\"api\":\"https://example.com/api/\"," +
            "\"modpack\":\"pack\",\"platform\":\"modrinth\"," +
            "\"launcherOwnedMemberships\":[9,2,9]}")
            .getBytes(StandardCharsets.UTF_8));

        LoaderConfig config = LoaderConfig.load(configFile);

        assertEquals(java.util.Arrays.asList(2L, 9L),
            config.launcherOwnedMemberships);
    }

    @Test
    void rejectsInvalidExplicitLauncherOwnership() throws Exception {
        Path configFile = temporary.resolve("loader.json");
        Files.write(configFile, ("{\"api\":\"https://example.com/api/\"," +
            "\"modpack\":\"pack\",\"launcherOwnedMemberships\":[0]}")
            .getBytes(StandardCharsets.UTF_8));

        assertThrows(LoaderException.class, () -> LoaderConfig.load(configFile));
    }

    @Test
    void acceptsTechnicAsNativeDeliveryPlatform() throws Exception {
        Path configFile = temporary.resolve("loader.json");
        Files.write(configFile, ("{\"api\":\"https://example.com/api/\"," +
            "\"modpack\":\"pack\",\"platform\":\"technic\"}")
            .getBytes(StandardCharsets.UTF_8));

        LoaderConfig config = LoaderConfig.load(configFile);

        assertEquals("technic", config.platform);
    }

    @Test
    void rejectsUnknownExportSource() throws Exception {
        Path configFile = temporary.resolve("loader.json");
        Files.write(configFile, ("{\"api\":\"https://example.com/api/\"," +
            "\"modpack\":\"pack\",\"source\":\"mirror\"}")
            .getBytes(StandardCharsets.UTF_8));

        assertThrows(LoaderException.class, () -> LoaderConfig.load(configFile));
    }

    @Test
    void rejectsUnknownNativeDeliveryPlatform() throws Exception {
        Path configFile = temporary.resolve("loader.json");
        Files.write(configFile, ("{\"api\":\"https://example.com/api/\"," +
            "\"modpack\":\"pack\",\"platform\":\"unknown\"}")
            .getBytes(StandardCharsets.UTF_8));

        assertThrows(LoaderException.class, () -> LoaderConfig.load(configFile));
    }

    @Test
    void cachedStateMustMatchTheConfiguredSourceAndPlatform() throws Exception {
        Path configFile = temporary.resolve("loader.json");
        Files.write(configFile, ("{\"api\":\"https://example.com/api/\"," +
            "\"modpack\":\"pack\",\"target\":\"client\"," +
            "\"source\":\"hybrid\",\"platform\":\"technic\"}")
            .getBytes(StandardCharsets.UTF_8));
        LoaderConfig config = LoaderConfig.load(configFile);

        InstalledState state = new InstalledState();
        state.api = config.api;
        state.modpack = config.modpack;
        state.target = config.target;
        state.source = config.source;
        state.platform = config.platform;
        state.manifest = new BootstrapManifest();
        state.manifest.source = "hybrid";

        assertTrue(state.matches(config));
        state.manifest.source = "solder";
        assertFalse(state.matches(config));
    }

    @Test
    void cachedStateMustMatchExplicitLauncherOwnership() throws Exception {
        Path configFile = temporary.resolve("loader.json");
        Files.write(configFile, ("{\"api\":\"https://example.com/api/\"," +
            "\"modpack\":\"pack\",\"target\":\"client\"," +
            "\"launcherOwnedMemberships\":[2,9]}")
            .getBytes(StandardCharsets.UTF_8));
        LoaderConfig config = LoaderConfig.load(configFile);

        InstalledState state = new InstalledState();
        state.api = config.api;
        state.modpack = config.modpack;
        state.target = config.target;
        state.source = config.source;
        state.platform = config.platform;
        state.manifest = new BootstrapManifest();
        state.manifest.source = config.source;
        state.launcherOwnedMemberships = java.util.Arrays.asList(2L, 9L);

        assertTrue(state.matches(config));
        state.launcherOwnedMemberships = java.util.Collections.singletonList(2L);
        assertFalse(state.matches(config));
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

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }
}
