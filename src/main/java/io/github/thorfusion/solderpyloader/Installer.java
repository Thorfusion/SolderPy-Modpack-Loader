package io.github.thorfusion.solderpyloader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class Installer {
    private final Path gameDirectory;
    private final Path dataDirectory;
    private final Path loaderJar;
    private final DownloadManager downloads;
    private final SafeZipExtractor zipExtractor;

    Installer(Path gameDirectory, Path dataDirectory, LoaderConfig.Limits limits) {
        this(gameDirectory, dataDirectory, limits, null);
    }

    Installer(Path gameDirectory, Path dataDirectory, LoaderConfig.Limits limits, Path loaderJar) {
        this.gameDirectory = gameDirectory.toAbsolutePath().normalize();
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
        this.loaderJar = loaderJar == null ? null : loaderJar.toAbsolutePath().normalize();
        this.downloads = new DownloadManager(limits.maxDownloadBytes);
        this.zipExtractor = new SafeZipExtractor(limits.maxExpandedBytes, limits.maxArchiveEntries);
    }

    boolean isInstalledStateIntact(
        List<BootstrapManifest.Package> selected, InstalledState state) throws LoaderException {

        if (state.receipts.size() != selected.size()) {
            return false;
        }
        for (BootstrapManifest.Package item : selected) {
            if (!isReusable(item, state.receipts.get(item.name))) {
                return false;
            }
        }
        return true;
    }

    void reconcile(
        List<BootstrapManifest.Package> selected,
        InstalledState previous,
        InstalledState next) throws LoaderException {

        Path transaction = dataDirectory.resolve("staging-" + UUID.randomUUID().toString());
        Path content = transaction.resolve("content");
        Path downloadDirectory = transaction.resolve("downloads");
        Path backup = transaction.resolve("backup");
        try {
            Files.createDirectories(content);
            Map<String, String> desiredOwners = new LinkedHashMap<String, String>();
            Map<String, String> desiredPaths = new LinkedHashMap<String, String>();
            Set<String> stagedPaths = new LinkedHashSet<String>();
            Map<String, InstalledState.Receipt> receipts =
                new LinkedHashMap<String, InstalledState.Receipt>();

            for (BootstrapManifest.Package item : selected) {
                InstalledState.Receipt oldReceipt = previous.receipts.get(item.name);
                boolean reusable = isReusable(item, oldReceipt);
                List<String> files;
                if (reusable) {
                    files = new ArrayList<String>(oldReceipt.files);
                } else {
                    files = stage(item, content, downloadDirectory);
                }
                if (files.isEmpty()) {
                    throw new LoaderException("Package " + item.name + " did not produce any files");
                }
                List<String> normalizedFiles = new ArrayList<String>();
                Map<String, String> hashes = new LinkedHashMap<String, String>();
                for (String relative : files) {
                    String normalized = PathSafety.normalizeRelative(relative, false);
                    validateOutput(normalized);
                    String collision = PathSafety.collisionKey(normalized);
                    String priorOwner = desiredOwners.put(collision, item.name);
                    if (priorOwner != null) {
                        throw new LoaderException("Packages " + priorOwner + " and " + item.name +
                            " both own output path " + normalized);
                    }
                    desiredPaths.put(collision, normalized);
                    normalizedFiles.add(normalized);
                    if (reusable) {
                        hashes.put(normalized, oldReceipt.hashes.get(normalized));
                    } else {
                        stagedPaths.add(normalized);
                        hashes.put(normalized, sha256(PathSafety.resolve(content, normalized)));
                    }
                }
                receipts.put(item.name, new InstalledState.Receipt(
                    item.name, item.version, item.download.md5.toLowerCase(Locale.ROOT),
                    installKey(item), normalizedFiles, hashes));
            }

            Set<String> removals = new LinkedHashSet<String>();
            for (InstalledState.Receipt receipt : previous.receipts.values()) {
                if (receipt == null || receipt.files == null) {
                    continue;
                }
                for (String oldPath : receipt.files) {
                    String normalized = PathSafety.normalizeRelative(oldPath, false);
                    validateOutput(normalized);
                    String desired = desiredPaths.get(PathSafety.collisionKey(normalized));
                    if (desired == null || !desired.equals(normalized)) {
                        removals.add(normalized);
                    }
                }
            }

            next.receipts = receipts;
            commit(stagedPaths, removals, content, backup, next);
        } catch (UnrecoverableBootstrapException e) {
            throw e;
        } catch (RecoverableBootstrapException e) {
            throw e;
        } catch (LoaderException e) {
            throw new RecoverableBootstrapException(e.getMessage(), e);
        } catch (IOException e) {
            throw new RecoverableBootstrapException("Could not stage the modpack update", e);
        } finally {
            deleteTreeQuietly(transaction);
        }
    }

    private boolean isReusable(
        BootstrapManifest.Package item, InstalledState.Receipt receipt) throws LoaderException {
        if (receipt == null || receipt.files == null || receipt.files.isEmpty() ||
            receipt.hashes == null || receipt.hashes.isEmpty() ||
            !item.version.equals(receipt.version) || receipt.md5 == null ||
            !item.download.md5.equalsIgnoreCase(receipt.md5) ||
            !installKey(item).equals(receipt.installKey)) {
            return false;
        }
        for (String value : receipt.files) {
            String relative = PathSafety.normalizeRelative(value, false);
            validateOutput(relative);
            PathSafety.rejectSymlinkAncestors(gameDirectory, relative);
            Path file = PathSafety.resolve(gameDirectory, relative);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            String expectedHash = receipt.hashes.get(relative);
            if (expectedHash == null || !expectedHash.matches("[0-9a-fA-F]{64}") ||
                !expectedHash.equalsIgnoreCase(sha256(file))) {
                return false;
            }
        }
        return true;
    }

    private void validateOutput(String relative) throws LoaderException {
        Path output = PathSafety.resolve(gameDirectory, relative);
        if (output.startsWith(dataDirectory) || output.equals(loaderJar) ||
            "config/solderpy-loader.json".equals(PathSafety.collisionKey(relative))) {
            throw new LoaderException("Package attempts to modify a reserved loader path: " + relative);
        }
    }

    private static String installKey(BootstrapManifest.Package item) throws LoaderException {
        if ("jar".equals(item.download.format)) {
            return "jar:" + PathSafety.normalizeRelative(item.download.path, false);
        }
        if ("solder_zip".equals(item.download.format)) {
            return "solder_zip:" + PathSafety.normalizeRelative(item.download.extractTo, true);
        }
        throw new LoaderException("Unsupported package format: " + item.download.format);
    }

    private List<String> stage(
        BootstrapManifest.Package item, Path content, Path downloadDirectory) throws LoaderException {
        LoaderLog.info("Downloading " + item.name + " " + item.version);
        Path archive = downloads.download(item, downloadDirectory);
        try {
            if ("jar".equals(item.download.format)) {
                String relative = PathSafety.normalizeRelative(item.download.path, false);
                Path output = PathSafety.resolve(content, relative);
                Files.createDirectories(output.getParent());
                Files.move(archive, output, StandardCopyOption.REPLACE_EXISTING);
                return Collections.singletonList(relative);
            }
            if ("solder_zip".equals(item.download.format)) {
                return zipExtractor.extract(archive, item.download.extractTo, content);
            }
            throw new LoaderException("Unsupported package format: " + item.download.format);
        } catch (IOException e) {
            throw new LoaderException("Could not stage package " + item.name, e);
        } finally {
            try {
                Files.deleteIfExists(archive);
            } catch (IOException ignored) {
            }
        }
    }

    private void commit(
        Set<String> stagedPaths,
        Set<String> removals,
        Path content,
        Path backup,
        InstalledState next) throws LoaderException {

        Set<String> affected = new LinkedHashSet<String>();
        affected.addAll(removals);
        affected.addAll(stagedPaths);
        Map<String, Path> backups = new LinkedHashMap<String, Path>();
        List<Path> installed = new ArrayList<Path>();

        try {
            for (String relative : affected) {
                PathSafety.rejectSymlinkAncestors(gameDirectory, relative);
                Path target = PathSafety.resolve(gameDirectory, relative);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                        throw new LoaderException("Owned output is not a regular file: " + target);
                    }
                    Path saved = PathSafety.resolve(backup, relative);
                    Files.createDirectories(saved.getParent());
                    move(target, saved);
                    backups.put(relative, saved);
                }
            }

            for (String relative : stagedPaths) {
                Path source = PathSafety.resolve(content, relative);
                Path target = PathSafety.resolve(gameDirectory, relative);
                PathSafety.rejectSymlinkAncestors(gameDirectory, relative);
                Files.createDirectories(target.getParent());
                move(source, target);
                installed.add(target);
            }

            next.save(dataDirectory);
        } catch (Exception failure) {
            try {
                rollback(installed, backups);
            } catch (LoaderException rollbackFailure) {
                UnrecoverableBootstrapException unrecoverable =
                    new UnrecoverableBootstrapException(
                        "Update failed and rollback was incomplete", rollbackFailure);
                unrecoverable.addSuppressed(failure);
                throw unrecoverable;
            }
            throw new RecoverableBootstrapException(
                "Could not commit the modpack update; previous files were restored", failure);
        }
    }

    private void rollback(List<Path> installed, Map<String, Path> backups) throws LoaderException {
        IOException rollbackFailure = null;
        Collections.reverse(installed);
        for (Path file : installed) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                rollbackFailure = e;
            }
        }
        List<Map.Entry<String, Path>> entries = new ArrayList<Map.Entry<String, Path>>(backups.entrySet());
        Collections.reverse(entries);
        for (Map.Entry<String, Path> entry : entries) {
            try {
                Path destination = PathSafety.resolve(gameDirectory, entry.getKey());
                Files.createDirectories(destination.getParent());
                move(entry.getValue(), destination);
            } catch (Exception e) {
                if (e instanceof IOException) {
                    rollbackFailure = (IOException) e;
                } else {
                    rollbackFailure = new IOException(e);
                }
            }
        }
        if (rollbackFailure != null) {
            throw new LoaderException("Update failed and rollback was incomplete", rollbackFailure);
        }
    }

    private static void move(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(Path file) throws LoaderException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return result.toString();
        } catch (IOException e) {
            throw new LoaderException("Could not verify installed file " + file, e);
        } catch (NoSuchAlgorithmException e) {
            throw new LoaderException("This Java runtime does not provide SHA-256", e);
        }
    }

    private static void deleteTreeQuietly(Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> ordered = paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            for (Path path : ordered) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }
}
