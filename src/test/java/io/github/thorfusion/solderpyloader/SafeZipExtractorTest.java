package io.github.thorfusion.solderpyloader;

import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeZipExtractorTest {
    @TempDir Path temporary;

    @Test
    void extractsNormalFilesUnderConfiguredRoot() throws Exception {
        Path archive = temporary.resolve("normal.zip");
        writeEntry(archive, "config/example.cfg", "enabled=true", 0);
        Path output = temporary.resolve("output");

        List<String> files = new SafeZipExtractor(1024, 10)
            .extract(archive, ".", output);

        assertEquals(1, files.size());
        assertEquals("config/example.cfg", files.get(0));
        assertTrue(Files.isRegularFile(output.resolve("config/example.cfg")));
    }

    @Test
    void rejectsTraversal() throws Exception {
        Path archive = temporary.resolve("traversal.zip");
        writeEntry(archive, "../outside.txt", "bad", 0);

        assertThrows(LoaderException.class, () ->
            new SafeZipExtractor(1024, 10).extract(archive, ".", temporary.resolve("output")));
    }

    @Test
    void rejectsSymbolicLinks() throws Exception {
        Path archive = temporary.resolve("link.zip");
        writeEntry(archive, "link", "target", UnixStat.LINK_FLAG | 0777);

        assertThrows(LoaderException.class, () ->
            new SafeZipExtractor(1024, 10).extract(archive, ".", temporary.resolve("output")));
    }

    private static void writeEntry(Path archive, String name, String contents, int unixMode) throws Exception {
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(archive)) {
            ZipArchiveEntry entry = new ZipArchiveEntry(name);
            if (unixMode != 0) {
                entry.setUnixMode(unixMode);
            }
            byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
            entry.setSize(bytes.length);
            zip.putArchiveEntry(entry);
            zip.write(bytes);
            zip.closeArchiveEntry();
        }
    }
}

