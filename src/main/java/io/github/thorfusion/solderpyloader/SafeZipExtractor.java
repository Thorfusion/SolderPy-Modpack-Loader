package io.github.thorfusion.solderpyloader;

import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class SafeZipExtractor {
    private static final int COPY_BUFFER_BYTES = 256 * 1024;
    private static final int SEVEN_ZIP_FILE_THRESHOLD = 2_048;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final long maxExpandedBytes;
    private final int maxEntries;
    private final SevenZipSupport sevenZip;

    SafeZipExtractor(long maxExpandedBytes, int maxEntries) {
        this(maxExpandedBytes, maxEntries, SevenZipSupport.discover());
    }

    SafeZipExtractor(long maxExpandedBytes, int maxEntries, SevenZipSupport sevenZip) {
        this.maxExpandedBytes = maxExpandedBytes;
        this.maxEntries = maxEntries;
        this.sevenZip = sevenZip;
    }

    List<String> extract(Path archive, String extractTo, Path stagingRoot) throws LoaderException {
        return extractWithMd5(archive, extractTo, stagingRoot).files;
    }

    Extraction extractWithMd5(Path archive, String extractTo, Path stagingRoot)
        throws LoaderException {

        return prepare(archive, extractTo).extract(stagingRoot);
    }

    Prepared prepare(Path archive, String extractTo) throws LoaderException {
        ArchivePlan plan = inspect(archive, extractTo);
        boolean useSevenZip = sevenZip != null && plan.files.size() < SEVEN_ZIP_FILE_THRESHOLD;
        return new Prepared(archive, extractTo, plan, useSevenZip);
    }

    final class Prepared {
        private final Path archive;
        private final String extractTo;
        private final ArchivePlan plan;
        private final boolean useSevenZip;

        private Prepared(
            Path archive, String extractTo, ArchivePlan plan, boolean useSevenZip) {

            this.archive = archive;
            this.extractTo = extractTo;
            this.plan = plan;
            this.useSevenZip = useSevenZip;
        }

        String action() {
            String method = useSevenZip ? "Extracting ZIP with 7-Zip" :
                "Extracting ZIP in one pass";
            return method + " (" + String.format(Locale.ROOT, "%,d", plan.files.size()) +
                " files)";
        }

        Extraction extract(Path stagingRoot) throws LoaderException {
            if (!useSevenZip) {
                if (sevenZip != null && plan.files.size() >= SEVEN_ZIP_FILE_THRESHOLD) {
                    LoaderLog.info("Using single-pass ZIP extraction for " +
                        plan.files.size() + " files to avoid a separate per-file 7-Zip audit");
                }
                return extractBuiltIn(archive, extractTo, stagingRoot);
            }
            return extractWithSevenZip(archive, extractTo, stagingRoot, plan);
        }
    }

    private Extraction extractWithSevenZip(
        Path archive, String extractTo, Path stagingRoot, ArchivePlan plan)
        throws LoaderException {

        Path externalRoot = null;
        try {
            Files.createDirectories(stagingRoot);
            externalRoot = Files.createTempDirectory(stagingRoot, ".7zip-");
            if (!sevenZip.extract(archive, externalRoot)) {
                deleteTree(externalRoot);
                externalRoot = null;
                return extractBuiltIn(archive, extractTo, stagingRoot);
            }
            return auditAndMoveExternal(externalRoot, stagingRoot, plan);
        } catch (LoaderException e) {
            throw e;
        } catch (IOException e) {
            throw new LoaderException("Could not safely extract ZIP " + archive.getFileName(), e);
        } finally {
            if (externalRoot != null) {
                try {
                    deleteTree(externalRoot);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private ArchivePlan inspect(Path archive, String extractTo) throws LoaderException {
        List<ArchiveFile> files = new ArrayList<ArchiveFile>();
        Set<String> outputs = new HashSet<String>();
        long expanded = 0;
        int entries = 0;
        try (ZipFile zip = open(archive)) {
            Enumeration<ZipArchiveEntry> archiveEntries = zip.getEntriesInPhysicalOrder();
            while (archiveEntries.hasMoreElements()) {
                ZipArchiveEntry entry = archiveEntries.nextElement();
                entries++;
                checkEntry(zip, entry, entries);
                if (entry.isDirectory()) {
                    PathSafety.normalizeRelative(entry.getName(), false);
                    continue;
                }
                String archiveRelative = PathSafety.normalizeRelative(entry.getName(), false);
                String outputRelative = PathSafety.combine(extractTo, entry.getName());
                if (!outputs.add(PathSafety.collisionKey(outputRelative))) {
                    throw new LoaderException("ZIP contains duplicate output path: " + outputRelative);
                }
                long size = entry.getSize();
                if (size < 0 || size > maxExpandedBytes - expanded) {
                    throw new LoaderException("ZIP exceeds the configured expanded size limit");
                }
                expanded += size;
                files.add(new ArchiveFile(archiveRelative, outputRelative, size));
            }
        } catch (LoaderException e) {
            throw e;
        } catch (IOException e) {
            throw new LoaderException("Could not safely inspect ZIP " + archive.getFileName(), e);
        }
        return new ArchivePlan(files);
    }

    private Extraction auditAndMoveExternal(
        Path externalRoot, Path stagingRoot, ArchivePlan plan) throws LoaderException, IOException {

        Map<String, ArchiveFile> expected = new LinkedHashMap<String, ArchiveFile>();
        for (ArchiveFile file : plan.files) {
            expected.put(PathSafety.collisionKey(file.archiveRelative), file);
        }

        long expanded = 0;
        Map<String, String> hashes = new LinkedHashMap<String, String>();
        try (Stream<Path> paths = Files.walk(externalRoot)) {
            for (Path path : paths.collect(Collectors.toList())) {
                if (path.equals(externalRoot)) {
                    continue;
                }
                if (Files.isSymbolicLink(path)) {
                    throw new LoaderException("7-Zip produced a symbolic link: " + path);
                }
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new LoaderException("7-Zip produced a non-regular file: " + path);
                }
                String relative = externalRoot.relativize(path).toString().replace('\\', '/');
                String normalized = PathSafety.normalizeRelative(relative, false);
                ArchiveFile archiveFile = expected.remove(PathSafety.collisionKey(normalized));
                if (archiveFile == null || !archiveFile.archiveRelative.equals(normalized)) {
                    throw new LoaderException("7-Zip produced an unexpected file: " + relative);
                }
                long size = Files.size(path);
                if (size != archiveFile.declaredSize || size > maxExpandedBytes - expanded) {
                    throw new LoaderException("7-Zip output does not match the inspected ZIP metadata");
                }
                expanded += size;
                hashes.put(archiveFile.outputRelative, md5(path));
            }
        }
        if (!expected.isEmpty()) {
            throw new LoaderException("7-Zip did not extract every inspected ZIP entry");
        }

        List<String> result = new ArrayList<String>();
        for (ArchiveFile file : plan.files) {
            Path source = PathSafety.resolve(externalRoot, file.archiveRelative);
            Path output = PathSafety.resolve(stagingRoot, file.outputRelative);
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                throw new LoaderException("ZIP output already exists in package staging: " +
                    file.outputRelative);
            }
            Files.createDirectories(output.getParent());
            Files.move(source, output, StandardCopyOption.REPLACE_EXISTING);
            result.add(file.outputRelative);
        }
        return new Extraction(result, hashes);
    }

    private Extraction extractBuiltIn(
        Path archive, String extractTo, Path stagingRoot) throws LoaderException {

        List<String> files = new ArrayList<String>();
        Map<String, String> hashes = new LinkedHashMap<String, String>();
        Set<String> outputs = new HashSet<String>();
        long expanded = 0;
        int entries = 0;
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        // ZipFile reads the central directory. Streaming ZIP readers cannot see
        // Unix link attributes reliably and are therefore unsafe here.
        try (ZipFile zip = open(archive)) {
            Enumeration<ZipArchiveEntry> archiveEntries = zip.getEntriesInPhysicalOrder();
            while (archiveEntries.hasMoreElements()) {
                ZipArchiveEntry entry = archiveEntries.nextElement();
                entries++;
                checkEntry(zip, entry, entries);
                if (entry.isDirectory()) {
                    PathSafety.normalizeRelative(entry.getName(), false);
                    continue;
                }
                String relative = PathSafety.combine(extractTo, entry.getName());
                String collision = PathSafety.collisionKey(relative);
                if (!outputs.add(collision)) {
                    throw new LoaderException("ZIP contains duplicate output path: " + relative);
                }
                Path output = PathSafety.resolve(stagingRoot, relative);
                Files.createDirectories(output.getParent());
                MessageDigest digest = md5Digest();
                try (InputStream entryInput = zip.getInputStream(entry);
                     OutputStream target = Files.newOutputStream(output)) {
                    int read;
                    while ((read = entryInput.read(buffer)) >= 0) {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new IOException("ZIP extraction was interrupted");
                        }
                        if (read > maxExpandedBytes - expanded) {
                            throw new LoaderException("ZIP exceeds the configured expanded size limit");
                        }
                        expanded += read;
                        digest.update(buffer, 0, read);
                        target.write(buffer, 0, read);
                    }
                }
                files.add(relative);
                hashes.put(relative, hex(digest.digest()));
            }
        } catch (LoaderException e) {
            throw e;
        } catch (IOException e) {
            throw new LoaderException("Could not safely extract ZIP " + archive.getFileName(), e);
        }
        return new Extraction(files, hashes);
    }

    private void checkEntry(ZipFile zip, ZipArchiveEntry entry, int entryNumber)
        throws LoaderException {

        if (entryNumber > maxEntries) {
            throw new LoaderException("ZIP contains more than " + maxEntries + " entries");
        }
        if (!zip.canReadEntryData(entry)) {
            throw new LoaderException("ZIP contains an unreadable or encrypted entry: " +
                entry.getName());
        }
        if (entry.isUnixSymlink()) {
            throw new LoaderException("ZIP contains a symbolic link: " + entry.getName());
        }
        if (!entry.isDirectory()) {
            int unixMode = entry.getUnixMode();
            int unixType = unixMode & UnixStat.FILE_TYPE_FLAG;
            if (unixMode != 0 && unixType != 0 && unixType != UnixStat.FILE_FLAG) {
                throw new LoaderException("ZIP contains a non-regular file: " + entry.getName());
            }
        }
    }

    private static ZipFile open(Path archive) throws IOException {
        return ZipFile.builder()
            .setPath(archive)
            .setCharset(StandardCharsets.UTF_8)
            .setUseUnicodeExtraFields(true)
            .setIgnoreLocalFileHeader(false)
            .setMaxNumberOfDisks(1)
            .get();
    }

    private static String md5(Path file) throws LoaderException {
        MessageDigest digest = md5Digest();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return hex(digest.digest());
        } catch (IOException e) {
            throw new LoaderException("Could not calculate extracted-file MD5 for " + file, e);
        }
    }

    private static MessageDigest md5Digest() throws LoaderException {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new LoaderException("This Java runtime does not provide MD5", e);
        }
    }

    private static String hex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            result[index * 2] = HEX[value >>> 4];
            result[index * 2 + 1] = HEX[value & 0x0f];
        }
        return new String(result);
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> ordered = paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            IOException failure = null;
            for (Path path : ordered) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    failure = e;
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class ArchivePlan {
        private final List<ArchiveFile> files;

        private ArchivePlan(List<ArchiveFile> files) {
            this.files = files;
        }
    }

    static final class Extraction {
        final List<String> files;
        final Map<String, String> hashes;

        private Extraction(List<String> files, Map<String, String> hashes) {
            this.files = new ArrayList<String>(files);
            this.hashes = new LinkedHashMap<String, String>(hashes);
        }
    }

    private static final class ArchiveFile {
        private final String archiveRelative;
        private final String outputRelative;
        private final long declaredSize;

        private ArchiveFile(String archiveRelative, String outputRelative, long declaredSize) {
            this.archiveRelative = archiveRelative;
            this.outputRelative = outputRelative;
            this.declaredSize = declaredSize;
        }
    }
}
