package io.github.thorfusion.solderpyloader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class Installer {
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final ThreadLocal<byte[]> MD5_BUFFER =
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

    Installer(Path gameDirectory, Path dataDirectory, LoaderConfig.Limits limits) {
        this(gameDirectory, dataDirectory, limits, null, BootstrapProgress.console());
    }

    Installer(Path gameDirectory, Path dataDirectory, LoaderConfig.Limits limits, Path loaderJar) {
        this(gameDirectory, dataDirectory, limits, loaderJar, BootstrapProgress.console());
    }

    Installer(
        Path gameDirectory,
        Path dataDirectory,
        LoaderConfig.Limits limits,
        Path loaderJar,
        BootstrapProgress progress) {

        this.gameDirectory = gameDirectory.toAbsolutePath().normalize();
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
        this.loaderJar = loaderJar == null ? null : loaderJar.toAbsolutePath().normalize();
        this.downloads = new DownloadManager(limits.maxDownloadBytes);
        this.zipExtractor = new SafeZipExtractor(limits.maxExpandedBytes, limits.maxArchiveEntries);
        this.concurrentDownloads = limits.maxConcurrentDownloads;
        this.concurrentExtractions = limits.maxConcurrentExtractions;
        this.progress = progress;
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

        reconcile(selected, selected, previous, next);
    }

    void reconcile(
        List<BootstrapManifest.Package> selected,
        List<BootstrapManifest.Package> allPackages,
        InstalledState previous,
        InstalledState next) throws LoaderException {

        Path transaction = dataDirectory.resolve("staging-" + UUID.randomUUID().toString());
        Path content = transaction.resolve("content");
        Path downloadDirectory = transaction.resolve("downloads");
        Path packagesDirectory = transaction.resolve("packages");
        Path backup = transaction.resolve("backup");
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
            Set<String> reusablePackages = new LinkedHashSet<String>();
            List<BootstrapManifest.Package> pendingDownloads =
                new ArrayList<BootstrapManifest.Package>();

            for (BootstrapManifest.Package item : selected) {
                InstalledState.Receipt oldReceipt = previous.receipts.get(item.name);
                if (isReusable(item, oldReceipt)) {
                    reusablePackages.add(item.name);
                } else {
                    pendingDownloads.add(item);
                }
            }

            Map<String, DownloadManager.DownloadedFile> downloaded =
                downloadAll(pendingDownloads, downloadDirectory);
            downloaded = verifyAll(pendingDownloads, downloaded);
            Map<String, StagedPackage> stagedPackages =
                stageAll(pendingDownloads, downloaded, packagesDirectory);
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
                        StagedPackage staged = stagedPackages.get(item.name);
                        String stagedHash = staged.hashes.get(normalized);
                        if (stagedHash == null || !stagedHash.matches("[0-9a-fA-F]{32}")) {
                            throw new LoaderException("Package staging is missing an MD5 receipt for " +
                                normalized);
                        }
                        Path source = PathSafety.resolve(staged.root, normalized);
                        hashes.put(normalized, stagedHash.toLowerCase(Locale.ROOT));
                        if (matchesExistingFile(normalized, stagedHash)) {
                            Files.deleteIfExists(source);
                        } else {
                            stagedPaths.add(normalized);
                        }
                    }
                }
                if (!reusable) {
                    mergeStagedTree(stagedPackages.get(item.name).root, content);
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

            next.receipts = receipts;
            progress.phase("Committing modpack update...");
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
            if (expectedHash == null || !expectedHash.matches("[0-9a-fA-F]{32}") ||
                !expectedHash.equalsIgnoreCase(md5(file))) {
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

    private Map<String, DownloadManager.DownloadedFile> downloadAll(
        List<BootstrapManifest.Package> pending, Path downloadDirectory) throws LoaderException {

        Map<String, DownloadManager.DownloadedFile> result =
            new LinkedHashMap<String, DownloadManager.DownloadedFile>();
        if (pending.isEmpty()) {
            LoaderLog.info("All selected packages are already installed and verified");
            return result;
        }

        int workers = Math.min(concurrentDownloads, pending.size());
        progress.beginDownloads(pending, workers);
        ExecutorService executor = Executors.newFixedThreadPool(
            workers, workerThreadFactory("solderpy-download-"));
        Map<BootstrapManifest.Package, Future<DownloadManager.DownloadedFile>> futures =
            new LinkedHashMap<BootstrapManifest.Package, Future<DownloadManager.DownloadedFile>>();
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
                    cancelWorkers(futures);
                    Throwable cause = e.getCause();
                    if (cause instanceof LoaderException) {
                        throw (LoaderException) cause;
                    }
                    throw new LoaderException(
                        "Unexpected failure while downloading " + entry.getKey().name, cause);
                }
            }
            return result;
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
        Map<String, DownloadManager.DownloadedFile> downloaded) throws LoaderException {

        if (pending.isEmpty()) {
            return downloaded;
        }
        progress.beginVerification(pending);
        Map<String, DownloadManager.DownloadedFile> verified =
            new LinkedHashMap<String, DownloadManager.DownloadedFile>();
        for (BootstrapManifest.Package item : pending) {
            DownloadManager.DownloadedFile file = downloaded.get(item.name);
            if (file == null) {
                throw new LoaderException("Downloaded package is missing before verification: " +
                    item.name);
            }
            verified.put(item.name, downloads.verifyDownloaded(item, file, progress));
        }
        return verified;
    }

    private Map<String, StagedPackage> stageAll(
        List<BootstrapManifest.Package> pending,
        Map<String, DownloadManager.DownloadedFile> downloaded,
        Path packagesDirectory) throws LoaderException {

        Map<String, StagedPackage> result = new LinkedHashMap<String, StagedPackage>();
        if (pending.isEmpty()) {
            return result;
        }

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
                    () -> stageDownloaded(item, archive, packageRoot)));
            }
            for (Map.Entry<BootstrapManifest.Package, Future<StagedPackage>> entry :
                futures.entrySet()) {
                try {
                    result.put(entry.getKey().name, entry.getValue().get());
                } catch (ExecutionException e) {
                    cancelWorkers(futures);
                    Throwable cause = e.getCause();
                    if (cause instanceof LoaderException) {
                        throw (LoaderException) cause;
                    }
                    throw new LoaderException(
                        "Unexpected failure while staging " + entry.getKey().name, cause);
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
        Path packageRoot) throws LoaderException {

        try {
            if ("jar".equals(item.download.format)) {
                progress.installing(item);
                String relative = PathSafety.normalizeRelative(item.download.path, false);
                Path output = PathSafety.resolve(packageRoot, relative);
                Files.createDirectories(output.getParent());
                Files.move(archive.path, output, StandardCopyOption.REPLACE_EXISTING);
                Map<String, String> hashes = new LinkedHashMap<String, String>();
                hashes.put(relative, archive.md5);
                progress.installed(item);
                return new StagedPackage(packageRoot,
                    Collections.singletonList(relative), hashes);
            }
            if ("solder_zip".equals(item.download.format)) {
                SafeZipExtractor.Prepared prepared =
                    zipExtractor.prepare(archive.path, item.download.extractTo);
                progress.installing(item, prepared.action());
                SafeZipExtractor.Extraction extraction = prepared.extract(packageRoot);
                progress.installed(item);
                return new StagedPackage(packageRoot, extraction.files, extraction.hashes);
            }
            throw new LoaderException("Unsupported package format: " + item.download.format);
        } catch (IOException e) {
            throw new LoaderException("Could not stage package " + item.name, e);
        } finally {
            try {
                Files.deleteIfExists(archive.path);
            } catch (IOException ignored) {
            }
        }
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
        Set<Path> preparedDirectories = new HashSet<Path>();

        try {
            for (String relative : affected) {
                PathSafety.rejectSymlinkAncestors(gameDirectory, relative);
                Path target = PathSafety.resolve(gameDirectory, relative);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                        throw new LoaderException("Owned output is not a regular file: " + target);
                    }
                    Path saved = PathSafety.resolve(backup, relative);
                    createParentDirectories(saved, preparedDirectories);
                    move(target, saved);
                    backups.put(relative, saved);
                }
            }

            for (String relative : stagedPaths) {
                Path source = PathSafety.resolve(content, relative);
                Path target = PathSafety.resolve(gameDirectory, relative);
                PathSafety.rejectSymlinkAncestors(gameDirectory, relative);
                createParentDirectories(target, preparedDirectories);
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

    private static void createParentDirectories(Path file, Set<Path> preparedDirectories)
        throws IOException {

        Path parent = file.getParent();
        if (parent != null && preparedDirectories.add(parent)) {
            Files.createDirectories(parent);
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

    private static String md5(Path file) throws LoaderException {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = MD5_BUFFER.get();
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] bytes = digest.digest();
            char[] result = new char[bytes.length * 2];
            for (int index = 0; index < bytes.length; index++) {
                int value = bytes[index] & 0xff;
                result[index * 2] = HEX[value >>> 4];
                result[index * 2 + 1] = HEX[value & 0x0f];
            }
            return new String(result);
        } catch (IOException e) {
            throw new LoaderException("Could not verify installed file " + file, e);
        } catch (NoSuchAlgorithmException e) {
            throw new LoaderException("This Java runtime does not provide MD5", e);
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
