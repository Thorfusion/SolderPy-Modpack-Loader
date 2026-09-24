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
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        BootstrapManifest.Package launcherOwned = jarPackage(
            "old", "1.0", "mods/provider-name.jar", hashes.get("mods/old.jar"));
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

    @Test
    void strictBuildUpdateRemovesUnlistedModsButPreservesResolvedAndRuntimeFiles()
        throws Exception {

        Path mods = Files.createDirectories(gameDirectory.resolve("mods"));
        byte[] selectedBytes = "selected".getBytes(StandardCharsets.UTF_8);
        Path selectedFile = mods.resolve("selected.jar");
        Path unlistedFile = mods.resolve("user-added.jar");
        Path launcherFile = mods.resolve("native-launcher-owned.jar");
        Path unlistedDifferentBytes = mods.resolve("another-name.jar");
        Path relauncherFile = mods.resolve("!relauncher.jar");
        Path loaderFile = mods.resolve("solderpy-loader.jar");
        Files.write(selectedFile, selectedBytes);
        Files.write(unlistedFile, "remove".getBytes(StandardCharsets.UTF_8));
        Files.write(launcherFile, "native".getBytes(StandardCharsets.UTF_8));
        Files.write(unlistedDifferentBytes, "wrong bytes".getBytes(StandardCharsets.UTF_8));
        Files.write(relauncherFile, "runtime".getBytes(StandardCharsets.UTF_8));
        Files.write(loaderFile, "loader".getBytes(StandardCharsets.UTF_8));

        BootstrapManifest.Package selected = jarPackage(
            "selected", "1.0", "mods/selected.jar", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        selected.membershipId = 1L;
        BootstrapManifest.Package launcher = jarPackage(
            "native", "1.0", "mods/solder-canonical-name.jar",
            md5("native".getBytes(StandardCharsets.UTF_8)));
        launcher.membershipId = 2L;
        launcher.bootstrapManaged = false;
        launcher.installOwner = "launcher";

        InstalledState previous = new InstalledState();
        previous.build = "1.0";
        Map<String, String> hashes = new LinkedHashMap<String, String>();
        hashes.put("mods/selected.jar", md5(selectedBytes));
        previous.receipts.put("selected", new InstalledState.Receipt(
            "selected", "1.0", selected.download.md5,
            "jar:mods/selected.jar", Collections.singletonList("mods/selected.jar"), hashes));
        InstalledState next = new InstalledState();
        next.build = "2.0";

        new Installer(gameDirectory, gameDirectory.resolve(".solderpy-loader"),
            new LoaderConfig.Limits(), loaderFile).reconcile(
                Collections.singletonList(selected),
                java.util.Arrays.asList(selected, launcher), previous, next, true);

        assertTrue(Files.isRegularFile(selectedFile));
        assertTrue(Files.isRegularFile(launcherFile));
        assertTrue(Files.isRegularFile(relauncherFile));
        assertTrue(Files.isRegularFile(loaderFile));
        assertFalse(Files.exists(unlistedFile));
        assertFalse(Files.exists(unlistedDifferentBytes));
    }

    @Test
    void strictCleanupDoesNothingUntilTheInstalledBuildVersionChanges()
        throws Exception {

        Path userMod = gameDirectory.resolve("mods/user-added.jar");
        Files.createDirectories(userMod.getParent());
        Files.write(userMod, "preserve".getBytes(StandardCharsets.UTF_8));
        InstalledState previous = new InstalledState();
        previous.build = "2.0";
        InstalledState next = new InstalledState();
        next.build = "2.0";

        new Installer(gameDirectory, gameDirectory.resolve(".solderpy-loader"),
            new LoaderConfig.Limits()).reconcile(
                Collections.<BootstrapManifest.Package>emptyList(),
                Collections.<BootstrapManifest.Package>emptyList(), previous, next, true);

        assertTrue(Files.isRegularFile(userMod));
    }

    @Test
    void verifiesLauncherOwnedModsByHashWithoutDeletingExtraMods() throws Exception {
        Path mods = Files.createDirectories(gameDirectory.resolve("mods"));
        byte[] nativeBytes = "launcher-owned".getBytes(StandardCharsets.UTF_8);
        Path renamedNativeMod = mods.resolve("a-completely-different-name.jar");
        Path userMod = mods.resolve("user-added.jar");
        Files.write(renamedNativeMod, nativeBytes);
        Files.write(userMod, "user mod".getBytes(StandardCharsets.UTF_8));

        BootstrapManifest.Package launcher = jarPackage(
            "native-mod", "2.0", "mods/provider-name.jar", md5(nativeBytes));
        launcher.prettyName = "Native Mod";
        launcher.membershipId = 7L;
        launcher.bootstrapManaged = false;
        launcher.installOwner = "launcher";

        InstalledState cached = new InstalledState();
        new Installer(gameDirectory, gameDirectory.resolve(".solderpy-loader"),
            new LoaderConfig.Limits()).reconcile(
                Collections.<BootstrapManifest.Package>emptyList(),
                Collections.singletonList(launcher), new InstalledState(),
                cached, false);

        assertTrue(Files.isRegularFile(renamedNativeMod));
        assertTrue(Files.isRegularFile(userMod));
        assertEquals("mods/a-completely-different-name.jar",
            cached.launcherFileMetadata.get(launcher.download.md5).path);

        Path movedNativeMod = mods.resolve("renamed-again.jar");
        Files.move(renamedNativeMod, movedNativeMod);
        InstalledState refreshed = new InstalledState();
        new Installer(gameDirectory, gameDirectory.resolve(".solderpy-loader"),
            new LoaderConfig.Limits()).reconcile(
                Collections.<BootstrapManifest.Package>emptyList(),
                Collections.singletonList(launcher), cached, refreshed, false);

        assertEquals("mods/renamed-again.jar",
            refreshed.launcherFileMetadata.get(launcher.download.md5).path);
        assertTrue(Files.isRegularFile(userMod));
    }

    @Test
    void launcherMetadataCacheCanBeReplacedByAlwaysHashVerification() throws Exception {
        Path mod = gameDirectory.resolve("mods/native.jar");
        Files.createDirectories(mod.getParent());
        byte[] expected = "expected launcher bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(mod, expected);

        BootstrapManifest.Package launcher = jarPackage(
            "native-mod", "2.0", "mods/provider-name.jar", md5(expected));
        launcher.prettyName = "Native Mod";
        launcher.membershipId = 7L;
        launcher.bootstrapManaged = false;
        launcher.installOwner = "launcher";
        InstalledState cached = new InstalledState();

        new Installer(gameDirectory, gameDirectory.resolve(".solderpy-loader"),
            new LoaderConfig.Limits()).reconcile(
                Collections.<BootstrapManifest.Package>emptyList(),
                Collections.singletonList(launcher), new InstalledState(), cached, false);

        InstalledState.FileMetadata metadata =
            cached.launcherFileMetadata.get(launcher.download.md5);
        Files.write(mod, new byte[expected.length]);
        Files.setLastModifiedTime(mod,
            FileTime.from(metadata.lastModifiedNanos, TimeUnit.NANOSECONDS));

        new Installer(gameDirectory, gameDirectory.resolve(".solderpy-loader"),
            new LoaderConfig.Limits()).reconcile(
                Collections.<BootstrapManifest.Package>emptyList(),
                Collections.singletonList(launcher), cached, new InstalledState(), false);

        RecoverableBootstrapException error = assertThrows(
            RecoverableBootstrapException.class, () ->
                new Installer(gameDirectory, gameDirectory.resolve(".solderpy-loader"),
                    new LoaderConfig.Limits(), true).reconcile(
                        Collections.<BootstrapManifest.Package>emptyList(),
                        Collections.singletonList(launcher), cached,
                        new InstalledState(), false));
        assertTrue(error.getMessage().contains("Missing or wrong version"));
    }

    @Test
    void loaderOwnedMetadataCacheCanBeReplacedByAlwaysHashVerification() throws Exception {
        Path mod = gameDirectory.resolve("mods/managed.jar");
        Files.createDirectories(mod.getParent());
        byte[] expected = "expected managed bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(mod, expected);

        BootstrapManifest.Package managed = jarPackage(
            "managed", "1.0", "mods/managed.jar", md5(expected));
        Map<String, String> hashes = new LinkedHashMap<String, String>();
        hashes.put("mods/managed.jar", md5(expected));
        InstalledState state = new InstalledState();
        state.receipts.put("managed", new InstalledState.Receipt(
            "managed", "1.0", managed.download.md5, "jar:mods/managed.jar",
            Collections.singletonList("mods/managed.jar"), hashes));

        Installer cachedInstaller = new Installer(
            gameDirectory, gameDirectory.resolve(".solderpy-loader"),
            new LoaderConfig.Limits());
        assertTrue(cachedInstaller.isInstalledStateIntact(
            Collections.singletonList(managed), state));
        InstalledState.FileMetadata metadata = state.receipts.get("managed")
            .fileMetadata.get("mods/managed.jar");

        Files.write(mod, new byte[expected.length]);
        Files.setLastModifiedTime(mod,
            FileTime.from(metadata.lastModifiedNanos, TimeUnit.NANOSECONDS));

        assertTrue(cachedInstaller.isInstalledStateIntact(
            Collections.singletonList(managed), state));
        assertFalse(new Installer(
            gameDirectory, gameDirectory.resolve(".solderpy-loader"),
            new LoaderConfig.Limits(), true).isInstalledStateIntact(
                Collections.singletonList(managed), state));
    }

    @Test
    void missingLauncherOwnedHashStopsLaunchWithoutDeletingAnything() throws Exception {
        Path mods = Files.createDirectories(gameDirectory.resolve("mods"));
        Path wrongVersion = mods.resolve("native-mod.jar");
        Files.write(wrongVersion, "wrong version".getBytes(StandardCharsets.UTF_8));

        BootstrapManifest.Package launcher = jarPackage(
            "native-mod", "2.0", "mods/provider-name.jar",
            md5("expected version".getBytes(StandardCharsets.UTF_8)));
        launcher.prettyName = "Native Mod";
        launcher.membershipId = 7L;
        launcher.bootstrapManaged = false;
        launcher.installOwner = "launcher";

        RecoverableBootstrapException error = assertThrows(
            RecoverableBootstrapException.class, () ->
                new Installer(gameDirectory, gameDirectory.resolve(".solderpy-loader"),
                    new LoaderConfig.Limits()).reconcile(
                        Collections.<BootstrapManifest.Package>emptyList(),
                        Collections.singletonList(launcher), new InstalledState(),
                        new InstalledState(), false));

        assertTrue(error.getMessage().contains(
            "Missing or wrong version: Native Mod 2.0 [native-mod]"));
        assertTrue(Files.isRegularFile(wrongVersion));
    }

    @Test
    void disabledStrictCleanupPreservesUserModsAcrossBuildChanges()
        throws Exception {

        Path userMod = gameDirectory.resolve("mods/user-added.jar");
        Files.createDirectories(userMod.getParent());
        Files.write(userMod, "preserve".getBytes(StandardCharsets.UTF_8));
        InstalledState previous = new InstalledState();
        previous.build = "1.0";
        InstalledState next = new InstalledState();
        next.build = "2.0";

        new Installer(gameDirectory, gameDirectory.resolve(".solderpy-loader"),
            new LoaderConfig.Limits()).reconcile(
                Collections.<BootstrapManifest.Package>emptyList(),
                Collections.<BootstrapManifest.Package>emptyList(), previous, next, false);

        assertTrue(Files.isRegularFile(userMod));
    }

    @Test
    void strictCleanupRefusesLauncherOwnedZipWithoutInstalledFileHashes() throws Exception {
        Path nativeMod = gameDirectory.resolve("mods/upstream-name.jar");
        Files.createDirectories(nativeMod.getParent());
        Files.write(nativeMod, "native".getBytes(StandardCharsets.UTF_8));
        BootstrapManifest.Package launcher = jarPackage(
            "native", "1.0", "mods/solder-name.jar",
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        launcher.membershipId = 9L;
        launcher.bootstrapManaged = false;
        launcher.installOwner = "launcher";
        launcher.download.format = "solder_zip";
        launcher.download.path = null;
        launcher.download.extractTo = ".";
        InstalledState previous = new InstalledState();
        previous.build = "1.0";
        InstalledState next = new InstalledState();
        next.build = "2.0";

        RecoverableBootstrapException error = assertThrows(
            RecoverableBootstrapException.class, () ->
                new Installer(gameDirectory, gameDirectory.resolve(".solderpy-loader"),
                    new LoaderConfig.Limits()).reconcile(
                        Collections.<BootstrapManifest.Package>emptyList(),
                        Collections.singletonList(launcher), previous, next, true));

        assertTrue(error.getMessage().contains("raw JAR MD5"));
        assertTrue(Files.isRegularFile(nativeMod));
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

    private static BootstrapManifest.Package jarPackage(
        String name, String version, String path, String artifactMd5) {

        BootstrapManifest.Package item = new BootstrapManifest.Package();
        item.name = name;
        item.version = version;
        item.bootstrapManaged = true;
        item.installOwner = "loader";
        item.download = new BootstrapManifest.Download();
        item.download.md5 = artifactMd5;
        item.download.format = "jar";
        item.download.path = path;
        return item;
    }

    private static void send(HttpExchange exchange, byte[] body) throws IOException {
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static String md5(byte[] bytes) throws Exception {
        return digest("MD5", bytes);
    }

    private static String digest(String algorithm, byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance(algorithm).digest(bytes);
        StringBuilder value = new StringBuilder();
        for (byte item : digest) {
            value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        }
        return value.toString();
    }
}
