package io.github.thorfusion.solderpyloader;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootstrapIntegrationTest {
    @TempDir Path gameDirectory;

    @Test
    void fetchesVerifiesAndCommitsRawJar() throws Exception {
        byte[] jarBytes = "not-a-real-jar-but-verified".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        BootstrapManifest manifest = manifest(port, jarBytes);
        byte[] manifestBytes = JsonSupport.GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8);
        FixtureHandler fixture = new FixtureHandler(jarBytes, manifestBytes);
        server.createContext("/", fixture);
        server.start();
        try {
            Path configFile = gameDirectory.resolve("loader.json");
            String configJson = "{\"api\":\"http://127.0.0.1:" + port +
                "/api/\",\"modpack\":\"pack\",\"source\":\"hybrid\"," +
                "\"platform\":\"modrinth\"," +
                "\"launcherOwnedMemberships\":[2]}";
            Files.write(configFile, configJson.getBytes(StandardCharsets.UTF_8));
            LoaderConfig config = LoaderConfig.load(configFile);
            config.resolveTarget("client");
            BootstrapClient client = new BootstrapClient(config);

            client.verifyCapability();
            BootstrapClient.ManifestResponse response = client.fetchManifest(null, null);
            assertEquals("1.0", response.manifest.build.version);
            assertEquals("\"fixture-etag\"", response.etag);
            assertEquals(
                "target=client&source=hybrid&platform=modrinth&ownership=explicit",
                fixture.manifestQuery);

            InstalledState next = new InstalledState();
            Path data = gameDirectory.resolve(".solderpy-loader");
            Installer installer = new Installer(gameDirectory, data, config.limits);
            installer.reconcile(
                Collections.singletonList(response.manifest.packages.get(0)),
                new InstalledState(), next);

            Path installedJar = gameDirectory.resolve("mods/example-1.0.jar");
            assertArrayEquals(jarBytes, Files.readAllBytes(installedJar));
            assertTrue(Files.isRegularFile(data.resolve("state.json")));

            FileTime existingTimestamp = FileTime.fromMillis(1_700_000_000_000L);
            Files.setLastModifiedTime(installedJar, existingTimestamp);
            installer.reconcile(
                Collections.singletonList(response.manifest.packages.get(0)),
                new InstalledState(), new InstalledState());
            assertEquals(existingTimestamp, Files.getLastModifiedTime(installedJar));
            assertArrayEquals(jarBytes, Files.readAllBytes(installedJar));

            InstalledState installed = InstalledState.load(data);
            assertFalse(installed.receipts.get("example").hashes.isEmpty());
            assertEquals(md5(jarBytes),
                installed.receipts.get("example").hashes.get("mods/example-1.0.jar"));
            assertTrue(installer.isInstalledStateIntact(
                Collections.singletonList(response.manifest.packages.get(0)), installed));
            int downloadsBeforeReuse = fixture.fileRequests.get();
            installer.reconcile(
                Collections.singletonList(response.manifest.packages.get(0)),
                installed, new InstalledState());
            assertEquals(downloadsBeforeReuse, fixture.fileRequests.get());
            assertEquals(existingTimestamp, Files.getLastModifiedTime(installedJar));

            Files.write(installedJar, "locally-modified".getBytes(StandardCharsets.UTF_8));
            assertFalse(installer.isInstalledStateIntact(
                Collections.singletonList(response.manifest.packages.get(0)), installed));

            installer.reconcile(
                Collections.singletonList(response.manifest.packages.get(0)),
                installed, new InstalledState());

            assertArrayEquals(jarBytes, Files.readAllBytes(installedJar));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsAManifestForADifferentDownloadSource() throws Exception {
        BootstrapManifest manifest = manifest(1234, new byte[] {1});
        manifest.source = "solder";

        assertThrows(LoaderException.class,
            () -> manifest.validate("pack", "client", "hybrid"));
    }

    @Test
    void treatsAnOlderSchemaOneManifestAsHybrid() throws Exception {
        BootstrapManifest manifest = manifest(1234, new byte[] {1});
        manifest.source = null;

        manifest.validate("pack", "client", "hybrid");

        assertEquals("hybrid", manifest.source);
    }

    @Test
    void rejectsAnUnknownPackageInstallOwner() throws Exception {
        BootstrapManifest manifest = manifest(1234, new byte[] {1});
        manifest.packages.get(0).installOwner = "someone-else";

        assertThrows(LoaderException.class,
            () -> manifest.validate("pack", "client", "hybrid"));
    }

    @Test
    void derivesInstallOwnerForAnOlderSchemaOneManifest() throws Exception {
        BootstrapManifest manifest = manifest(1234, new byte[] {1});
        manifest.packages.get(0).installOwner = null;

        manifest.validate("pack", "client", "hybrid");

        assertEquals("loader", manifest.packages.get(0).installOwner);
        assertTrue(manifest.packages.get(0).bootstrapManaged);
    }

    private static BootstrapManifest manifest(int port, byte[] jarBytes) throws Exception {
        BootstrapManifest manifest = new BootstrapManifest();
        manifest.schema = "solder.py/bootstrap";
        manifest.schemaVersion = 1;
        manifest.modpack = new BootstrapManifest.Modpack();
        manifest.modpack.slug = "pack";
        manifest.modpack.name = "Pack";
        manifest.build = new BootstrapManifest.Build();
        manifest.build.version = "1.0";
        manifest.target = "client";
        manifest.source = "hybrid";
        manifest.optionalMode = new BootstrapManifest.OptionalMode();
        manifest.optionalMode.name = "basic";
        manifest.selectionPolicy = new BootstrapManifest.SelectionPolicy();
        manifest.manifestHash = repeat('a', 64);

        BootstrapManifest.Package item = new BootstrapManifest.Package();
        item.id = 1;
        item.membershipId = 2;
        item.name = "example";
        item.version = "1.0";
        item.bootstrapManaged = true;
        item.selection = new BootstrapManifest.Selection();
        item.selection.state = 0;
        item.download = new BootstrapManifest.Download();
        item.download.url = "http://127.0.0.1:" + port + "/files/example.jar";
        item.download.md5 = md5(jarBytes);
        item.download.filesize = (long) jarBytes.length;
        item.download.format = "jar";
        item.download.path = "mods/example-1.0.jar";
        manifest.packages = Collections.singletonList(item);
        return manifest;
    }

    private static String md5(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(bytes);
        StringBuilder value = new StringBuilder();
        for (byte item : digest) {
            value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        }
        return value.toString();
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            result.append(value);
        }
        return result.toString();
    }

    private static final class FixtureHandler implements HttpHandler {
        private final byte[] jar;
        private final byte[] manifest;
        private final AtomicInteger fileRequests = new AtomicInteger();
        private String manifestQuery;

        private FixtureHandler(byte[] jar, byte[] manifest) {
            this.jar = jar;
            this.manifest = manifest;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("/api/".equals(path)) {
                send(exchange, 200, "application/json",
                    "{\"capabilities\":{\"bootstrap_manifest\":true,\"bootstrap_schema\":1}}"
                        .getBytes(StandardCharsets.UTF_8));
            } else if ("/api/modpack/pack/recommended/bootstrap".equals(path)) {
                manifestQuery = exchange.getRequestURI().getRawQuery();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("ETag", "\"fixture-etag\"");
                send(exchange, 200, "application/json", manifest);
            } else if ("/files/example.jar".equals(path)) {
                fileRequests.incrementAndGet();
                send(exchange, 200, "application/octet-stream", jar);
            } else {
                send(exchange, 404, "text/plain", "missing".getBytes(StandardCharsets.UTF_8));
            }
        }

        private static void send(HttpExchange exchange, int status, String type, byte[] body)
            throws IOException {
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }
    }
}
