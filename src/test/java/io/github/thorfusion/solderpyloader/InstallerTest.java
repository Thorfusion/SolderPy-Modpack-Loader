package io.github.thorfusion.solderpyloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

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
        previous.receipts.put("old", new InstalledState.Receipt(
            "old", "1.0", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "jar:mods/old.jar",
            Collections.singletonList("mods/old.jar")));
        InstalledState next = new InstalledState();
        Path data = gameDirectory.resolve(".solderpy-loader");

        new Installer(gameDirectory, data, new LoaderConfig.Limits())
            .reconcile(Collections.<BootstrapManifest.Package>emptyList(), previous, next);

        assertFalse(Files.exists(owned));
        assertTrue(Files.isRegularFile(userFile));
        assertTrue(Files.isRegularFile(data.resolve("state.json")));
    }
}
