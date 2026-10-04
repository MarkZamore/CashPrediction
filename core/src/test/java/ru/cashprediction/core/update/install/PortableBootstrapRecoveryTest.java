package ru.cashprediction.core.update.install;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.update.tree.TreeDeltaEngine;

/** Аварии bootstrap и File.Replace проверяются отдельными процессами без изменения общих fixtures. */
class PortableBootstrapRecoveryTest {
    @TempDir Path temporary;

    private void windows() { assumeTrue(System.getProperty("os.name", "").startsWith("Windows")); }

    @Test void successfulPortableTransactionRestoresAllConfigsBeforeRemovingBootstrap() throws Exception {
        windows();
        BootstrapFixture f = new BootstrapFixture(temporary.resolve("success"));
        var result = f.run(0, false, "", 0);
        assertEquals(0, result.exit(), result.diagnostics());
        TreeDeltaEngine.verify(f.root, f.target.files(), f.target.treeSha256());
        assertFalse(Files.exists(f.updates.resolve("Bootstrap")));
        for (String path : PortableBootstrap.CONFIGS) assertFalse(Files.readString(f.root.resolve(path)).contains("Updates\\Bootstrap"));
        f.userData();
    }

    @Test void everyCopyPublishMoveAndNativeReplacementBoundaryPreservesAnOrdinaryLaunchPath() throws Exception {
        windows();
        BootstrapFixture count = new BootstrapFixture(temporary.resolve("count"));
        for (int boundary = 1; boundary <= count.boundaries(); boundary++) {
            BootstrapFixture f = new BootstrapFixture(temporary.resolve("boundary-" + boundary));
            var crash = f.run(boundary, true, "", 0);
            assertNotEquals(0, crash.exit(), "boundary=" + boundary + crash.diagnostics());
            for (String path : PortableBootstrap.STABLE) assertTrue(Files.isRegularFile(f.root.resolve(path)), path);
            for (String path : PortableBootstrap.CONFIGS) {
                if (Files.readString(f.root.resolve(path)).contains("Updates\\Bootstrap")) {
                    var runtime = f.old.stream().filter(file -> file.path().startsWith("runtime/")).toList();
                    TreeDeltaEngine.verify(f.updates.resolve("Bootstrap"), runtime, TreeDeltaEngine.treeHash(runtime));
                }
            }
            var recovery = f.run(0, false, "", 0);
            assertEquals(0, recovery.exit(), "recovery=" + boundary + recovery.diagnostics());
            TreeDeltaEngine.verify(f.root, f.old, f.oldHash);
            f.userData();
        }
    }

    @Test void killsAtEveryPhaseAndPersistentMetadataFailurePreserveWholeOldOrNewTrees() throws Exception {
        windows();
        for (String phase : List.of("BOOTSTRAPPING", "BACKING_UP", "INSTALLING", "VERIFYING", "COMMITTED")) {
            BootstrapFixture f = new BootstrapFixture(temporary.resolve("phase-" + phase));
            assertNotEquals(0, f.run(0, true, phase, 0).exit());
            var recovery = f.run(0, false, "", 0);
            assertEquals(0, recovery.exit(), recovery.diagnostics());
            var actual = TreeDeltaEngine.inventory(f.root);
            assertTrue(actual.equals(f.old) || actual.equals(f.target.files()));
            f.userData();
        }
        BootstrapFixture disk = new BootstrapFixture(temporary.resolve("metadata"));
        assertEquals(1, disk.run(0, false, "", -1).exit());
        TreeDeltaEngine.verify(disk.root, disk.old, disk.oldHash);
        assertEquals(0, disk.run(0, false, "", 0).exit());
        disk.userData();
    }

    @Test void completedRollbackWithForeignBootstrapAllowsOriginalUiWithoutRemovingForeignData() throws Exception {
        windows();
        BootstrapFixture f = new BootstrapFixture(temporary.resolve("foreign"));
        BootstrapFixture.put(f.updates, "Bootstrap/private.txt", "foreign-data");
        assertEquals(1, f.run(0, false, "", 0).exit());
        TreeDeltaEngine.verify(f.root, f.old, f.oldHash);
        var journal = InstallJournal.read(f.root, f.updates);
        assertEquals("ROLLED_BACK", journal.get("outcome"));
        var bootstrap = Json.asObject(journal.get("bootstrap"), "bootstrap");
        assertEquals("RESTORED", bootstrap.get("state"));
        var coordinator = new InstallCoordinator(f.root, f.root.resolve("CashMemory"), "swing", new String[0],
                1, (root, updates) -> { }, true);
        assertTrue(coordinator.beforeUi());
        assertEquals("foreign-data", Files.readString(f.updates.resolve("Bootstrap/private.txt")));
        f.userData();
    }

    @Test void recoveryCanCrashBeforeAndAfterEveryAtomicRestoreAndRuntimeRestoreMove() throws Exception {
        windows();
        BootstrapFixture count = new BootstrapFixture(temporary.resolve("rollback-count"));
        long runtime = count.old.stream().filter(file -> file.path().startsWith("runtime/")).count();
        long stable = count.old.stream().filter(file -> PortableBootstrap.STABLE.contains(file.path())).count();
        long moves = count.old.size() - stable;
        int firstNativeReplacement = Math.toIntExact(2 * (runtime + 1 + stable + 3 + moves) + 2);
        int rollbackBoundaries = Math.toIntExact(2 * (stable - 3 + moves + 3));
        for (int boundary = 1; boundary <= rollbackBoundaries; boundary++) {
            BootstrapFixture f = new BootstrapFixture(temporary.resolve("rollback-" + boundary));
            assertNotEquals(0, f.run(firstNativeReplacement, true, "", 0).exit());
            assertNotEquals(0, f.run(boundary, true, "", 0).exit());
            for (String path : PortableBootstrap.STABLE) assertTrue(Files.isRegularFile(f.root.resolve(path)), path);
            var recovery = f.run(0, false, "", 0);
            assertEquals(0, recovery.exit(), recovery.diagnostics());
            TreeDeltaEngine.verify(f.root, f.old, f.oldHash);
            f.userData();
        }
    }

    @Test void readOnlyLaunchersAndConfigsSurviveAtomicReplacement() throws Exception {
        windows();
        BootstrapFixture f = new BootstrapFixture(temporary.resolve("readonly"));
        for (String path : PortableBootstrap.STABLE) {
            Files.setAttribute(f.root.resolve(path), "dos:readonly", true);
            Files.setAttribute(f.ready.resolve(path), "dos:readonly", true);
        }
        var old = TreeDeltaEngine.inventory(f.root);
        var files = TreeDeltaEngine.inventory(f.ready);
        var target = new ru.cashprediction.core.update.model.UpdateManifest(f.target.releaseNumber(), f.target.commitSha(),
                f.target.version(), f.target.publishedAtUtc(), f.target.assetName(), f.target.sizeBytes(), f.target.sha256(),
                TreeDeltaEngine.treeHash(files), files, List.of());
        InstallFiles.write(f.updates.resolve("Ready/update.json"), ru.cashprediction.core.update.model.UpdateCodec.write(target));
        InstallJournal.write(f.updates, InstallJournal.preparePortable(f.root, target));
        try {
            var result = f.run(0, false, "", 0);
            assertEquals(0, result.exit(), result.diagnostics());
            TreeDeltaEngine.verify(f.root, files, target.treeSha256());
            for (String path : PortableBootstrap.STABLE) assertTrue((Boolean) Files.getAttribute(f.root.resolve(path), "dos:readonly"));
            assertFalse(Files.exists(f.updates.resolve("Bootstrap")));
            f.userData();
        } finally {
            for (String path : PortableBootstrap.STABLE) {
                for (Path base : List.of(f.root, f.ready)) {
                    if (Files.exists(base.resolve(path))) Files.setAttribute(base.resolve(path), "dos:readonly", false);
                }
            }
        }
    }
}
