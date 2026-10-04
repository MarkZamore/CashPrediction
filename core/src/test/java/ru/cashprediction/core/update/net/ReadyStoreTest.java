package ru.cashprediction.core.update.net;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.update.model.UpdateCodec;
import ru.cashprediction.core.update.model.UpdateManifest;
import ru.cashprediction.core.update.tree.TreeDeltaEngine;
import static org.junit.jupiter.api.Assertions.*;

/** Проверки реальных состояний диска на границах двух атомарных rename Ready. */
class ReadyStoreTest {
    @TempDir Path temporary;

    @Test
    void recoveryHandlesBeforeFirstMoveBetweenMovesAndAfterSecondMove() throws Exception {
        for (int phase = 0; phase < 3; phase++) {
            Path updates = temporary.resolve("phase-" + phase);
            Files.createDirectories(updates);
            UpdateManifest old = prepared(updates.resolve("Ready"), 2, "old");
            UpdateManifest target = prepared(updates.resolve("Staging"), 3, "target");
            UpdateFiles.writeAtomic(updates.resolve("prepare-journal.json"), json(target));
            if (phase >= 1) UpdateFiles.move(updates.resolve("Ready"), updates.resolve("PreviousReady"));
            if (phase >= 2) UpdateFiles.move(updates.resolve("Staging"), updates.resolve("Ready"));
            ReadyStore store = new ReadyStore(updates, 1);
            assertEquals(target, store.recover());
            assertEquals(target, store.recover());
            TreeDeltaEngine.verify(updates.resolve("Ready/tree"), target.files(), target.treeSha256());
            assertFalse(Files.exists(updates.resolve("PreviousReady")));
            assertFalse(Files.exists(updates.resolve("Staging")));
            assertFalse(Files.exists(updates.resolve("prepare-journal.json")));
            assertNotEquals(old.treeSha256(), target.treeSha256());
        }
    }

    @Test
    void corruptStagingRestoresVerifiedPreviousRatherThanPublishingPartialTree() throws Exception {
        Path updates = temporary.resolve("Updates");
        Files.createDirectories(updates);
        UpdateManifest old = prepared(updates.resolve("PreviousReady"), 2, "old");
        UpdateManifest target = prepared(updates.resolve("Staging"), 3, "target");
        UpdateFiles.writeAtomic(updates.resolve("prepare-journal.json"), json(target));
        Files.writeString(updates.resolve("Staging/tree/app/data.txt"), "broken");
        assertEquals(old, new ReadyStore(updates, 1).recover());
        TreeDeltaEngine.verify(updates.resolve("Ready/tree"), old.files(), old.treeSha256());
        assertFalse(Files.exists(updates.resolve("Staging")));
    }

    @Test
    void journalDoesNotTrustUnmatchedOrUncommittedStaging() throws Exception {
        for (boolean journalPresent : List.of(false, true)) {
            Path updates = temporary.resolve(journalPresent ? "mismatch" : "no-journal");
            Files.createDirectories(updates);
            UpdateManifest old = prepared(updates.resolve("Ready"), 2, "old");
            prepared(updates.resolve("Staging"), 3, "candidate");
            if (journalPresent) UpdateFiles.writeAtomic(updates.resolve("prepare-journal.json"), json(old));
            assertEquals(old, new ReadyStore(updates, 1).recover());
            assertFalse(Files.exists(updates.resolve("Staging")));
        }
    }

    @Test
    void invalidReadyFallsBackToPreviousWithoutUsingTimestamps() throws Exception {
        Path updates = temporary.resolve("Updates");
        Files.createDirectories(updates);
        UpdateManifest previous = prepared(updates.resolve("PreviousReady"), 2, "old");
        prepared(updates.resolve("Ready"), 3, "broken");
        Files.delete(updates.resolve("Ready/tree/app/data.txt"));
        Files.setLastModifiedTime(updates.resolve("PreviousReady"), java.nio.file.attribute.FileTime.fromMillis(0));
        assertEquals(previous, new ReadyStore(updates, 1).recover());
        TreeDeltaEngine.verify(updates.resolve("Ready/tree"), previous.files(), previous.treeSha256());
    }

    @Test
    void olderStagingDoesNotReplaceNewerVerifiedReady() throws Exception {
        Path updates = temporary.resolve("Updates");
        Files.createDirectories(updates);
        UpdateManifest ready = prepared(updates.resolve("Ready"), 4, "newer");
        UpdateManifest stage = prepared(updates.resolve("Staging"), 3, "older");
        UpdateFiles.writeAtomic(updates.resolve("prepare-journal.json"), json(stage));
        assertEquals(ready, new ReadyStore(updates, 1).recover());
    }

    @Test
    void publishWritesManifestAndVerifiedTreeAndCleansPrevious() throws Exception {
        Path updates = temporary.resolve("Updates");
        Files.createDirectories(updates);
        prepared(updates.resolve("Ready"), 2, "old");
        UpdateManifest target = prepared(updates.resolve("Staging"), 3, "target");
        ReadyStore store = new ReadyStore(updates, 1);
        store.publish(target);
        assertEquals(target, store.recover());
        assertFalse(Files.exists(updates.resolve("PreviousReady")));
        assertFalse(Files.exists(updates.resolve("prepare-journal.json")));
    }

    @Test
    void corruptTreeIsRejectedBeforeJournalOrReadyMoves() throws Exception {
        Path updates = temporary.resolve("Updates");
        Files.createDirectories(updates);
        UpdateManifest old = prepared(updates.resolve("Ready"), 2, "old");
        UpdateManifest target = prepared(updates.resolve("Staging"), 3, "target");
        Files.writeString(updates.resolve("Staging/tree/app/data.txt"), "corrupt");
        ReadyStore store = new ReadyStore(updates, 1);
        assertThrows(IOException.class, () -> store.publish(target));
        assertFalse(Files.exists(updates.resolve("prepare-journal.json")));
        assertEquals(old, store.recover());
    }

    @Test
    void malformedUtf8OrOversizedManifestIsRejected() throws Exception {
        Path file = temporary.resolve("update.json");
        Files.write(file, new byte[] {(byte) 0xc3, (byte) 0x28});
        assertThrows(IOException.class, () -> ReadyStore.readUtf8(file));
        try (var output = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.WRITE)) {
            output.position(ReadyStore.JSON_LIMIT);
            output.write(java.nio.ByteBuffer.wrap(new byte[] {0}));
        }
        assertThrows(IOException.class, () -> ReadyStore.readUtf8(file));
    }

    @Test
    void publicPreUiRecoveryHandlesEveryRenameBoundaryAndIsIdempotent() throws Exception {
        for (int phase = 0; phase < 3; phase++) {
            Path root = temporary.resolve("portable-" + phase);
            Path updates = root.resolve("CashMemory/Updates");
            Files.createDirectories(updates);
            prepared(updates.resolve("Ready"), 2, "old");
            UpdateManifest target = prepared(updates.resolve("Staging"), 3, "new");
            UpdateFiles.writeAtomic(updates.resolve("prepare-journal.json"), json(target));
            if (phase >= 1) UpdateFiles.move(updates.resolve("Ready"), updates.resolve("PreviousReady"));
            if (phase >= 2) UpdateFiles.move(updates.resolve("Staging"), updates.resolve("Ready"));
            assertTrue(UpdatePreparer.recoverReady(root, 1));
            assertTrue(UpdatePreparer.recoverReady(root, 1));
            assertEquals(target, UpdateCodec.read(Files.readString(updates.resolve("Ready/update.json"))));
            TreeDeltaEngine.verify(updates.resolve("Ready/tree"), target.files(), target.treeSha256());
            assertFalse(Files.exists(updates.resolve("PreviousReady")));
            assertFalse(Files.exists(updates.resolve("Staging")));
            assertFalse(Files.exists(updates.resolve("prepare-journal.json")));
        }
    }

    @Test
    void publicRecoveryDoesNotCreateUpdatesForAbsentOrDevelopmentState() throws Exception {
        Path root = temporary.resolve("portable");
        Files.createDirectory(root);
        assertFalse(UpdatePreparer.recoverReady(root, 1));
        assertFalse(Files.exists(root.resolve("CashMemory")));
        assertFalse(UpdatePreparer.recoverReady(root, 0));
        assertFalse(Files.exists(root.resolve("CashMemory")));
    }

    @Test
    void publicRecoveryNeverMutatesBusyPreparationAndCanRecoverAfterLockRelease() throws Exception {
        Path root = temporary.resolve("portable");
        Path updates = root.resolve("CashMemory/Updates");
        Files.createDirectories(updates);
        UpdateManifest old = prepared(updates.resolve("PreviousReady"), 2, "old");
        UpdateManifest target = prepared(updates.resolve("Staging"), 3, "new");
        UpdateFiles.writeAtomic(updates.resolve("prepare-journal.json"), json(target));
        try (FileChannel channel = FileChannel.open(updates.resolve("prepare.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.lock()) {
            assertFalse(UpdatePreparer.recoverReady(root, 1));
            assertFalse(Files.exists(updates.resolve("Ready")));
            TreeDeltaEngine.verify(updates.resolve("PreviousReady/tree"), old.files(), old.treeSha256());
            TreeDeltaEngine.verify(updates.resolve("Staging/tree"), target.files(), target.treeSha256());
            assertArrayEquals(json(target), Files.readAllBytes(updates.resolve("prepare-journal.json")));
        }
        assertTrue(UpdatePreparer.recoverReady(root, 1));
        TreeDeltaEngine.verify(updates.resolve("Ready/tree"), target.files(), target.treeSha256());
    }

    @Test
    void publicRecoveryPreservesAllPreparationStateDuringActiveInstall() throws Exception {
        Path root = temporary.resolve("portable");
        Path updates = root.resolve("CashMemory/Updates");
        Files.createDirectories(updates);
        UpdateManifest old = prepared(updates.resolve("PreviousReady"), 2, "old");
        UpdateManifest target = prepared(updates.resolve("Staging"), 3, "new");
        UpdateFiles.writeAtomic(updates.resolve("prepare-journal.json"), json(target));
        Files.writeString(updates.resolve("install-journal.json"), "active-install");
        assertFalse(UpdatePreparer.recoverReady(root, 1));
        assertFalse(Files.exists(updates.resolve("Ready")));
        TreeDeltaEngine.verify(updates.resolve("PreviousReady/tree"), old.files(), old.treeSha256());
        TreeDeltaEngine.verify(updates.resolve("Staging/tree"), target.files(), target.treeSha256());
        assertEquals("active-install", Files.readString(updates.resolve("install-journal.json")));
        assertArrayEquals(json(target), Files.readAllBytes(updates.resolve("prepare-journal.json")));
    }

    @Test
    void journalWithArbitraryMovePathsCannotChooseSourceOrTouchAnotherCopy() throws Exception {
        Path root = temporary.resolve("portable");
        Path updates = root.resolve("CashMemory/Updates");
        Files.createDirectories(updates);
        UpdateManifest backup = prepared(updates.resolve("PreviousReady"), 2, "old");
        UpdateManifest candidate = prepared(updates.resolve("Staging"), 3, "untrusted");
        Path foreign = temporary.resolve("foreign-copy/Ready");
        UpdateManifest other = prepared(foreign, 4, "foreign");
        String manifest = UpdateCodec.write(candidate);
        String forged = "{\"source\":\"" + foreign.toString().replace("\\", "\\\\")
                + "\",\"destination\":\"Ready\"," + manifest.substring(1);
        Files.writeString(updates.resolve("prepare-journal.json"), forged);
        assertTrue(UpdatePreparer.recoverReady(root, 1));
        assertEquals(backup, UpdateCodec.read(Files.readString(updates.resolve("Ready/update.json"))));
        assertEquals(other, UpdateCodec.read(Files.readString(foreign.resolve("update.json"))));
        TreeDeltaEngine.verify(foreign.resolve("tree"), other.files(), other.treeSha256());
        assertFalse(Files.exists(updates.resolve("Staging")));
    }

    @Test
    void unsafeInventoryInJournalCannotReachOutsideUpdates() throws Exception {
        Path root = temporary.resolve("portable");
        Path updates = root.resolve("CashMemory/Updates");
        Files.createDirectories(updates);
        UpdateManifest backup = prepared(updates.resolve("PreviousReady"), 2, "old");
        UpdateManifest candidate = prepared(updates.resolve("Staging"), 3, "new");
        Path sentinel = root.resolve("CashMemory/user.txt");
        Files.writeString(sentinel, "protected-user-data");
        Files.writeString(updates.resolve("prepare-journal.json"), UpdateCodec.write(candidate)
                .replace("app/data.txt", "../../user.txt"));
        assertTrue(UpdatePreparer.recoverReady(root, 1));
        assertEquals(backup, UpdateCodec.read(Files.readString(updates.resolve("Ready/update.json"))));
        assertEquals("protected-user-data", Files.readString(sentinel));
    }

    @Test
    void publicRecoveryRejectsTraversalInInstallationRoot() throws Exception {
        Path root = temporary.resolve("portable");
        Files.createDirectory(root);
        assertThrows(IOException.class, () -> UpdatePreparer.recoverReady(root.resolve("../portable"), 1));
        assertFalse(Files.exists(root.resolve("CashMemory")));
    }

    @Test
    void publicRecoveryRestoresPreviousWhenJournalCandidateIsIncomplete() throws Exception {
        Path root = temporary.resolve("portable");
        Path updates = root.resolve("CashMemory/Updates");
        Files.createDirectories(updates);
        UpdateManifest backup = prepared(updates.resolve("PreviousReady"), 2, "old");
        UpdateManifest candidate = prepared(updates.resolve("Staging"), 3, "new");
        UpdateFiles.writeAtomic(updates.resolve("prepare-journal.json"), json(candidate));
        Files.delete(updates.resolve("Staging/tree/app/data.txt"));
        assertTrue(UpdatePreparer.recoverReady(root, 1));
        TreeDeltaEngine.verify(updates.resolve("Ready/tree"), backup.files(), backup.treeSha256());
    }

    @Test
    void publicRecoveryNeverPublishesAlreadyInstalledOrOlderCandidate() throws Exception {
        Path root = temporary.resolve("portable");
        Path updates = root.resolve("CashMemory/Updates");
        Files.createDirectories(updates);
        prepared(updates.resolve("PreviousReady"), 2, "old");
        UpdateManifest candidate = prepared(updates.resolve("Staging"), 3, "already-installed");
        UpdateFiles.writeAtomic(updates.resolve("prepare-journal.json"), json(candidate));
        assertFalse(UpdatePreparer.recoverReady(root, 3));
        assertFalse(Files.exists(updates.resolve("Ready")));
    }

    @Test
    void incompleteButHashConsistentPortableTargetCannotReplaceReady() throws Exception {
        for (String missing : NetPortableFixture.REQUIRED) {
            Path root = temporary.resolve("missing-" + NetPortableFixture.REQUIRED.indexOf(missing));
            Path updates = root.resolve("CashMemory/Updates");
            Files.createDirectories(updates);
            UpdateManifest old = prepared(updates.resolve("Ready"), 2, "old");
            prepared(updates.resolve("Staging"), 3, "target");
            Files.delete(updates.resolve("Staging/tree").resolve(missing));
            UpdateManifest incomplete = refreshed(updates.resolve("Staging"), 3);
            // Общий CLI-движок законно принимает это дерево; updater обязан отказать.
            TreeDeltaEngine.verify(updates.resolve("Staging/tree"), incomplete.files(), incomplete.treeSha256());
            assertThrows(IOException.class, () -> new ReadyStore(updates, 1).publish(incomplete), missing);
            assertFalse(Files.exists(updates.resolve("prepare-journal.json")));
            assertTrue(UpdatePreparer.recoverReady(root, 1));
            TreeDeltaEngine.verify(updates.resolve("Ready/tree"), old.files(), old.treeSha256());
        }
    }

    @Test
    void savedIncompleteReadyRestoresValidPreviousForEveryRequiredPath() throws Exception {
        for (String missing : NetPortableFixture.REQUIRED) {
            Path root = temporary.resolve("saved-" + NetPortableFixture.REQUIRED.indexOf(missing));
            Path updates = root.resolve("CashMemory/Updates");
            Files.createDirectories(updates);
            UpdateManifest previous = prepared(updates.resolve("PreviousReady"), 2, "old");
            prepared(updates.resolve("Ready"), 3, "broken");
            Files.delete(updates.resolve("Ready/tree").resolve(missing));
            refreshed(updates.resolve("Ready"), 3);
            assertTrue(UpdatePreparer.recoverReady(root, 1));
            assertEquals(previous, UpdateCodec.read(Files.readString(updates.resolve("Ready/update.json"))), missing);
        }
    }

    @Test
    void journalCommittedEmptyTreeCannotBecomeReadyButGenericTreeStaysValid() throws Exception {
        Path root = temporary.resolve("portable");
        Path updates = root.resolve("CashMemory/Updates");
        Files.createDirectories(updates.resolve("Staging/tree"));
        UpdateManifest previous = prepared(updates.resolve("PreviousReady"), 2, "old");
        UpdateManifest empty = refreshed(updates.resolve("Staging"), 3);
        assertTrue(empty.files().isEmpty());
        TreeDeltaEngine.verify(updates.resolve("Staging/tree"), empty.files(), empty.treeSha256());
        UpdateFiles.writeAtomic(updates.resolve("prepare-journal.json"), json(empty));
        assertTrue(UpdatePreparer.recoverReady(root, 1));
        assertEquals(previous, UpdateCodec.read(Files.readString(updates.resolve("Ready/update.json"))));
    }

    @Test
    void emptySavedReadyWithoutValidPreviousIsRejected() throws Exception {
        Path root = temporary.resolve("portable");
        Path updates = root.resolve("CashMemory/Updates");
        Files.createDirectories(updates.resolve("Ready/tree"));
        refreshed(updates.resolve("Ready"), 3);
        assertFalse(UpdatePreparer.recoverReady(root, 1));
        assertFalse(Files.exists(updates.resolve("Ready")));
    }

    @Test
    void presentButZeroSizeRequiredFilesAreRejectedWithoutMovingReady() throws Exception {
        for (String emptyPath : NetPortableFixture.REQUIRED) {
            Path updates = temporary.resolve("zero-" + NetPortableFixture.REQUIRED.indexOf(emptyPath));
            Files.createDirectories(updates);
            UpdateManifest old = prepared(updates.resolve("Ready"), 2, "old");
            prepared(updates.resolve("Staging"), 3, "target");
            Files.write(updates.resolve("Staging/tree").resolve(emptyPath), new byte[0]);
            UpdateManifest target = refreshed(updates.resolve("Staging"), 3);
            assertThrows(IOException.class, () -> new ReadyStore(updates, 1).publish(target), emptyPath);
            TreeDeltaEngine.verify(updates.resolve("Ready/tree"), old.files(), old.treeSha256());
        }
    }

    private static byte[] json(UpdateManifest manifest) throws IOException {
        return UpdateCodec.write(manifest).getBytes(StandardCharsets.UTF_8);
    }

    private static UpdateManifest prepared(Path directory, int release, String contents) throws IOException {
        Path tree = directory.resolve("tree");
        NetPortableFixture.create(tree);
        Files.writeString(tree.resolve("app/data.txt"), contents);
        Files.writeString(tree.resolve("runtime/jvm.txt"), "runtime");
        return refreshed(directory, release);
    }

    private static UpdateManifest refreshed(Path directory, int release) throws IOException {
        Path tree = directory.resolve("tree");
        var files = TreeDeltaEngine.inventory(tree);
        UpdateManifest manifest = new UpdateManifest(release, Integer.toHexString(release).repeat(40),
                Integer.toString(release), Instant.EPOCH, "CashPrediction-portable.zip", 1, "0".repeat(64),
                TreeDeltaEngine.treeHash(files), files, List.of());
        UpdateFiles.writeAtomic(directory.resolve("update.json"), json(manifest));
        return manifest;
    }
}
