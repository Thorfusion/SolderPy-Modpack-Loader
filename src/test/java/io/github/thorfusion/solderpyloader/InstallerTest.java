package io.github.thorfusion.solderpyloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

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
}
