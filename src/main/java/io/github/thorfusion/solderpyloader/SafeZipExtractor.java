package io.github.thorfusion.solderpyloader;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.archivers.zip.UnixStat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class SafeZipExtractor {
    private final long maxExpandedBytes;
    private final int maxEntries;

    SafeZipExtractor(long maxExpandedBytes, int maxEntries) {
        this.maxExpandedBytes = maxExpandedBytes;
        this.maxEntries = maxEntries;
    }

    List<String> extract(Path archive, String extractTo, Path stagingRoot) throws LoaderException {
        List<String> files = new ArrayList<String>();
        Set<String> outputs = new HashSet<String>();
        long expanded = 0;
        int entries = 0;
        // ZipFile reads the central directory. Streaming ZIP readers cannot see
        // Unix link attributes reliably and are therefore unsafe here.
        try (ZipFile zip = ZipFile.builder()
            .setPath(archive)
            .setCharset(StandardCharsets.UTF_8)
            .setUseUnicodeExtraFields(true)
            .setIgnoreLocalFileHeader(false)
            .setMaxNumberOfDisks(1)
            .get()) {
            Enumeration<ZipArchiveEntry> archiveEntries = zip.getEntriesInPhysicalOrder();
            while (archiveEntries.hasMoreElements()) {
                ZipArchiveEntry entry = archiveEntries.nextElement();
                entries++;
                if (entries > maxEntries) {
                    throw new LoaderException("ZIP contains more than " + maxEntries + " entries");
                }
                if (!zip.canReadEntryData(entry)) {
                    throw new LoaderException("ZIP contains an unreadable or encrypted entry: " + entry.getName());
                }
                if (entry.isUnixSymlink()) {
                    throw new LoaderException("ZIP contains a symbolic link: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    continue;
                }
                int unixMode = entry.getUnixMode();
                int unixType = unixMode & UnixStat.FILE_TYPE_FLAG;
                if (unixMode != 0 && unixType != 0 && unixType != UnixStat.FILE_FLAG) {
                    throw new LoaderException("ZIP contains a non-regular file: " + entry.getName());
                }
                String relative = PathSafety.combine(extractTo, entry.getName());
                String collision = PathSafety.collisionKey(relative);
                if (!outputs.add(collision)) {
                    throw new LoaderException("ZIP contains duplicate output path: " + relative);
                }
                Path output = PathSafety.resolve(stagingRoot, relative);
                Files.createDirectories(output.getParent());
                try (InputStream entryInput = zip.getInputStream(entry);
                     OutputStream target = Files.newOutputStream(output)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = entryInput.read(buffer)) >= 0) {
                        expanded += read;
                        if (expanded > maxExpandedBytes) {
                            throw new LoaderException("ZIP exceeds the configured expanded size limit");
                        }
                        target.write(buffer, 0, read);
                    }
                }
                files.add(relative);
            }
        } catch (LoaderException e) {
            throw e;
        } catch (IOException e) {
            throw new LoaderException("Could not safely extract ZIP " + archive.getFileName(), e);
        }
        return files;
    }
}
