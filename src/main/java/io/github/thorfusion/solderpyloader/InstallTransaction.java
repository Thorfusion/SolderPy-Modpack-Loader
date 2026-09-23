package io.github.thorfusion.solderpyloader;

import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Crash-recoverable commit journal for changes to the live game directory. */
final class InstallTransaction {
    static final String TRANSACTIONS_DIRECTORY = "transactions";
    static final String TRANSACTION_PREFIX = "transaction-";
    static final String JOURNAL_FILE = "transaction.json";
    static final String BACKUPS_READY_FILE = "backups.ready";
    static final String COMMIT_COMPLETE_FILE = "commit.complete";

    private static final int JOURNAL_VERSION = 1;
    private static final String STATE_FILE = "state.json";
    private static final String STATE_BACKUP_FILE = "state-before.json";
    private static final String BACKUP_DIRECTORY = "backup/files";

    enum CommitPoint {
        BACKUPS_READY,
        FILES_APPLIED,
        STATE_SAVED,
        COMMIT_MARKED
    }

    interface CommitObserver {
        void reached(CommitPoint point);
    }

    private InstallTransaction() {
    }

    static Path create(Path dataDirectory) throws LoaderException {
        Path root = dataDirectory.toAbsolutePath().normalize()
            .resolve(TRANSACTIONS_DIRECTORY);
        try {
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root) ||
                !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new LoaderException(
                    "Transaction storage is not a safe directory: " + root);
            }
            return Files.createDirectory(
                root.resolve(TRANSACTION_PREFIX + UUID.randomUUID().toString()));
        } catch (LoaderException e) {
            throw e;
        } catch (IOException e) {
            throw new LoaderException("Could not create the update transaction", e);
        }
    }

    static void commit(
        Path gameDirectory,
        Path dataDirectory,
        Path loaderJar,
        Path transaction,
        Path content,
        Set<String> stagedPaths,
        Set<String> removals,
        InstalledState next) throws LoaderException {

        commit(gameDirectory, dataDirectory, loaderJar, transaction, content,
            stagedPaths, removals, next, null);
    }

    static void commit(
        Path gameDirectory,
        Path dataDirectory,
        Path loaderJar,
        Path transaction,
        Path content,
        Set<String> stagedPaths,
        Set<String> removals,
        InstalledState next,
        CommitObserver observer) throws LoaderException {

        Path normalizedGame = gameDirectory.toAbsolutePath().normalize();
        Path normalizedData = dataDirectory.toAbsolutePath().normalize();
        Path normalizedLoader = loaderJar == null
            ? null : loaderJar.toAbsolutePath().normalize();
        Path normalizedTransaction = transaction.toAbsolutePath().normalize();
        Path normalizedContent = content.toAbsolutePath().normalize();
        if (!normalizedTransaction.startsWith(
                normalizedData.resolve(TRANSACTIONS_DIRECTORY)) ||
            !normalizedContent.startsWith(normalizedTransaction)) {
            throw new LoaderException("Update transaction paths are outside transaction storage");
        }

        try {
            Journal journal = createJournal(
                normalizedGame, normalizedData, normalizedLoader,
                stagedPaths, removals);
            writeJournal(normalizedTransaction, journal);
            createBackups(
                normalizedGame, normalizedData, normalizedLoader,
                normalizedTransaction, journal);
            writeMarker(normalizedTransaction.resolve(BACKUPS_READY_FILE));
            notifyObserver(observer, CommitPoint.BACKUPS_READY);

            applyFiles(normalizedGame, normalizedData, normalizedLoader,
                normalizedTransaction, normalizedContent, journal);
            notifyObserver(observer, CommitPoint.FILES_APPLIED);

            next.save(normalizedData);
            forceFile(normalizedData.resolve(STATE_FILE));
            notifyObserver(observer, CommitPoint.STATE_SAVED);

            writeMarker(normalizedTransaction.resolve(COMMIT_COMPLETE_FILE));
            notifyObserver(observer, CommitPoint.COMMIT_MARKED);
            discard(normalizedTransaction);
        } catch (Exception failure) {
            try {
                recoverOne(normalizedGame, normalizedData, normalizedLoader,
                    normalizedTransaction);
            } catch (LoaderException recoveryFailure) {
                UnrecoverableBootstrapException unrecoverable =
                    new UnrecoverableBootstrapException(
                        "Update failed and the persistent transaction could not be rolled back",
                        recoveryFailure);
                unrecoverable.addSuppressed(failure);
                throw unrecoverable;
            }
            throw new RecoverableBootstrapException(
                "Could not commit the modpack update; previous files were restored", failure);
        }
    }

    static void recoverPending(
        Path gameDirectory, Path dataDirectory, Path loaderJar) throws LoaderException {

        Path normalizedGame = gameDirectory.toAbsolutePath().normalize();
        Path normalizedData = dataDirectory.toAbsolutePath().normalize();
        Path normalizedLoader = loaderJar == null
            ? null : loaderJar.toAbsolutePath().normalize();
        Path root = normalizedData.resolve(TRANSACTIONS_DIRECTORY);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(root) ||
            !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new LoaderException(
                "Transaction storage is not a safe directory: " + root);
        }

        List<Path> pending = new ArrayList<Path>();
        try (DirectoryStream<Path> children =
                 Files.newDirectoryStream(root, TRANSACTION_PREFIX + "*")) {
            for (Path child : children) {
                if (Files.isSymbolicLink(child) ||
                    !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    throw new LoaderException(
                        "Unsafe entry in transaction storage: " + child);
                }
                pending.add(child);
            }
        } catch (LoaderException e) {
            throw e;
        } catch (IOException e) {
            throw new LoaderException("Could not inspect interrupted update transactions", e);
        }

        pending.sort(Comparator.comparing(path -> path.getFileName().toString()));
        for (Path transaction : pending) {
            recoverOne(normalizedGame, normalizedData, normalizedLoader, transaction);
        }
    }

    static void discard(Path transaction) {
        if (transaction == null ||
            !Files.exists(transaction, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(transaction)) {
            List<Path> ordered = paths.sorted(Comparator.reverseOrder())
                .collect(Collectors.toList());
            for (Path path : ordered) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static Journal createJournal(
        Path gameDirectory,
        Path dataDirectory,
        Path loaderJar,
        Set<String> stagedPaths,
        Set<String> removals) throws LoaderException {

        Journal journal = new Journal();
        journal.version = JOURNAL_VERSION;
        Path state = dataDirectory.resolve(STATE_FILE);
        journal.stateExisted = existingRegularFile(state, "installed state");

        for (String relative : removals) {
            journal.entries.add(createEntry(
                gameDirectory, dataDirectory, loaderJar, relative, "remove"));
        }
        for (String relative : stagedPaths) {
            if (removals.contains(relative)) {
                throw new LoaderException(
                    "Update both installs and removes the same path: " + relative);
            }
            journal.entries.add(createEntry(
                gameDirectory, dataDirectory, loaderJar, relative, "install"));
        }
        return journal;
    }

    private static Entry createEntry(
        Path gameDirectory,
        Path dataDirectory,
        Path loaderJar,
        String relative,
        String action) throws LoaderException {

        String normalized = validateOutput(
            gameDirectory, dataDirectory, loaderJar, relative);
        PathSafety.rejectSymlinkAncestors(gameDirectory, normalized);
        Path target = PathSafety.resolve(gameDirectory, normalized);
        Entry entry = new Entry();
        entry.path = normalized;
        entry.action = action;
        entry.existed = existingRegularFile(target, "managed output");
        return entry;
    }

    private static boolean existingRegularFile(Path path, String description)
        throws LoaderException {

        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (Files.isSymbolicLink(path) ||
            !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new LoaderException(
                "The " + description + " is not a regular file: " + path);
        }
        return true;
    }

    private static void createBackups(
        Path gameDirectory,
        Path dataDirectory,
        Path loaderJar,
        Path transaction,
        Journal journal) throws LoaderException, IOException {

        Path backupRoot = transaction.resolve(BACKUP_DIRECTORY);
        for (Entry entry : journal.entries) {
            validateEntry(gameDirectory, dataDirectory, loaderJar, entry);
            Path target = PathSafety.resolve(gameDirectory, entry.path);
            boolean exists = existingRegularFile(target, "managed output");
            if (exists != entry.existed) {
                throw new LoaderException(
                    "Managed output changed while preparing the transaction: " + target);
            }
            if (entry.existed) {
                copyDurably(target, PathSafety.resolve(backupRoot, entry.path));
            }
        }

        Path state = dataDirectory.resolve(STATE_FILE);
        boolean stateExists = existingRegularFile(state, "installed state");
        if (stateExists != journal.stateExisted) {
            throw new LoaderException(
                "Installed state changed while preparing the transaction: " + state);
        }
        if (journal.stateExisted) {
            copyDurably(state, transaction.resolve(STATE_BACKUP_FILE));
        }
    }

    private static void applyFiles(
        Path gameDirectory,
        Path dataDirectory,
        Path loaderJar,
        Path transaction,
        Path content,
        Journal journal) throws LoaderException, IOException {

        if (!Files.isRegularFile(transaction.resolve(BACKUPS_READY_FILE),
                LinkOption.NOFOLLOW_LINKS)) {
            throw new LoaderException("Transaction backups were not marked ready");
        }
        for (Entry entry : journal.entries) {
            validateEntry(gameDirectory, dataDirectory, loaderJar, entry);
            PathSafety.rejectSymlinkAncestors(gameDirectory, entry.path);
            Path target = PathSafety.resolve(gameDirectory, entry.path);
            if ("remove".equals(entry.action)) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new LoaderException(
                        "Owned output is not a regular file: " + target);
                }
                Files.deleteIfExists(target);
                continue;
            }

            Path source = PathSafety.resolve(content, entry.path);
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) ||
                Files.isSymbolicLink(source)) {
                throw new LoaderException(
                    "Staged package output is missing or unsafe: " + source);
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new LoaderException(
                    "Owned output is not a regular file: " + target);
            }
            Files.createDirectories(target.getParent());
            move(source, target);
            forceFile(target);
        }
    }

    private static void recoverOne(
        Path gameDirectory,
        Path dataDirectory,
        Path loaderJar,
        Path transaction) throws LoaderException {

        Path journalFile = transaction.resolve(JOURNAL_FILE);
        boolean backupsReady = Files.isRegularFile(
            transaction.resolve(BACKUPS_READY_FILE), LinkOption.NOFOLLOW_LINKS);
        boolean commitComplete = Files.isRegularFile(
            transaction.resolve(COMMIT_COMPLETE_FILE), LinkOption.NOFOLLOW_LINKS);
        if (!Files.isRegularFile(journalFile, LinkOption.NOFOLLOW_LINKS)) {
            if (backupsReady || commitComplete) {
                throw new LoaderException(
                    "An interrupted update has markers but no recovery journal: " + transaction);
            }
            LoaderLog.info("Discarding an interrupted pre-commit staging directory");
            discard(transaction);
            return;
        }

        Journal journal = readJournal(journalFile);
        validateJournal(gameDirectory, dataDirectory, loaderJar, journal);
        if (commitComplete && !backupsReady) {
            throw new LoaderException(
                "An interrupted update is committed without a backup-ready marker: " +
                    transaction);
        }
        if (commitComplete) {
            LoaderLog.info("Cleaning a completed update transaction after interrupted cleanup");
            discard(transaction);
            return;
        }
        if (!backupsReady) {
            LoaderLog.info("Discarding an update interrupted before live files were changed");
            discard(transaction);
            return;
        }

        LoaderLog.warn("An interrupted modpack commit was found; restoring the previous files");
        rollback(gameDirectory, dataDirectory, transaction, journal);
        discard(transaction);
        LoaderLog.info("The interrupted modpack commit was rolled back successfully");
    }

    private static void rollback(
        Path gameDirectory,
        Path dataDirectory,
        Path transaction,
        Journal journal) throws LoaderException {

        Path backupRoot = transaction.resolve(BACKUP_DIRECTORY);
        Path stateBackup = transaction.resolve(STATE_BACKUP_FILE);
        try {
            for (Entry entry : journal.entries) {
                if (entry.existed) {
                    Path saved = PathSafety.resolve(backupRoot, entry.path);
                    if (!Files.isRegularFile(saved, LinkOption.NOFOLLOW_LINKS) ||
                        Files.isSymbolicLink(saved)) {
                        throw new LoaderException(
                            "Transaction backup is missing or unsafe: " + saved);
                    }
                }
            }
            if (journal.stateExisted &&
                (!Files.isRegularFile(stateBackup, LinkOption.NOFOLLOW_LINKS) ||
                    Files.isSymbolicLink(stateBackup))) {
                throw new LoaderException(
                    "Transaction state backup is missing or unsafe: " + stateBackup);
            }

            for (Entry entry : journal.entries) {
                PathSafety.rejectSymlinkAncestors(gameDirectory, entry.path);
                Path target = PathSafety.resolve(gameDirectory, entry.path);
                if (entry.existed) {
                    copyDurably(PathSafety.resolve(backupRoot, entry.path), target);
                } else {
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) &&
                        !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                        throw new LoaderException(
                            "Cannot remove unsafe interrupted output: " + target);
                    }
                    Files.deleteIfExists(target);
                }
            }

            Path state = dataDirectory.resolve(STATE_FILE);
            if (Files.exists(state, LinkOption.NOFOLLOW_LINKS) &&
                (Files.isSymbolicLink(state) ||
                    !Files.isRegularFile(state, LinkOption.NOFOLLOW_LINKS))) {
                throw new LoaderException(
                    "Cannot restore over unsafe interrupted installed state: " + state);
            }
            if (journal.stateExisted) {
                copyDurably(stateBackup, state);
            } else {
                Files.deleteIfExists(state);
            }
        } catch (LoaderException e) {
            throw e;
        } catch (IOException e) {
            throw new LoaderException("Could not roll back the interrupted update", e);
        }
    }

    private static void validateJournal(
        Path gameDirectory,
        Path dataDirectory,
        Path loaderJar,
        Journal journal) throws LoaderException {

        if (journal == null || journal.version != JOURNAL_VERSION ||
            journal.entries == null) {
            throw new LoaderException("Interrupted update has an unsupported recovery journal");
        }
        for (Entry entry : journal.entries) {
            validateEntry(gameDirectory, dataDirectory, loaderJar, entry);
        }
    }

    private static void validateEntry(
        Path gameDirectory,
        Path dataDirectory,
        Path loaderJar,
        Entry entry) throws LoaderException {

        if (entry == null ||
            !("install".equals(entry.action) || "remove".equals(entry.action))) {
            throw new LoaderException("Interrupted update has an invalid recovery entry");
        }
        String normalized = validateOutput(
            gameDirectory, dataDirectory, loaderJar, entry.path);
        if (!normalized.equals(entry.path)) {
            throw new LoaderException(
                "Interrupted update contains a non-normalized path: " + entry.path);
        }
    }

    private static String validateOutput(
        Path gameDirectory,
        Path dataDirectory,
        Path loaderJar,
        String relative) throws LoaderException {

        String normalized = PathSafety.normalizeRelative(relative, false);
        Path output = PathSafety.resolve(gameDirectory, normalized);
        if (output.startsWith(dataDirectory) ||
            (loaderJar != null && output.equals(loaderJar)) ||
            "config/solderpy-loader.json".equals(PathSafety.collisionKey(normalized))) {
            throw new LoaderException(
                "Transaction attempts to modify a reserved loader path: " + relative);
        }
        return normalized;
    }

    private static void writeJournal(Path transaction, Journal journal)
        throws LoaderException {

        Path temporary = null;
        try {
            temporary = Files.createTempFile(transaction, "journal-", ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                JsonSupport.GSON.toJson(journal, writer);
            }
            forceFile(temporary);
            move(temporary, transaction.resolve(JOURNAL_FILE));
            temporary = null;
            forceFile(transaction.resolve(JOURNAL_FILE));
        } catch (IOException | RuntimeException e) {
            throw new LoaderException("Could not persist the update recovery journal", e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static Journal readJournal(Path file) throws LoaderException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonSupport.GSON.fromJson(reader, Journal.class);
        } catch (JsonParseException e) {
            throw new LoaderException("Interrupted update recovery journal is invalid", e);
        } catch (IOException e) {
            throw new LoaderException("Could not read interrupted update recovery journal", e);
        }
    }

    private static void writeMarker(Path marker) throws IOException {
        try (FileChannel channel = FileChannel.open(marker,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.write(StandardCharsets.US_ASCII.encode("ready\n"));
            channel.force(true);
        }
    }

    private static void copyDurably(Path source, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        forceFile(destination);
    }

    private static void forceFile(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
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

    private static void notifyObserver(CommitObserver observer, CommitPoint point) {
        if (observer != null) {
            observer.reached(point);
        }
    }

    private static final class Journal {
        private int version;
        private boolean stateExisted;
        private List<Entry> entries = new ArrayList<Entry>();
    }

    private static final class Entry {
        private String path;
        private String action;
        private boolean existed;
    }
}
