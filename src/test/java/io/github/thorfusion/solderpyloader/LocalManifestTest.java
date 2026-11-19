package io.github.thorfusion.solderpyloader;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalManifestTest {
    @TempDir Path game;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void localManifestUpdatesDownloadOnlyChangedModsAndRepairFromCache(boolean inline)
        throws Exception {

        byte[] unchanged = "unchanged mod".getBytes(StandardCharsets.UTF_8);
        byte[] original = "original changing mod".getBytes(StandardCharsets.UTF_8);
        byte[] updated = "updated changing mod".getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> artifacts = new ConcurrentHashMap<String, byte[]>();
        Map<String, AtomicInteger> requests = new ConcurrentHashMap<String, AtomicInteger>();
        artifacts.put("/unchanged.jar", unchanged);
        artifacts.put("/changing.jar", original);
        requests.put("/unchanged.jar", new AtomicInteger());
        requests.put("/changing.jar", new AtomicInteger());
        AtomicInteger unexpectedRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] content = artifacts.get(path);
            if (content == null) {
                unexpectedRequests.incrementAndGet();
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            requests.get(path).incrementAndGet();
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(content);
            }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            BootstrapManifest manifest = manifest();
            manifest.packages.add(rawJar(1, "unchanged", baseUrl, unchanged));
            manifest.packages.add(rawJar(2, "changing", baseUrl, original));
            manifest.selectionPolicy.requiredMemberships.add(1L);
            manifest.selectionPolicy.requiredMemberships.add(2L);
            new BootstrapEngine(paths(), localConfig(manifest, inline)).run();
            assertEquals(1, requests.get("/unchanged.jar").get());
            assertEquals(1, requests.get("/changing.jar").get());
            FileTime unchangedTimestamp = Files.getLastModifiedTime(game.resolve("mods/unchanged.jar"));
            artifacts.put("/changing.jar", updated);
            BootstrapManifest.Package changed = rawJar(2, "changing", baseUrl, updated);
            changed.version = "2.0";
            manifest.packages.set(1, changed);
            manifest.build.version = "2.0";
            LoaderConfig config = localConfig(manifest, inline);
            new BootstrapEngine(paths(), config).run();

            assertEquals(1, requests.get("/unchanged.jar").get());
            assertEquals(2, requests.get("/changing.jar").get());
            assertEquals(unchangedTimestamp, Files.getLastModifiedTime(game.resolve("mods/unchanged.jar")));
            assertArrayEquals(unchanged, Files.readAllBytes(game.resolve("mods/unchanged.jar")));
            assertArrayEquals(updated, Files.readAllBytes(game.resolve("mods/changing.jar")));

            // A deleted installed file is repaired from the verified artifact cache.
            Files.delete(game.resolve("mods/changing.jar"));
            new BootstrapEngine(paths(), config).run();
            assertArrayEquals(updated, Files.readAllBytes(game.resolve("mods/changing.jar")));
            assertEquals(1, requests.get("/unchanged.jar").get());
            assertEquals(2, requests.get("/changing.jar").get());
            assertEquals(0, unexpectedRequests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void universalBuildInstallsInlineManifestWithoutApiAndReusesDownloads() throws Exception {
        byte[] bytes = "verified local manifest download".getBytes(StandardCharsets.UTF_8);
        AtomicInteger fileRequests = new AtomicInteger();
        AtomicInteger unexpectedRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            boolean artifact = "/example.jar".equals(exchange.getRequestURI().getPath());
            if (artifact) {
                fileRequests.incrementAndGet();
            } else {
                unexpectedRequests.incrementAndGet();
            }
            exchange.sendResponseHeaders(artifact ? 200 : 500, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        try {
            BootstrapManifest manifest = manifest();
            BootstrapManifest.Package item = new BootstrapManifest.Package();
            item.membershipId = 1;
            item.name = "example";
            item.version = "1.0";
            item.installOwner = "loader";
            item.selection = new BootstrapManifest.Selection();
            item.download = new BootstrapManifest.Download();
            item.download.url = "http://127.0.0.1:" + server.getAddress().getPort() + "/example.jar";
            item.download.md5 = String.format(Locale.ROOT, "%032x",
                new BigInteger(1, MessageDigest.getInstance("MD5").digest(bytes)));
            item.download.filesize = (long) bytes.length;
            item.download.format = "jar";
            item.download.path = "mods/example.jar";
            manifest.packages.add(item);
            manifest.selectionPolicy.requiredMemberships.add(1L);
            LoaderConfig config = inlineConfig(manifest);
            RuntimePaths paths = paths();

            new BootstrapEngine(paths, config).run();
            new BootstrapEngine(paths, config).run();

            assertArrayEquals(bytes, Files.readAllBytes(game.resolve("mods/example.jar")));
            assertEquals(1, fileRequests.get());
            assertEquals(0, unexpectedRequests.get());
            InstalledState state = InstalledState.load(paths.dataDirectory());
            assertTrue(state.matches(config));
            assertNull(state.signedManifest);
            assertNull(state.etag);
            assertTrue(BootstrapWorker.hasIntactPreviousInstall(paths, config));

            // Cached state cannot replace the local manifest as the fail-open authority.
            manifest.packages.get(0).download.md5 = "00000000000000000000000000000000";
            LoaderConfig changed = inlineConfig(manifest);
            assertFalse(BootstrapWorker.hasIntactPreviousInstall(paths, changed));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void readsExternalManifestAndDetectsEditsWithAnUnchangedDeclaredHash() throws Exception {
        BootstrapManifest manifest = manifest();
        manifest.manifestHash = String.join("", Collections.nCopies(64, "a"));
        Path file = writeManifest("manifest.json", manifest);
        LoaderConfig config = fileConfig("manifest.json");
        LocalManifestClient client = new LocalManifestClient(game, config);

        BootstrapManifest before = client.read();
        manifest.modpack.name = "Updated name";
        Files.write(file, JsonSupport.GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
        BootstrapManifest after = client.read();

        assertNotEquals(before.manifestHash, after.manifestHash);
        assertEquals("Updated name", after.modpack.name);
        new BootstrapEngine(paths(), config).run();
        assertTrue(BootstrapWorker.hasIntactPreviousInstall(paths(), config));
        Files.delete(file);
        assertThrows(LoaderException.class, () -> new BootstrapEngine(paths(), config).run());
        assertFalse(BootstrapWorker.hasIntactPreviousInstall(paths(), config));
    }

    @Test
    void resolvesSideSpecificFilesAfterAutomaticSideDetection() throws Exception {
        writeManifest("server.json", manifest());
        LoaderConfig config = fileConfig("{target}.json");
        config.target = "auto";
        config.resolveTarget("server");

        assertEquals("server", new LocalManifestClient(game, config)
            .read().target);
    }

    @Test
    void rejectsAManifestForTheWrongPackOrSide() throws Exception {
        BootstrapManifest manifest = manifest();
        manifest.target = "client";
        LoaderConfig config = inlineConfig(manifest);
        assertThrows(LoaderException.class,
            () -> new LocalManifestClient(game, config).read());
        manifest.target = "server";
        manifest.modpack.slug = "another-pack";
        LoaderConfig wrongPack = inlineConfig(manifest);
        assertThrows(LoaderException.class,
            () -> new LocalManifestClient(game, wrongPack).read());
    }

    @Test
    void rejectsInvalidOrEmptyLocalJson() throws Exception {
        LoaderConfig config = fileConfig("invalid.json");
        Path file = game.resolve(config.manifestFile);
        Files.createDirectories(file.getParent());
        for (String value : new String[] {"{", "[]", "null", "{}"}) {
            Files.write(file, value.getBytes(StandardCharsets.UTF_8));
            assertThrows(LoaderException.class,
                () -> new LocalManifestClient(game, config).read());
        }
    }

    @Test
    void honorsAnOptionalPinnedSignatureForALocalManifest() throws Exception {
        KeyPair keys = ManifestSigningTestSupport.keyPair();
        JsonObject document = ManifestSigningTestSupport.sign(
            JsonSupport.GSON.toJsonTree(manifest()).getAsJsonObject(), keys);
        JsonObject root = baseConfig();
        root.add("manifest", document);
        root.add("manifestVerification", JsonSupport.GSON.fromJson(
            ManifestSigningTestSupport.configurationJson(keys), JsonObject.class));
        LoaderConfig config = writeConfig(root);
        new LocalManifestClient(game, config).read();

        config.manifest.getAsJsonObject("build").addProperty("version", "tampered");
        assertThrows(LoaderException.class,
            () -> new LocalManifestClient(game, config).read());
        config.manifest.remove("signature");
        assertThrows(LoaderException.class,
            () -> new LocalManifestClient(game, config).read());
    }

    @Test
    void aPackageCannotReplaceTheLocalManifest() throws Exception {
        BootstrapManifest.Package item = new BootstrapManifest.Package();
        item.membershipId = 1;
        item.name = "self-update";
        item.version = "1.0";
        item.bootstrapManaged = true;
        item.download = new BootstrapManifest.Download();
        item.download.format = "jar";
        item.download.path = "config/solderpy-loader/manifest.json";
        item.download.md5 = "00000000000000000000000000000000";
        Installer installer = new Installer(game, paths().dataDirectory(), new LoaderConfig.Limits());

        LoaderException error = assertThrows(LoaderException.class, () -> installer.reconcile(
            Collections.singletonList(item), new InstalledState(), new InstalledState()));
        assertTrue(error.getMessage().contains("reserved loader path"));
    }

    private BootstrapManifest manifest() {
        BootstrapManifest manifest = new BootstrapManifest();
        manifest.schema = "solder.py/bootstrap";
        manifest.schemaVersion = 1;
        manifest.modpack = new BootstrapManifest.Modpack();
        manifest.modpack.slug = "pack";
        manifest.modpack.name = "Pack";
        manifest.build = new BootstrapManifest.Build();
        manifest.build.version = "1.0";
        manifest.target = "server";
        manifest.source = "hybrid";
        manifest.optionalMode = new BootstrapManifest.OptionalMode();
        manifest.optionalMode.name = "basic";
        manifest.selectionPolicy = new BootstrapManifest.SelectionPolicy();
        return manifest;
    }

    private JsonObject baseConfig() {
        JsonObject root = new JsonObject();
        root.addProperty("modpack", "pack");
        root.addProperty("target", "server");
        return root;
    }

    private BootstrapManifest.Package rawJar(long id, String name, String baseUrl, byte[] content)
        throws Exception {

        BootstrapManifest.Package item = new BootstrapManifest.Package();
        item.membershipId = id;
        item.name = name;
        item.version = "1.0";
        item.installOwner = "loader";
        item.selection = new BootstrapManifest.Selection();
        item.download = new BootstrapManifest.Download();
        item.download.url = baseUrl + "/" + name + ".jar";
        item.download.md5 = String.format(Locale.ROOT, "%032x",
            new BigInteger(1, MessageDigest.getInstance("MD5").digest(content)));
        item.download.filesize = (long) content.length;
        item.download.format = "jar";
        item.download.path = "mods/" + name + ".jar";
        return item;
    }

    private LoaderConfig localConfig(BootstrapManifest manifest, boolean inline) throws Exception {
        if (inline) {
            return inlineConfig(manifest);
        }
        writeManifest("manifest.json", manifest);
        return fileConfig("manifest.json");
    }

    private LoaderConfig inlineConfig(BootstrapManifest manifest) throws Exception {
        JsonObject root = baseConfig();
        root.add("manifest", JsonSupport.GSON.toJsonTree(manifest));
        return writeConfig(root);
    }

    private LoaderConfig fileConfig(String filename) throws Exception {
        JsonObject root = baseConfig();
        root.addProperty("manifestFile", "config/solderpy-loader/" + filename);
        return writeConfig(root);
    }

    private LoaderConfig writeConfig(JsonObject root) throws Exception {
        Path file = Files.createDirectories(game.resolve("config")).resolve("solderpy-loader.json");
        Files.write(file, JsonSupport.GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
        return LoaderConfig.load(file);
    }

    private Path writeManifest(String filename, BootstrapManifest manifest) throws Exception {
        Path file = Files.createDirectories(game.resolve("config/solderpy-loader")).resolve(filename);
        Files.write(file, JsonSupport.GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private RuntimePaths paths() {
        return new RuntimePaths(game, game.resolve("mods/loader.jar"));
    }
}
