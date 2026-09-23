package io.github.thorfusion.solderpyloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallTransactionTest {
    @TempDir Path gameDirectory;

    @Test
    void nextLaunchRollsBackCrashAfterLiveFilesChanged() throws Exception {
        Fixture fixture = fixture();

        crashCommit(fixture, InstallTransaction.CommitPoint.FILES_APPLIED);

        assertEquals("new", read(fixture.changed));
        assertFalse(Files.exists(fixture.removed));
        assertEquals("added", read(fixture.added));
        assertEquals("old", InstalledState.load(fixture.data).build);

        recover(fixture);

        assertEquals("old", read(fixture.changed));
        assertEquals("removed", read(fixture.removed));
        assertFalse(Files.exists(fixture.added));
        assertEquals("old", InstalledState.load(fixture.data).build);
        assertNoTransactions(fixture.data);
    }

    @Test
    void nextLaunchRestoresOldStateAfterCrashFollowingStateSave() throws Exception {
        Fixture fixture = fixture();

        crashCommit(fixture, InstallTransaction.CommitPoint.STATE_SAVED);

        assertEquals("new", read(fixture.changed));
        assertEquals("new", InstalledState.load(fixture.data).build);

        recover(fixture);

        assertEquals("old", read(fixture.changed));
        assertEquals("removed", read(fixture.removed));
        assertFalse(Files.exists(fixture.added));
        assertEquals("old", InstalledState.load(fixture.data).build);
        assertNoTransactions(fixture.data);
    }

    @Test
    void completedCommitSurvivesCrashDuringTransactionCleanup() throws Exception {
        Fixture fixture = fixture();

        crashCommit(fixture, InstallTransaction.CommitPoint.COMMIT_MARKED);

        recover(fixture);

        assertEquals("new", read(fixture.changed));
        assertFalse(Files.exists(fixture.removed));
        assertEquals("added", read(fixture.added));
        assertEquals("new", InstalledState.load(fixture.data).build);
        assertNoTransactions(fixture.data);
    }

    @Test
    void handledCommitFailureRollsBackImmediately() throws Exception {
        Fixture fixture = fixture();

        assertThrows(RecoverableBootstrapException.class, () ->
            InstallTransaction.commit(
                gameDirectory, fixture.data, null, fixture.transaction,
                fixture.content, fixture.staged, fixture.removals, fixture.next,
                point -> {
                    if (point == InstallTransaction.CommitPoint.FILES_APPLIED) {
                        throw new IllegalStateException("simulated commit failure");
                    }
                }));

        assertEquals("old", read(fixture.changed));
        assertEquals("removed", read(fixture.removed));
        assertFalse(Files.exists(fixture.added));
        assertEquals("old", InstalledState.load(fixture.data).build);
        assertNoTransactions(fixture.data);
    }

    @Test
    void nextLaunchDiscardsStagingThatNeverReachedCommit() throws Exception {
        Path data = gameDirectory.resolve(".solderpy-loader");
        Path transaction = InstallTransaction.create(data);
        write(transaction.resolve("content/incomplete.bin"), "incomplete");

        InstallTransaction.recoverPending(gameDirectory, data, null);

        assertFalse(Files.exists(transaction));
        assertNoTransactions(data);
    }

    @Test
    void damagedBackupStopsRecoveryAndPreservesTransactionEvidence() throws Exception {
        Fixture fixture = fixture();
        crashCommit(fixture, InstallTransaction.CommitPoint.STATE_SAVED);
        Files.delete(fixture.transaction.resolve("backup/files/config/changed.cfg"));

        assertThrows(LoaderException.class, () -> recover(fixture));

        assertEquals("new", read(fixture.changed));
        assertEquals("new", InstalledState.load(fixture.data).build);
        assertTrue(Files.isDirectory(fixture.transaction));
    }

    private Fixture fixture() throws Exception {
        Path data = gameDirectory.resolve(".solderpy-loader");
        Path changed = gameDirectory.resolve("config/changed.cfg");
        Path removed = gameDirectory.resolve("mods/removed.jar");
        Path added = gameDirectory.resolve("mods/added.jar");
        write(changed, "old");
        write(removed, "removed");

        InstalledState old = new InstalledState();
        old.build = "old";
        old.save(data);

        Path transaction = InstallTransaction.create(data);
        Path content = transaction.resolve("content");
        write(content.resolve("config/changed.cfg"), "new");
        write(content.resolve("mods/added.jar"), "added");

        Set<String> staged = new LinkedHashSet<String>();
        staged.add("config/changed.cfg");
        staged.add("mods/added.jar");
        Set<String> removals = new LinkedHashSet<String>();
        removals.add("mods/removed.jar");

        InstalledState next = new InstalledState();
        next.build = "new";
        return new Fixture(
            data, transaction, content, changed, removed, added,
            staged, removals, next);
    }

    private void crashCommit(Fixture fixture, InstallTransaction.CommitPoint crashPoint) {
        assertThrows(SimulatedCrash.class, () ->
            InstallTransaction.commit(
                gameDirectory, fixture.data, null, fixture.transaction,
                fixture.content, fixture.staged, fixture.removals, fixture.next,
                point -> {
                    if (point == crashPoint) {
                        throw new SimulatedCrash();
                    }
                }));
        assertTrue(Files.isDirectory(fixture.transaction));
    }

    private void recover(Fixture fixture) throws Exception {
        InstallTransaction.recoverPending(gameDirectory, fixture.data, null);
    }

    private static void assertNoTransactions(Path data) throws Exception {
        Path transactions = data.resolve(InstallTransaction.TRANSACTIONS_DIRECTORY);
        if (!Files.isDirectory(transactions)) {
            return;
        }
        try (Stream<Path> children = Files.list(transactions)) {
            assertEquals(0L, children.count());
        }
    }

    private static void write(Path file, String value) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, value.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static final class SimulatedCrash extends Error {
        private static final long serialVersionUID = 1L;
    }

    private static final class Fixture {
        private final Path data;
        private final Path transaction;
        private final Path content;
        private final Path changed;
        private final Path removed;
        private final Path added;
        private final Set<String> staged;
        private final Set<String> removals;
        private final InstalledState next;

        private Fixture(
            Path data,
            Path transaction,
            Path content,
            Path changed,
            Path removed,
            Path added,
            Set<String> staged,
            Set<String> removals,
            InstalledState next) {

            this.data = data;
            this.transaction = transaction;
            this.content = content;
            this.changed = changed;
            this.removed = removed;
            this.added = added;
            this.staged = staged;
            this.removals = removals;
            this.next = next;
        }
    }
}
