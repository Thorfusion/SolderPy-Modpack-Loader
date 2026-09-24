package io.github.thorfusion.solderpyloader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

final class Installer {
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final ThreadLocal<byte[]> HASH_BUFFER =
        new ThreadLocal<byte[]>() {
            @Override
            protected byte[] initialValue() {
                return new byte[256 * 1024];
            }
        };

    private final Path gameDirectory;
    private final Path dataDirectory;
    private final Path loaderJar;
    private final DownloadManager downloads;
    private final SafeZipExtractor zipExtractor;
    private final int concurrentDownloads;
    private final int concurrentExtractions;
    private final BootstrapProgress progress;
    private final boolean alwaysHashFiles;

    Installer(Path gameDirectory, Path dataDirectory, LoaderConfig.Limits limits) {
        this(gameDirectory, dataDirectory, limits, null, BootstrapProgress.console(), false);
    }

    Installer(
        Path gameDirectory,
        Path dataDirectory,
        LoaderConfig.Limits limits,
        boolean alwaysHashFiles) {

        this(gameDirectory, dataDirectory, limits, null, BootstrapProgress.console(),
            alwaysHashFiles);
    }

    Installer(Path gameDirectory, Path dataDirectory, LoaderConfig.Limits limits, Path loaderJar) {
        this(gameDirectory, dataDirectory, limits, loaderJar, BootstrapProgress.console(), false);
    }

    Installer(
        Path gameDirectory,
        Path dataDirectory,
        LoaderConfig.Limits limits,
        Path loaderJar,
        boolean alwaysHashFiles) {

        this(gameDirectory, dataDirectory, limits, loaderJar, BootstrapProgress.console(),
            alwaysHashFiles);
    }

    Installer(
        Path gameDirectory,
        Path dataDirectory,
        LoaderConfig.Limits limits,
        Path loaderJar,
        BootstrapProgress progress) {

        this(gameDirectory, dataDirectory, limits, loaderJar, progress, false);
    }

    Installer(
        Path gameDirectory,
        Path dataDirectory,
        LoaderConfig.Limits limits,
        Path loaderJar,
        BootstrapProgress progress,
        boolean alwaysHashFiles) {

        this.gameDirectory = gameDirectory.toAbsolutePath().normalize();
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
        this.loaderJar = loaderJar == null ? null : loaderJar.toAbsolutePath().normalize();
        this.downloads = new DownloadManager(
            limits.maxDownloadBytes, this.dataDirectory.resolve("cache/downloads"));
        this.zipExtractor = new SafeZipExtractor(limits.maxExpandedBytes, limits.maxArchiveEntries);
        this.concurrentDownloads = limits.maxConcurrentDownloads;
        this.concurrentExtractions = limits.maxConcurrentExtractions;
        this.progress = progress;
        this.alwaysHashFiles = alwaysHashFiles;
    }

    boolean isInstalledStateIntact(
        List<BootstrapManifest.Package> selected, InstalledState state) throws LoaderException {

        return isInstalledStateIntact(selected, selected, state);
    }

    boolean isInstalledStateIntact(
        List<BootstrapManifest.Package> selected,
        List<BootstrapManifest.Package> allPackages,
        InstalledState state) throws LoaderException {

        if (state.receipts.size() != selected.size()) {
            return false;
        }
        verifyLauncherOwnedFiles(allPackages, state, state);
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

        reconcile(selected, selected, previous, next, false);
    }

    void reconcile(
        List<BootstrapManifest.Package> selected,
        List<BootstrapManifest.Package> allPackages,
        InstalledState previous,
        InstalledState next) throws LoaderException {

        reconcile(selected, allPackages, previous, next, false);
    }

    void reconcile(
        List<BootstrapManifest.Package> selected,
        List<BootstrapManifest.Package> allPackages,
        InstalledState previous,
        InstalledState next,
        boolean removeUnlistedModFiles) throws LoaderException {

        Path transaction = InstallTransaction.create(dataDirectory);
        Path content = transaction.resolve("content");
        Path downloadDirectory = transaction.resolve("downloads");
        Path packagesDirectory = transaction.resolve("packages");
        boolean commitStarted = false;
        try {
            Files.createDirectories(content);
            Map<String, String> desiredOwners = new LinkedHashMap<String, String>();
            Map<String, String> desiredPaths = new LinkedHashMap<String, String>();
            Set<String> stagedPaths = new LinkedHashSet<String>();
            Map<String, InstalledState.Receipt> receipts =
                new LinkedHashMap<String, InstalledState.Receipt>();
            Set<String> externallyOwnedPackages = new LinkedHashSet<String>();
            for (BootstrapManifest.Package item : allPackages) {
                if (!item.bootstrapManaged) {
                    externallyOwnedPackages.add(item.name);
                }
            }
            verifyLauncherOwnedFiles(allPackages, previous, next);
            Set<String> reusablePackages = new LinkedHashSet<String>();
            List<BootstrapManifest.Package> pendingDownloads =
                new ArrayList<BootstrapManifest.Package>();
            boolean strictModCleanup = removeUnlistedModFiles &&
                previous != null && previous.build != null && next.build != null &&
                !previous.build.equals(next.build);
            progress.beginInstalledCheck(selected);
            for (BootstrapManifest.Package item : selected) {
                progress.checking(item);
                InstalledState.Receipt oldReceipt = previous.receipts.get(item.name);
                boolean reusable = isReusable(item, oldReceipt, strictModCleanup);
                if (reusable) {
                    reusablePackages.add(item.name);
                } else {
                    pendingDownloads.add(item);
                }
                progress.checked(item, reusable);
            }

            if (!pendingDownloads.isEmpty()) {
                progress.phase("Checking disk space for downloads...");
                long requiredBytes = downloads.additionalBytesRequired(pendingDownloads);
                if (strictModCleanup) {
                    requiredBytes = DiskSpace.add(
                        requiredBytes, regularModFilesBytes());
                }
                DiskSpace.require(dataDirectory, requiredBytes,
                    "download " + pendingDownloads.size() + " changed package(s)");
            }
            DownloadBatch downloadBatch =
                downloadAll(pendingDownloads, downloadDirectory);
            Map<String, DownloadManager.DownloadedFile> downloaded = verifyAll(
                pendingDownloads, downloadBatch.files, downloadBatch.failure);
            Map<String, StagedPackage> stagedPackages =
                stageAll(pendingDownloads, downloaded, packagesDirectory, previous, selected);
            if (!pendingDownloads.isEmpty()) {
                progress.phase("Checking package ownership...");
            }

            for (BootstrapManifest.Package item : selected) {
                InstalledState.Receipt oldReceipt = previous.receipts.get(item.name);
                boolean reusable = reusablePackages.contains(item.name);
                List<String> files;
                if (reusable) {
                    files = new ArrayList<String>(oldReceipt.files);
                } else {
                    StagedPackage staged = stagedPackages.get(item.name);
                    if (staged == null) {
                        throw new LoaderException("Installed package is missing from staging: " + item.name);
                    }
                    files = staged.files;
                }
                if (files.isEmpty()) {
                    throw new LoaderException("Package " + item.name + " did not produce any files");
                }
                List<String> normalizedFiles = new ArrayList<String>();
                Map<String, String> hashes = new LinkedHashMap<String, String>();
                Map<String, InstalledState.FileMetadata> fileMetadata =
                    new LinkedHashMap<String, InstalledState.FileMetadata>();
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
                        InstalledState.FileMetadata metadata =
                            oldReceipt.fileMetadata.get(normalized);
                        if (metadata != null) {
                            fileMetadata.put(normalized, metadata);
                        }
                    } else {
                        StagedPackage staged = stagedPackages.get(item.name);
                        String stagedHash = staged.hashes.get(normalized);
                        if (stagedHash == null || !stagedHash.matches("[0-9a-fA-F]{32}")) {
                            throw new LoaderException("Package staging is missing an MD5 receipt for " +
                                normalized);
                        }
                        Path source = PathSafety.resolve(staged.root, normalized);
                        hashes.put(normalized, stagedHash.toLowerCase(Locale.ROOT));
                        if (matchesExistingFile(normalized, stagedHash)) {
                            Path existing = PathSafety.resolve(gameDirectory, normalized);
                            fileMetadata.put(normalized,
                                readFileMetadata(normalized, existing));
                            Files.deleteIfExists(source);
                        } else {
                            fileMetadata.put(normalized,
                                readFileMetadata(normalized, source));
                            stagedPaths.add(normalized);
                        }
                    }
                }
                if (!reusable) {
                    mergeStagedTree(stagedPackages.get(item.name).root, content);
                }
                receipts.put(item.name, new InstalledState.Receipt(
                    item.name, item.version, item.download.md5.toLowerCase(Locale.ROOT),
                    installKey(item), normalizedFiles, hashes, fileMetadata));
            }

            Set<String> removals = new LinkedHashSet<String>();
            for (InstalledState.Receipt receipt : previous.receipts.values()) {
                if (receipt == null || receipt.files == null) {
                    continue;
                }
                if (externallyOwnedPackages.contains(receipt.slug)) {
                    LoaderLog.info("Relinquishing package " + receipt.slug +
                        " to its declared install owner");
                    continue;
                }
                for (String oldPath : receipt.files) {
                    String normalized = PathSafety.normalizeRelative(oldPath, false);
                    validateOutput(normalized);
                    String desired = desiredPaths.get(PathSafety.collisionKey(normalized));
                    if ((desired == null || !desired.equals(normalized)) &&
                        canRemoveOwnedFile(receipt, normalized)) {
                        removals.add(normalized);
                    }
                }
            }

            if (strictModCleanup) {
                progress.phase("Finding unlisted files in the mods folder...");
                Map<String, Set<String>> allowedHashes = allowedModHashes(
                    receipts, allPackages);
                addUnlistedModRemovals(
                    removals, desiredPaths.keySet(), allPackages, allowedHashes);
            }

            if (!stagedPaths.isEmpty() || !removals.isEmpty()) {
                progress.phase("Checking disk space for rollback backups...");
                DiskSpace.require(dataDirectory,
                    estimateCommitBackupBytes(stagedPaths, removals),
                    "create rollback backups for the modpack update");
            }

            next.receipts = receipts;
            progress.phase("Committing modpack update...");
            commitStarted = true;
            InstallTransaction.commit(
                gameDirectory, dataDirectory, loaderJar, transaction, content,
                stagedPaths, removals, next);
            downloads.pruneCache(selected);
        } catch (UnrecoverableBootstrapException e) {
            throw e;
        } catch (RecoverableBootstrapException e) {
            throw e;
        } catch (LoaderException e) {
            throw new RecoverableBootstrapException(e.getMessage(), e);
        } catch (IOException e) {
            throw new RecoverableBootstrapException("Could not stage the modpack update", e);
        } finally {
            if (!commitStarted) {
                InstallTransaction.discard(transaction);
            }
        }
    }

    private boolean canRemoveOwnedFile(
        InstalledState.Receipt receipt, String relative) throws LoaderException {

        PathSafety.rejectSymlinkAncestors(gameDirectory, relative);
        Path existing = PathSafety.resolve(gameDirectory, relative);
        if (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        String expected = receipt.hashes == null ? null : receipt.hashes.get(relative);
        if (!Files.isRegularFile(existing, LinkOption.NOFOLLOW_LINKS) ||
            expected == null || !expected.matches("[0-9a-fA-F]{32}")) {
            LoaderLog.warn("Preserving unverified former Loader output " + relative);
            return false;
        }
        if (!expected.equalsIgnoreCase(md5(existing))) {
            LoaderLog.warn("Preserving locally modified Loader output " + relative);
            return false;
        }
        return true;
    }

    private long regularModFilesBytes() throws LoaderException {
        Path mods = gameDirectory.resolve("mods");
        if (!Files.exists(mods, LinkOption.NOFOLLOW_LINKS)) {
            return 0L;
        }
        if (Files.isSymbolicLink(mods) ||
            !Files.isDirectory(mods, LinkOption.NOFOLLOW_LINKS)) {
            throw new LoaderException(
                "Strict mod cleanup requires mods to be a safe directory: " + mods);
        }
        long result = 0L;
        try (Stream<Path> paths = Files.walk(mods)) {
            java.util.Iterator<Path> iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(path)) {
                    result = DiskSpace.add(result,
                        fileSize(path, "file in mods for cleanup rollback"));
                }
            }
            return result;
        } catch (LoaderException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new LoaderException(
                "Could not inspect the mods folder for the disk-space check", e);
        }
    }

    private void addUnlistedModRemovals(
        Set<String> removals,
        Set<String> desiredPathKeys,
        List<BootstrapManifest.Package> allPackages,
        Map<String, Set<String>> allowedHashes) throws LoaderException {

        Path mods = gameDirectory.resolve("mods");
        if (!Files.exists(mods, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(mods) ||
            !Files.isDirectory(mods, LinkOption.NOFOLLOW_LINKS)) {
            throw new LoaderException(
                "Strict mod cleanup requires mods to be a safe directory: " + mods);
        }

        Set<String> protectedPaths = new LinkedHashSet<String>(desiredPathKeys);
        if (loaderJar != null && loaderJar.startsWith(gameDirectory)) {
            addProtectedModPath(protectedPaths,
                gameDirectory.relativize(loaderJar).toString().replace('\\', '/'));
        }

        int added = 0;
        try (Stream<Path> paths = Files.walk(mods)) {
            java.util.Iterator<Path> iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path file = iterator.next();
                if (file.equals(mods) || Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (Files.isSymbolicLink(file) ||
                    !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    LoaderLog.warn(
                        "Preserving unsafe non-regular entry during strict mod cleanup: " + file);
                    continue;
                }
                String relative = PathSafety.normalizeRelative(
                    gameDirectory.relativize(file).toString().replace('\\', '/'), false);
                String collision = PathSafety.collisionKey(relative);
                if (protectedPaths.contains(collision) || isLoaderRuntimeJar(file) ||
                    matchesAllowedHash(file, allowedHashes)) {
                    continue;
                }
                if (removals.add(relative)) {
                    added++;
                }
            }
        } catch (LoaderException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new LoaderException(
                "Could not inspect the mods folder for strict cleanup", e);
        }
        LoaderLog.info("Strict mod cleanup scheduled " + added +
            " unlisted file(s) for transactional removal");
    }

    private void verifyLauncherOwnedFiles(
        List<BootstrapManifest.Package> allPackages,
        InstalledState previous,
        InstalledState next) throws LoaderException {

        Map<String, List<BootstrapManifest.Package>> missing =
            new LinkedHashMap<String, List<BootstrapManifest.Package>>();
        for (BootstrapManifest.Package item : allPackages) {
            if (!"launcher".equals(item.installOwner)) {
                continue;
            }
            if (!hasLauncherJarMd5(item)) {
                throw new LoaderException(
                    "Cannot verify launcher-owned " + packageIdentity(item) +
                    " because the signed manifest has no raw JAR MD5. Publish the " +
                    "package with a raw JAR MD5 before exporting the pack.");
            }
            String hash = item.download.md5.toLowerCase(Locale.ROOT);
            List<BootstrapManifest.Package> packages = missing.get(hash);
            if (packages == null) {
                packages = new ArrayList<BootstrapManifest.Package>();
                missing.put(hash, packages);
            }
            packages.add(item);
        }
        if (missing.isEmpty()) {
            next.launcherFileMetadata =
                new LinkedHashMap<String, InstalledState.FileMetadata>();
            return;
        }

        progress.phase("Verifying launcher-installed mods...");
        Map<String, InstalledState.FileMetadata> verified =
            new LinkedHashMap<String, InstalledState.FileMetadata>();
        Map<String, InstalledState.FileMetadata> cached =
            previous == null || previous.launcherFileMetadata == null
                ? Collections.<String, InstalledState.FileMetadata>emptyMap()
                : previous.launcherFileMetadata;
        Set<String> inspectedPaths = new LinkedHashSet<String>();
        int metadataHits = 0;
        int hashedFiles = 0;

        for (String expectedHash : new ArrayList<String>(missing.keySet())) {
            InstalledState.FileMetadata metadata = cached.get(expectedHash);
            if (metadata == null || metadata.path == null) {
                continue;
            }
            try {
                String relative = PathSafety.normalizeRelative(metadata.path, false);
                String collision = PathSafety.collisionKey(relative);
                if (!collision.startsWith("mods/")) {
                    continue;
                }
                PathSafety.rejectSymlinkAncestors(gameDirectory, relative);
                Path file = PathSafety.resolve(gameDirectory, relative);
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) ||
                    Files.isSymbolicLink(file)) {
                    continue;
                }
                if (!alwaysHashFiles && matchesFileMetadata(relative, file, metadata)) {
                    inspectedPaths.add(collision);
                    missing.remove(expectedHash);
                    verified.put(expectedHash, readFileMetadata(relative, file));
                    metadataHits++;
                    continue;
                }
                String actualHash = md5(file);
                hashedFiles++;
                inspectedPaths.add(collision);
                if (missing.remove(actualHash) != null) {
                    verified.put(actualHash, readFileMetadata(relative, file));
                }
            } catch (LoaderException | RuntimeException staleMetadata) {
                LoaderLog.warn(
                    "Ignoring stale launcher-owned file metadata for hash " + expectedHash);
            }
        }

        Path mods = gameDirectory.resolve("mods");
        if (!missing.isEmpty() && Files.exists(mods, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(mods) ||
                !Files.isDirectory(mods, LinkOption.NOFOLLOW_LINKS)) {
                throw new LoaderException(
                    "Launcher-owned mod verification requires mods to be a safe directory: " +
                    mods);
            }
            try (Stream<Path> paths = Files.walk(mods)) {
                java.util.Iterator<Path> iterator = paths.iterator();
                while (iterator.hasNext() && !missing.isEmpty()) {
                    Path file = iterator.next();
                    if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) &&
                        !Files.isSymbolicLink(file)) {
                        String relative = PathSafety.normalizeRelative(
                            gameDirectory.relativize(file).toString().replace('\\', '/'), false);
                        if (!inspectedPaths.add(PathSafety.collisionKey(relative))) {
                            continue;
                        }
                        String actualHash = md5(file);
                        hashedFiles++;
                        if (missing.remove(actualHash) != null) {
                            verified.put(actualHash, readFileMetadata(relative, file));
                        }
                    }
                }
            } catch (LoaderException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                throw new LoaderException(
                    "Could not inspect the mods folder for launcher-owned packages", e);
            }
        }

        if (!missing.isEmpty()) {
            List<String> identities = new ArrayList<String>();
            for (List<BootstrapManifest.Package> packages : missing.values()) {
                for (BootstrapManifest.Package item : packages) {
                    identities.add(packageIdentity(item));
                }
            }
            throw new LoaderException(
                "Launcher-owned mod verification failed. Missing or wrong version: " +
                String.join(", ", identities) + ". Repair or reinstall the modpack in " +
                "the launcher, then try again.");
        }
        next.launcherFileMetadata = verified;
        LoaderLog.info("Verified " + launcherPackageCount(allPackages) +
            " launcher-owned package(s): " + metadataHits +
            " metadata cache hit(s), " + hashedFiles + " file hash(es) calculated");
    }

    private static int launcherPackageCount(
        List<BootstrapManifest.Package> allPackages) {

        int result = 0;
        for (BootstrapManifest.Package item : allPackages) {
            if ("launcher".equals(item.installOwner)) {
                result++;
            }
        }
        return result;
    }

    private static Map<String, Set<String>> allowedModHashes(
        Map<String, InstalledState.Receipt> receipts,
        List<BootstrapManifest.Package> allPackages) throws LoaderException {

        Map<String, Set<String>> result = new LinkedHashMap<String, Set<String>>();
        for (InstalledState.Receipt receipt : receipts.values()) {
            if (receipt == null || receipt.hashes == null) {
                continue;
            }
            for (Map.Entry<String, String> hash : receipt.hashes.entrySet()) {
                String relative = PathSafety.normalizeRelative(hash.getKey(), false);
                if (PathSafety.collisionKey(relative).startsWith("mods/") &&
                    hash.getValue() != null && hash.getValue().matches("[0-9a-fA-F]{32}")) {
                    addAllowedHash(result, "md5", hash.getValue());
                }
            }
        }
        for (BootstrapManifest.Package item : allPackages) {
            if (("launcher".equals(item.installOwner) ||
                "ignored".equals(item.installOwner)) && hasLauncherJarMd5(item)) {
                addAllowedHash(result, "md5", item.download.md5);
            }
        }
        return result;
    }

    private static boolean hasLauncherJarMd5(BootstrapManifest.Package item) {
        return item.download != null && "jar".equals(item.download.format) &&
            item.download.md5 != null &&
            item.download.md5.matches("[0-9a-fA-F]{32}");
    }

    private static void addAllowedHash(
        Map<String, Set<String>> hashes, String algorithm, String value) {

        Set<String> values = hashes.get(algorithm);
        if (values == null) {
            values = new LinkedHashSet<String>();
            hashes.put(algorithm, values);
        }
        values.add(value.toLowerCase(Locale.ROOT));
    }

    private static boolean matchesAllowedHash(
        Path file, Map<String, Set<String>> allowedHashes) throws LoaderException {

        if (allowedHashes.isEmpty()) {
            return false;
        }
        Map<String, MessageDigest> digests = new LinkedHashMap<String, MessageDigest>();
        try {
            for (String algorithm : allowedHashes.keySet()) {
                String javaName;
                if ("md5".equals(algorithm)) {
                    javaName = "MD5";
                } else if ("sha1".equals(algorithm)) {
                    javaName = "SHA-1";
                } else if ("sha512".equals(algorithm)) {
                    javaName = "SHA-512";
                } else {
                    throw new LoaderException(
                        "Unsupported launcher-owned hash algorithm: " + algorithm);
                }
                digests.put(algorithm, MessageDigest.getInstance(javaName));
            }
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = HASH_BUFFER.get();
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    for (MessageDigest digest : digests.values()) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            for (Map.Entry<String, MessageDigest> digest : digests.entrySet()) {
                if (allowedHashes.get(digest.getKey()).contains(hex(digest.getValue().digest()))) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            throw new LoaderException(
                "Could not hash mod file before strict cleanup: " + file, e);
        } catch (NoSuchAlgorithmException e) {
            throw new LoaderException("This Java runtime cannot calculate cleanup hashes", e);
        }
    }

    private void addProtectedModPath(Set<String> protectedPaths, String value)
        throws LoaderException {

        String relative = PathSafety.normalizeRelative(value, false);
        String collision = PathSafety.collisionKey(relative);
        if (collision.equals("mods") || collision.startsWith("mods/")) {
            Path output = PathSafety.resolve(gameDirectory, relative);
            if (loaderJar == null || !output.equals(loaderJar)) {
                validateOutput(relative);
            }
            protectedPaths.add(collision);
        }
    }

    private static boolean isLoaderRuntimeJar(Path file) {
        String name = file.getFileName() == null
            ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".jar")) {
            return false;
        }
        if (name.contains("relauncher")) {
            return true;
        }
        try (ZipFile archive = new ZipFile(file.toFile())) {
            return archive.getEntry(
                "com/juanmuscaria/relauncher/Relauncher.class") != null;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private long estimateCommitBackupBytes(
        Set<String> stagedPaths, Set<String> removals) throws LoaderException {

        Set<String> affected = new LinkedHashSet<String>();
        affected.addAll(stagedPaths);
        affected.addAll(removals);
        long result = 0L;
        for (String relative : affected) {
            PathSafety.rejectSymlinkAncestors(gameDirectory, relative);
            Path existing = PathSafety.resolve(gameDirectory, relative);
            if (Files.isRegularFile(existing, LinkOption.NOFOLLOW_LINKS)) {
                result = DiskSpace.add(result,
                    fileSize(existing, "file for transactional rollback: " + relative));
            }
        }
        Path state = dataDirectory.resolve("state.json");
        if (Files.isRegularFile(state, LinkOption.NOFOLLOW_LINKS)) {
            result = DiskSpace.add(result,
                fileSize(state, "installed state for transactional rollback"));
        }
        return result;
    }

    private boolean isReusable(
        BootstrapManifest.Package item, InstalledState.Receipt receipt) throws LoaderException {

        return isReusable(item, receipt, false);
    }

    private boolean isReusable(
        BootstrapManifest.Package item,
        InstalledState.Receipt receipt,
        boolean forceOutputInspection) throws LoaderException {

        if (receipt == null || !item.version.equals(receipt.version) || receipt.md5 == null ||
            !item.download.md5.equalsIgnoreCase(receipt.md5) ||
            !installKey(item).equals(receipt.installKey) || receipt.files == null ||
            receipt.files.isEmpty() || receipt.hashes == null || receipt.hashes.isEmpty()) {
            return false;
        }
        boolean inspectOutputs = forceOutputInspection || item.enforcesOnLaunch();
        if (receipt.fileMetadata == null) {
            receipt.fileMetadata =
                new LinkedHashMap<String, InstalledState.FileMetadata>();
        }
        for (String value : receipt.files) {
            String relative = PathSafety.normalizeRelative(value, false);
            validateOutput(relative);
            String expectedHash = receipt.hashes.get(relative);
            if (expectedHash == null || !expectedHash.matches("[0-9a-fA-F]{32}")) {
                return false;
            }
            if (!inspectOutputs) {
                continue;
            }
            PathSafety.rejectSymlinkAncestors(gameDirectory, relative);
            Path file = PathSafety.resolve(gameDirectory, relative);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            InstalledState.FileMetadata metadata = receipt.fileMetadata.get(relative);
            if (!alwaysHashFiles && matchesFileMetadata(relative, file, metadata)) {
                continue;
            }
            if (!expectedHash.equalsIgnoreCase(md5(file))) {
                return false;
            }
            receipt.fileMetadata.put(relative, readFileMetadata(relative, file));
        }
        return true;
    }

    private static boolean matchesFileMetadata(
        String relative, Path file, InstalledState.FileMetadata expected)
        throws LoaderException {

        if (expected == null || expected.path == null ||
            !relative.equals(expected.path)) {
            return false;
        }
        InstalledState.FileMetadata actual = readFileMetadata(relative, file);
        return actual.size == expected.size &&
            actual.lastModifiedNanos == expected.lastModifiedNanos;
    }

    private static InstalledState.FileMetadata readFileMetadata(
        String relative, Path file) throws LoaderException {

        try {
            BasicFileAttributes attributes = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || Files.isSymbolicLink(file)) {
                throw new LoaderException("Managed output is not a safe regular file: " + file);
            }
            return new InstalledState.FileMetadata(
                relative, attributes.size(),
                attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS));
        } catch (LoaderException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new LoaderException("Could not inspect file metadata: " + file, e);
        }
    }

    private void validateOutput(String relative) throws LoaderException {
        Path output = PathSafety.resolve(gameDirectory, relative);
        if (output.startsWith(dataDirectory) || output.equals(loaderJar) ||
            "config/solderpy-loader.json".equals(PathSafety.collisionKey(relative))) {
            throw new LoaderException("Package attempts to modify a reserved loader path: " + relative);
        }
    }

    private boolean matchesExistingFile(String relative, String expectedMd5)
        throws LoaderException {

        PathSafety.rejectSymlinkAncestors(gameDirectory, relative);
        Path existing = PathSafety.resolve(gameDirectory, relative);
        return Files.isRegularFile(existing, LinkOption.NOFOLLOW_LINKS) &&
            expectedMd5.equalsIgnoreCase(md5(existing));
    }

    private static void mergeStagedTree(Path sourceDirectory, Path destinationDirectory)
        throws IOException, LoaderException {

        if (!Files.isDirectory(sourceDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.createDirectories(destinationDirectory);
        try (DirectoryStream<Path> children = Files.newDirectoryStream(sourceDirectory)) {
            for (Path source : children) {
                if (Files.isSymbolicLink(source)) {
                    throw new LoaderException("Package staging produced a symbolic link: " + source);
                }
                Path destination = destinationDirectory.resolve(source.getFileName().toString());
                if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                        move(source, destination);
                    } else if (Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
                        mergeStagedTree(source, destination);
                    } else {
                        throw new LoaderException(
                            "Package staging collides with a non-directory path: " + destination);
                    }
                } else if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                        throw new LoaderException(
                            "Package staging unexpectedly collided at " + destination);
                    }
                    move(source, destination);
                } else {
                    throw new LoaderException("Package staging produced a non-regular file: " + source);
                }
            }
        }
        try {
            Files.deleteIfExists(sourceDirectory);
        } catch (java.nio.file.DirectoryNotEmptyException ignored) {
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

    private DownloadBatch downloadAll(
        List<BootstrapManifest.Package> pending, Path downloadDirectory) throws LoaderException {

        Map<String, DownloadManager.DownloadedFile> result =
            new LinkedHashMap<String, DownloadManager.DownloadedFile>();
        if (pending.isEmpty()) {
            LoaderLog.info("All selected packages are already installed and verified");
            return new DownloadBatch(result, null);
        }

        int workers = Math.min(concurrentDownloads, pending.size());
        progress.beginDownloads(pending, workers);
        ExecutorService executor = Executors.newFixedThreadPool(
            workers, workerThreadFactory("solderpy-download-"));
        Map<BootstrapManifest.Package, Future<DownloadManager.DownloadedFile>> futures =
            new LinkedHashMap<BootstrapManifest.Package, Future<DownloadManager.DownloadedFile>>();
        LoaderException firstFailure = null;
        try {
            for (BootstrapManifest.Package item : pending) {
                futures.put(item, executor.submit(
                    () -> downloads.downloadFile(item, downloadDirectory, progress)));
            }
            for (Map.Entry<BootstrapManifest.Package, Future<DownloadManager.DownloadedFile>> entry :
                futures.entrySet()) {
                try {
                    result.put(entry.getKey().name, entry.getValue().get());
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    LoaderException failure = packageFailure(
                        "Download", entry.getKey(), cause);
                    if (firstFailure == null) {
                        firstFailure = failure;
                    }
                }
            }
            return new DownloadBatch(result, firstFailure);
        } catch (InterruptedException e) {
            cancelWorkers(futures);
            Thread.currentThread().interrupt();
            throw new LoaderException("Interrupted while downloading modpack packages", e);
        } finally {
            stopExecutor(executor, "download");
        }
    }

    private Map<String, DownloadManager.DownloadedFile> verifyAll(
        List<BootstrapManifest.Package> pending,
        Map<String, DownloadManager.DownloadedFile> downloaded,
        LoaderException downloadFailure) throws LoaderException {

        if (pending.isEmpty()) {
            return downloaded;
        }
        progress.beginVerification(pending);
        Map<String, DownloadManager.DownloadedFile> verified =
            new LinkedHashMap<String, DownloadManager.DownloadedFile>();
        LoaderException firstFailure = downloadFailure;
        for (BootstrapManifest.Package item : pending) {
            DownloadManager.DownloadedFile file = downloaded.get(item.name);
            if (file == null) {
                if (firstFailure == null) {
                    firstFailure = packageFailure("Verification", item,
                        new LoaderException("The downloaded artifact is missing"));
                }
                continue;
            }
            try {
                verified.put(item.name, downloads.verifyDownloaded(item, file, progress));
            } catch (LoaderException failure) {
                if (Thread.currentThread().isInterrupted()) {
                    throw failure;
                }
                if (firstFailure == null) {
                    firstFailure = packageFailure("Verification", item, failure);
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
        return verified;
    }

    private Map<String, StagedPackage> stageAll(
        List<BootstrapManifest.Package> pending,
        Map<String, DownloadManager.DownloadedFile> downloaded,
        Path packagesDirectory,
        InstalledState previous,
        List<BootstrapManifest.Package> selected) throws LoaderException {

        Map<String, StagedPackage> result = new LinkedHashMap<String, StagedPackage>();
        if (pending.isEmpty()) {
            return result;
        }

        progress.phase("Inspecting downloaded packages and checking disk space...");
        Map<String, SafeZipExtractor.Prepared> preparedZips =
            new LinkedHashMap<String, SafeZipExtractor.Prepared>();
        Set<String> newOutputPaths = new LinkedHashSet<String>();
        long stagingBytes = 0L;
        for (BootstrapManifest.Package item : pending) {
            DownloadManager.DownloadedFile archive = downloaded.get(item.name);
            if (archive == null) {
                throw packageFailure("Staging", item,
                    new LoaderException("The verified artifact is missing"));
            }
            if ("jar".equals(item.download.format)) {
                stagingBytes = DiskSpace.add(stagingBytes, archive.bytes);
                newOutputPaths.add(PathSafety.normalizeRelative(item.download.path, false));
            } else if ("solder_zip".equals(item.download.format)) {
                try {
                    SafeZipExtractor.Prepared prepared =
                        zipExtractor.prepare(archive.path, item.download.extractTo);
                    preparedZips.put(item.name, prepared);
                    stagingBytes = DiskSpace.add(stagingBytes, prepared.expandedBytes());
                    newOutputPaths.addAll(prepared.outputFiles());
                } catch (LoaderException failure) {
                    throw packageFailure("ZIP inspection", item, failure);
                }
            } else {
                throw packageFailure("Staging", item,
                    new LoaderException("Unsupported package format: " + item.download.format));
            }
        }
        long rollbackBytes = estimateRollbackBytes(
            previous, selected, pending, newOutputPaths);
        DiskSpace.require(dataDirectory, DiskSpace.add(stagingBytes, rollbackBytes),
            "stage and safely commit " + pending.size() + " changed package(s)");

        int workers = Math.min(concurrentExtractions, pending.size());
        progress.beginInstallation(pending, workers);
        ExecutorService executor = Executors.newFixedThreadPool(
            workers, workerThreadFactory("solderpy-extract-"));
        Map<BootstrapManifest.Package, Future<StagedPackage>> futures =
            new LinkedHashMap<BootstrapManifest.Package, Future<StagedPackage>>();
        try {
            for (int index = 0; index < pending.size(); index++) {
                BootstrapManifest.Package item = pending.get(index);
                DownloadManager.DownloadedFile archive = downloaded.get(item.name);
                if (archive == null) {
                    throw new LoaderException("Downloaded package is missing from staging: " + item.name);
                }
                Path packageRoot = packagesDirectory.resolve(Integer.toString(index));
                futures.put(item, executor.submit(
                    () -> stageDownloaded(item, archive, packageRoot,
                        preparedZips.get(item.name))));
            }
            for (Map.Entry<BootstrapManifest.Package, Future<StagedPackage>> entry :
                futures.entrySet()) {
                try {
                    result.put(entry.getKey().name, entry.getValue().get());
                } catch (ExecutionException e) {
                    cancelWorkers(futures);
                    Throwable cause = e.getCause();
                    throw packageFailure("Staging", entry.getKey(), cause);
                }
            }
            return result;
        } catch (InterruptedException e) {
            cancelWorkers(futures);
            Thread.currentThread().interrupt();
            throw new LoaderException("Interrupted while staging modpack packages", e);
        } finally {
            stopExecutor(executor, "extraction");
        }
    }

    private StagedPackage stageDownloaded(
        BootstrapManifest.Package item,
        DownloadManager.DownloadedFile archive,
        Path packageRoot,
        SafeZipExtractor.Prepared preparedZip) throws LoaderException {

        try {
            if ("jar".equals(item.download.format)) {
                progress.installing(item);
                String relative = PathSafety.normalizeRelative(item.download.path, false);
                Path output = PathSafety.resolve(packageRoot, relative);
                Files.createDirectories(output.getParent());
                if (archive.persistent) {
                    Files.copy(archive.path, output, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(archive.path, output, StandardCopyOption.REPLACE_EXISTING);
                }
                Map<String, String> hashes = new LinkedHashMap<String, String>();
                hashes.put(relative, archive.md5);
                progress.installed(item);
                return new StagedPackage(packageRoot,
                    Collections.singletonList(relative), hashes);
            }
            if ("solder_zip".equals(item.download.format)) {
                if (preparedZip == null) {
                    throw new LoaderException("The inspected ZIP plan is missing");
                }
                progress.installing(item, preparedZip.action(), preparedZip.detail());
                SafeZipExtractor.Extraction extraction = preparedZip.extract(
                    packageRoot, (action, detail) ->
                        progress.installing(item, action, detail));
                progress.installed(item);
                return new StagedPackage(packageRoot, extraction.files, extraction.hashes);
            }
            throw new LoaderException("Unsupported package format: " + item.download.format);
        } catch (IOException e) {
            throw new LoaderException("Could not stage package " + item.name, e);
        } finally {
            if (!archive.persistent) {
                try {
                    Files.deleteIfExists(archive.path);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private long estimateRollbackBytes(
        InstalledState previous,
        List<BootstrapManifest.Package> selected,
        List<BootstrapManifest.Package> pending,
        Set<String> newOutputPaths) throws LoaderException {

        Set<String> selectedNames = new LinkedHashSet<String>();
        for (BootstrapManifest.Package item : selected) {
            selectedNames.add(item.name);
        }
        Set<String> pendingNames = new LinkedHashSet<String>();
        for (BootstrapManifest.Package item : pending) {
            pendingNames.add(item.name);
        }

        Map<String, String> affected = new LinkedHashMap<String, String>();
        for (String value : newOutputPaths) {
            addAffectedPath(affected, value);
        }
        if (previous != null && previous.receipts != null) {
            for (Map.Entry<String, InstalledState.Receipt> entry :
                previous.receipts.entrySet()) {

                if (!pendingNames.contains(entry.getKey()) &&
                    selectedNames.contains(entry.getKey())) {
                    continue;
                }
                InstalledState.Receipt receipt = entry.getValue();
                if (receipt == null || receipt.files == null) {
                    continue;
                }
                for (String value : receipt.files) {
                    addAffectedPath(affected, value);
                }
            }
        }

        long result = 0L;
        for (String relative : affected.values()) {
            PathSafety.rejectSymlinkAncestors(gameDirectory, relative);
            Path existing = PathSafety.resolve(gameDirectory, relative);
            if (Files.isRegularFile(existing, LinkOption.NOFOLLOW_LINKS)) {
                result = DiskSpace.add(result, fileSize(existing,
                    "existing file for rollback: " + relative));
            }
        }
        Path state = dataDirectory.resolve("state.json");
        if (Files.isRegularFile(state, LinkOption.NOFOLLOW_LINKS)) {
            result = DiskSpace.add(result, fileSize(state, "installed state for rollback"));
        }
        return result;
    }

    private void addAffectedPath(Map<String, String> affected, String value)
        throws LoaderException {

        String relative = PathSafety.normalizeRelative(value, false);
        validateOutput(relative);
        affected.put(PathSafety.collisionKey(relative), relative);
    }

    private static long fileSize(Path file, String description) throws LoaderException {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new LoaderException("Could not measure " + description, e);
        }
    }

    private static LoaderException packageFailure(
        String stage, BootstrapManifest.Package item, Throwable cause) {

        String reason = cause == null || cause.getMessage() == null ||
            cause.getMessage().trim().isEmpty()
            ? (cause == null ? "Unknown failure" : cause.toString())
            : cause.getMessage();
        String prefix = stage + " failed for " + packageIdentity(item);
        if (reason.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return new LoaderException(reason, cause);
        }
        return new LoaderException(prefix + ": " + reason, cause);
    }

    private static String packageIdentity(BootstrapManifest.Package item) {
        String pretty = item.prettyName == null || item.prettyName.trim().isEmpty()
            ? item.name : item.prettyName;
        StringBuilder result = new StringBuilder(pretty);
        if (item.version != null && !item.version.trim().isEmpty()) {
            result.append(' ').append(item.version);
        }
        if (item.name != null && !item.name.equals(pretty)) {
            result.append(" [").append(item.name).append(']');
        }
        return result.toString();
    }

    private static ThreadFactory workerThreadFactory(String prefix) {
        AtomicInteger number = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static <T> void cancelWorkers(
        Map<BootstrapManifest.Package, Future<T>> futures) {

        for (Future<T> future : futures.values()) {
            future.cancel(true);
        }
    }

    private static void stopExecutor(ExecutorService executor, String workerType) {
        executor.shutdownNow();
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(65);
        while (!executor.isTerminated()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                LoaderLog.warn("Timed out while stopping package " + workerType + " workers");
                break;
            }
            try {
                executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
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

    private static String md5(Path file) throws LoaderException {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = HASH_BUFFER.get();
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return hex(digest.digest());
        } catch (IOException e) {
            throw new LoaderException("Could not verify installed file " + file, e);
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

    private static final class DownloadBatch {
        private final Map<String, DownloadManager.DownloadedFile> files;
        private final LoaderException failure;

        private DownloadBatch(
            Map<String, DownloadManager.DownloadedFile> files,
            LoaderException failure) {

            this.files = files;
            this.failure = failure;
        }
    }

    private static final class StagedPackage {
        private final Path root;
        private final List<String> files;
        private final Map<String, String> hashes;

        private StagedPackage(Path root, List<String> files, Map<String, String> hashes) {
            this.root = root;
            this.files = new ArrayList<String>(files);
            this.hashes = new LinkedHashMap<String, String>(hashes);
        }
    }

}
