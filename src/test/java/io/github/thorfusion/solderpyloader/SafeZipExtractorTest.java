package io.github.thorfusion.solderpyloader;

import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

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

        SafeZipExtractor.Extraction extraction = new SafeZipExtractor(1024, 10)
            .extractWithMd5(archive, ".", output);
        List<String> files = extraction.files;

        assertEquals(1, files.size());
        assertEquals("config/example.cfg", files.get(0));
        assertEquals(md5("enabled=true"), extraction.hashes.get("config/example.cfg"));
        assertTrue(Files.isRegularFile(output.resolve("config/example.cfg")));
    }

    @Test
    void builtInExtractorAlsoProducesMd5Receipts() throws Exception {
        Path archive = temporary.resolve("built-in.zip");
        writeEntry(archive, "resourcepacks/example.txt", "contents", 0);
        Path output = temporary.resolve("built-in-output");

        SafeZipExtractor.Extraction extraction = new SafeZipExtractor(1024, 10, null)
            .extractWithMd5(archive, ".", output);

        assertEquals(md5("contents"),
            extraction.hashes.get("resourcepacks/example.txt"));
        assertTrue(Files.isRegularFile(output.resolve("resourcepacks/example.txt")));
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

    private static String md5(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5")
            .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(32);
        for (byte item : digest) {
            result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        }
        return result.toString();
    }
}
