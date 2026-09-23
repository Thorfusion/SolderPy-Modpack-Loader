package io.github.thorfusion.solderpyloader;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallerTest {
    @TempDir Path gameDirectory;

    @Test
    void removesOnlyPreviouslyOwnedUnselectedFiles() throws Exception {
        Path owned = gameDirectory.resolve("mods/old.jar");
        Path userFile = gameDirectory.resolve("mods/user.jar");
        Files.createDirectories(owned.getParent());
        Files.write(owned, "old".getBytes(StandardCharsets.UTF_8));
        Files.write(userFile, "user".getBytes(StandardCharsets.UTF_8));

        InstalledState previous = new InstalledState();
        Map<String, String> hashes = new LinkedHashMap<String, String>();
        hashes.put("mods/old.jar", "149603e6c03516362a8da23f624db945");
        previous.receipts.put("old", new InstalledState.Receipt(
            "old", "1.0", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "jar:mods/old.jar",
            Collections.singletonList("mods/old.jar"), hashes));
        InstalledState next = new InstalledState();
        Path data = gameDirectory.resolve(".solderpy-loader");

        new Installer(gameDirectory, data, new LoaderConfig.Limits())
            .reconcile(Collections.<BootstrapManifest.Package>emptyList(), previous, next);

        assertFalse(Files.exists(owned));
        assertTrue(Files.isRegularFile(userFile));
        assertTrue(Files.isRegularFile(data.resolve("state.json")));
    }

    @Test
    void preservesLocallyModifiedFormerOutput() throws Exception {
        Path owned = gameDirectory.resolve("mods/old.jar");
        Files.createDirectories(owned.getParent());
        Files.write(owned, "changed".getBytes(StandardCharsets.UTF_8));

        Map<String, String> hashes = new LinkedHashMap<String, String>();
        hashes.put("mods/old.jar", "149603e6c03516362a8da23f624db945");
        InstalledState previous = new InstalledState();
        previous.receipts.put("old", new InstalledState.Receipt(
            "old", "1.0", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "jar:mods/old.jar", Collections.singletonList("mods/old.jar"), hashes));

        new Installer(gameDirectory, gameDirectory.resolve(".solderpy-loader"),
            new LoaderConfig.Limits()).reconcile(
                Collections.<BootstrapManifest.Package>emptyList(), previous,
                new InstalledState());

        assertTrue(Files.isRegularFile(owned));
    }

    @Test
    void relinquishesFilesWhenLauncherTakesOwnership() throws Exception {
        Path owned = gameDirectory.resolve("mods/old.jar");
        Files.createDirectories(owned.getParent());
        Files.write(owned, "old".getBytes(StandardCharsets.UTF_8));

        Map<String, String> hashes = new LinkedHashMap<String, String>();
        hashes.put("mods/old.jar", "149603e6c03516362a8da23f624db945");
        InstalledState previous = new InstalledState();
        previous.receipts.put("old", new InstalledState.Receipt(
            "old", "1.0", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "jar:mods/old.jar", Collections.singletonList("mods/old.jar"), hashes));
        BootstrapManifest.Package launcherOwned = new BootstrapManifest.Package();
        launcherOwned.name = "old";
        launcherOwned.installOwner = "launcher";
        launcherOwned.bootstrapManaged = false;
        InstalledState next = new InstalledState();

        new Installer(gameDirectory, gameDirectory.resolve(".solderpy-loader"),
            new LoaderConfig.Limits()).reconcile(
                Collections.<BootstrapManifest.Package>emptyList(),
                Collections.singletonList(launcherOwned), previous,
                next);

        assertTrue(Files.isRegularFile(owned));
        assertTrue(next.receipts.isEmpty());
    }

    @Test
    void updateRemovesOmittedOutputWhenEnforcementIsEnabled() throws Exception {
        verifyUpdatedPackageOmission(true);
    }

    @Test
    void updateRemovesOmittedOutputWhenEnforcementIsDisabled() throws Exception {
        verifyUpdatedPackageOmission(false);
    }

    @Test
    void leavesUserChangesAloneBetweenUpdatesWhenEnforcementIsDisabled()
        throws Exception {

        byte[] userContents = "user-edited".getBytes(StandardCharsets.UTF_8);
        Path edited = gameDirectory.resolve("config/editable.cfg");
        Path removed = gameDirectory.resolve("config/removable.cfg");
        Files.createDirectories(edited.getParent());
        Files.write(edited, userContents);

        Map<String, String> hashes = new LinkedHashMap<String, String>();
        hashes.put("config/editable.cfg", md5("packaged".getBytes(StandardCharsets.UTF_8)));
        hashes.put("config/removable.cfg", md5("packaged".getBytes(StandardCharsets.UTF_8)));
        InstalledState previous = new InstalledState();
        previous.receipts.put("config-pack", new InstalledState.Receipt(
            "config-pack", "2.0.0", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "solder_zip:config",
            java.util.Arrays.asList("config/editable.cfg", "config/removable.cfg"), hashes));

        BootstrapManifest.Package item = new BootstrapManifest.Package();
        item.name = "config-pack";
        item.version = "2.0.0";
        item.bootstrapManaged = true;
        item.enforce = Boolean.FALSE;
        item.download = new BootstrapManifest.Download();
        item.download.url = "http://127.0.0.1:1/must-not-download.zip";
        item.download.md5 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        item.download.format = "solder_zip";
        item.download.extractTo = "config";

        Installer installer = new Installer(
            gameDirectory, gameDirectory.resolve(".solderpy-loader"),
            new LoaderConfig.Limits());
        assertTrue(installer.isInstalledStateIntact(
            Collections.singletonList(item), previous));

        installer.reconcile(
            Collections.singletonList(item), previous, new InstalledState());

        assertArrayEquals(userContents, Files.readAllBytes(edited));
        assertFalse(Files.exists(removed));
    }

    private void verifyUpdatedPackageOmission(boolean enforce)
        throws Exception {

        byte[] oldCurrent = "old-current".getBytes(StandardCharsets.UTF_8);
        byte[] oldOmitted = "old-omitted".getBytes(StandardCharsets.UTF_8);
        byte[] newCurrent = "new-current".getBytes(StandardCharsets.UTF_8);
        Path current = gameDirectory.resolve("config/current.cfg");
        Path omitted = gameDirectory.resolve("config/omitted.cfg");
        Files.createDirectories(current.getParent());
        Files.write(current, oldCurrent);
        Files.write(omitted, oldOmitted);

        Map<String, String> oldHashes = new LinkedHashMap<String, String>();
        oldHashes.put("config/current.cfg", md5(oldCurrent));
        oldHashes.put("config/omitted.cfg", md5(oldOmitted));
        InstalledState previous = new InstalledState();
        previous.receipts.put("config-pack", new InstalledState.Receipt(
            "config-pack", "2.0.0", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "solder_zip:config",
            java.util.Arrays.asList("config/current.cfg", "config/omitted.cfg"),
            oldHashes));

        byte[] archive = zip("current.cfg", newCurrent);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/config-pack.zip", exchange -> send(exchange, archive));
        server.start();
        try {
            BootstrapManifest.Package item = new BootstrapManifest.Package();
            item.name = "config-pack";
            item.version = "2.0.1";
            item.bootstrapManaged = true;
            item.enforce = Boolean.valueOf(enforce);
            item.download = new BootstrapManifest.Download();
            item.download.url = "http://127.0.0.1:" + server.getAddress().getPort() +
                "/config-pack.zip";
            item.download.md5 = md5(archive);
            item.download.filesize = Long.valueOf(archive.length);
            item.download.format = "solder_zip";
            item.download.extractTo = "config";

            InstalledState next = new InstalledState();
            new Installer(gameDirectory, gameDirectory.resolve(".solderpy-loader"),
                new LoaderConfig.Limits()).reconcile(
                    Collections.singletonList(item), previous, next);

            assertArrayEquals(newCurrent, Files.readAllBytes(current));
            assertFalse(Files.exists(omitted));
            assertEquals(Collections.singletonList("config/current.cfg"),
                next.receipts.get("config-pack").files);
        } finally {
            server.stop(0);
        }
    }

    private static byte[] zip(String path, byte[] contents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            output.putNextEntry(new ZipEntry(path));
            output.write(contents);
            output.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static void send(HttpExchange exchange, byte[] body) throws IOException {
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static String md5(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(bytes);
        StringBuilder value = new StringBuilder();
        for (byte item : digest) {
            value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        }
        return value.toString();
    }
}
