package io.github.thorfusion.solderpyloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiskSpaceTest {
    @TempDir Path temporary;

    @Test
    void downloadEstimateAccountsForAnExistingCachedArtifact() throws Exception {
        Path cache = Files.createDirectories(temporary.resolve("cache"));
        BootstrapManifest.Package item = item(100L);
        Files.write(cache.resolve(item.download.md5 + ".artifact"), new byte[40]);

        long required = new DownloadManager(1000L, cache)
            .additionalBytesRequired(Collections.singletonList(item));

        assertEquals(60L, required);
    }

    @Test
    void unknownArtifactSizeUsesTheConfiguredDownloadLimit() {
        BootstrapManifest.Package item = item(null);

        long required = new DownloadManager(1234L, temporary.resolve("cache"))
            .additionalBytesRequired(Collections.singletonList(item));

        assertEquals(1234L, required);
    }

    @Test
    void failureReportsRequiredAvailableAndMissingSpace() {
        long payload = 512L;
        long required = payload + DiskSpace.MINIMUM_HEADROOM_BYTES;

        LoaderException error = assertThrows(LoaderException.class, () ->
            DiskSpace.require(required - 1L, payload, "test-volume", "download packages"));

        assertTrue(error.getMessage().contains("Not enough disk space"));
        assertTrue(error.getMessage().contains("test-volume"));
        assertTrue(error.getMessage().contains("Free at least 1 B"));
    }

    @Test
    void safetyMarginIsBounded() {
        assertEquals(DiskSpace.MINIMUM_HEADROOM_BYTES, DiskSpace.headroom(1L));
        assertEquals(DiskSpace.MAXIMUM_HEADROOM_BYTES, DiskSpace.headroom(Long.MAX_VALUE));
    }

    private static BootstrapManifest.Package item(Long filesize) {
        BootstrapManifest.Package item = new BootstrapManifest.Package();
        item.name = "example";
        item.version = "1.0";
        item.download = new BootstrapManifest.Download();
        item.download.md5 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        item.download.filesize = filesize;
        item.download.format = "jar";
        item.download.path = "mods/example.jar";
        return item;
    }
}
