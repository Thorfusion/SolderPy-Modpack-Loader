package io.github.thorfusion.solderpyloader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** Runs against the shaded file-only JAR without the main classes on the classpath. */
public final class FileOnlyArtifactProbe {
    private FileOnlyArtifactProbe() {
    }

    public static void main(String[] args) throws Exception {
        for (String name : new String[] {"BootstrapClient", "BootstrapClient$ManifestResponse",
            "ManifestVerifier", "ManifestSource$ManifestResponse"}) {
            try {
                Class.forName("io.github.thorfusion.solderpyloader." + name);
                throw new AssertionError("The file-only JAR contains API implementation: " + name);
            } catch (ClassNotFoundException expected) {
                // API transport, responses, and signature verification belong to the universal JAR.
            }
        }

        Path game = Files.createTempDirectory("solderpy-file-only-probe-");
        try {
            Path configFile = Files.createDirectories(game.resolve("config"))
                .resolve("solderpy-loader.json");
            Files.write(configFile, ("{\"api\":\"https://example.com/api/\",\"modpack\":\"pack\"}")
                .getBytes(StandardCharsets.UTF_8));
            try {
                LoaderConfig.load(configFile);
                throw new AssertionError("The file-only JAR accepted API configuration");
            } catch (LoaderException expected) {
                if (!expected.getMessage().contains("file-only")) {
                    throw expected;
                }
            }

            String manifest = "{\"schema\":\"solder.py/bootstrap\",\"schema_version\":1," +
                "\"modpack\":{\"slug\":\"pack\",\"name\":\"Pack\"}," +
                "\"build\":{\"version\":\"1.0\"},\"target\":\"server\",\"source\":\"hybrid\"," +
                "\"optional_mode\":{\"name\":\"basic\"}," +
                "\"selection_policy\":{\"required_memberships\":[],\"default_memberships\":[]}," +
                "\"groups\":[],\"packages\":[]}";
            RuntimePaths paths = new RuntimePaths(game, game.resolve("mods/loader.jar"));
            Files.write(configFile, ("{\"modpack\":\"pack\",\"target\":\"server\",\"manifest\":" +
                manifest + "}").getBytes(StandardCharsets.UTF_8));
            new BootstrapEngine(paths, LoaderConfig.load(configFile)).run();

            Files.write(configFile, ("{\"modpack\":\"pack\",\"manifestVerification\":{},\"manifest\":" +
                manifest + "}").getBytes(StandardCharsets.UTF_8));
            try {
                LoaderConfig.load(configFile);
                throw new AssertionError("The file-only JAR accepted API signature configuration");
            } catch (LoaderException expected) {
                if (!expected.getMessage().contains("file-only")) {
                    throw expected;
                }
            }

            Path manifestFile = Files.createDirectories(game.resolve("config/solderpy-loader"))
                .resolve("manifest.json");
            Files.write(manifestFile, manifest.getBytes(StandardCharsets.UTF_8));
            Files.write(configFile, ("{\"modpack\":\"pack\",\"target\":\"server\"," +
                "\"manifestFile\":\"config/solderpy-loader/manifest.json\"}")
                .getBytes(StandardCharsets.UTF_8));
            LoaderConfig config = LoaderConfig.load(configFile);
            new BootstrapEngine(paths, config).run();
            if (!InstalledState.load(paths.dataDirectory()).matches(config)) {
                throw new AssertionError("The packaged file-only loader did not save matching state");
            }
        } finally {
            try (Stream<Path> files = Files.walk(game)) {
                for (Path file : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }
}
