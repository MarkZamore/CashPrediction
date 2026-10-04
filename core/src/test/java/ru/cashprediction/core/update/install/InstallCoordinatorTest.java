package ru.cashprediction.core.update.install;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Проверка предстартового барьера, регистрации и строгого запроса запуска. */
class InstallCoordinatorTest {
    @TempDir Path temporary;

    @Test void normalExitCreatesNoRestartRequestAndKeepsLiveJvmLease() throws Exception {
        HelperFixture f = new HelperFixture(temporary.resolve("normal"));
        Files.delete(f.updates.resolve("install-journal.json"));
        Files.move(f.updates.resolve("Ready"), f.updates.resolve("held"));
        AtomicInteger starts = new AtomicInteger();
        InstallCoordinator coordinator = new InstallCoordinator(f.root, f.root.resolve("CashMemory"),
                "swing", new String[]{"--selftest", "--registry-node", "foreign"}, 1,
                (root, updates) -> starts.incrementAndGet());
        assertTrue(coordinator.beforeUi());
        Files.move(f.updates.resolve("held"), f.updates.resolve("Ready"));
        coordinator.normalExit();
        assertEquals(1, starts.get());
        assertTrue(Files.exists(f.updates.resolve("install-journal.json")));
        assertFalse(Files.exists(f.updates.resolve("requests")));
        try (var leases = Files.list(f.updates.resolve("processes"))) {
            Map<String, Object> lease = InstallFiles.object(leases.findFirst().orElseThrow());
            assertEquals(ProcessHandle.current().pid(), lease.get("pid"));
            assertEquals(ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli(),
                    lease.get("startedAtEpochMillis"));
            assertEquals(f.root.toString(), lease.get("installationRoot"));
        }
        f.assertUserFiles();
    }

    @Test void beforeUiHandsOffExactClientAndFilteredArguments() throws Exception {
        HelperFixture f = new HelperFixture(temporary.resolve("prestart"));
        AtomicInteger starts = new AtomicInteger();
        InstallCoordinator coordinator = new InstallCoordinator(f.root, f.root.resolve("CashMemory"), "web",
                new String[]{"--no-window", "--no-browser", "--home", "elsewhere", "--test-api"}, 1,
                (root, updates) -> starts.incrementAndGet());
        assertFalse(coordinator.beforeUi());
        assertEquals(1, starts.get());
        try (var requests = Files.list(f.updates.resolve("requests"))) {
            Map<String, Object> request = InstallFiles.object(requests.findFirst().orElseThrow());
            assertEquals("web", request.get("client"));
            assertEquals(f.journal.get("transactionId"), request.get("transactionId"));
            assertEquals(List.of("--home", f.root.toString(), "--no-browser", "--no-window",
                    "--updated-from", f.target.commitSha()), request.get("args"));
        }
        f.assertUserFiles();
    }

    @Test void skipRequiresExactTargetShaAndDoesNotSkipActiveRecovery() throws Exception {
        HelperFixture f = new HelperFixture(temporary.resolve("skip"));
        AtomicInteger starts = new AtomicInteger();
        InstallCoordinator coordinator = new InstallCoordinator(f.root, f.root.resolve("CashMemory"), "fx",
                new String[]{"--updated-from", f.target.commitSha()}, 1, (root, updates) -> starts.incrementAndGet());
        assertFalse(coordinator.beforeUi());
        assertEquals(1, starts.get());
        Files.delete(f.updates.resolve("install-journal.json"));
        assertTrue(coordinator.beforeUi());
        assertEquals(1, starts.get());
    }

    @Test void foreignJournalAndOutsideMemoryAreRejectedWithoutEditingData() throws Exception {
        HelperFixture f = new HelperFixture(temporary.resolve("ownership"));
        assertThrows(java.io.IOException.class, () -> new InstallCoordinator(f.root, temporary.resolve("other"),
                "fx", new String[0], 1));
        f.journal.put("installationRoot", temporary.resolve("foreign").toString());
        f.save();
        InstallCoordinator coordinator = new InstallCoordinator(f.root, f.root.resolve("CashMemory"),
                "fx", new String[0], 1, (root, updates) -> fail("FOREIGN_HELPER"));
        assertThrows(java.io.IOException.class, coordinator::beforeUi);
        f.assertUserFiles();
    }

    @Test void restartWhitelistPreservesUnicodeAndQuotesOnlyInsideCanonicalHome() {
        Path root = temporary.resolve("unicode-\u03bb-'quoted'");
        assertEquals(List.of("--home", root.toString(), "--updated-from", "b".repeat(40)),
                RestartRequest.safeArgs(root, "swing", new String[]{"--no-window", "--selftest"}, "b".repeat(40)));
        assertEquals("CashPrediction-Swing.exe", RestartRequest.launcher("swing"));
        assertThrows(IllegalArgumentException.class, () -> RestartRequest.launcher("../evil"));
    }

    @Test void durableJournalRejectsUnmanagedOperationsAndDuplicateJsonKeys() throws Exception {
        HelperFixture f = new HelperFixture(temporary.resolve("journal"));
        f.journal.put("operations", List.of(Map.of("kind", "RESTORE", "state", "BEFORE", "path", "CashMemory/plans/user.md")));
        f.save();
        assertThrows(java.io.IOException.class, () -> InstallJournal.read(f.root, f.updates));
        InstallFiles.write(f.updates.resolve("install-journal.json"), "{\"schemaVersion\":1,\"schemaVersion\":1}");
        assertThrows(java.io.IOException.class, () -> InstallJournal.read(f.root, f.updates));
        f.assertUserFiles();
    }

    @Test void leaseUsesIndependentUuidInsteadOfPidFilename() throws Exception {
        HelperFixture f = new HelperFixture(temporary.resolve("lease"));
        ProcessLease.register(f.root, f.updates, "fx");
        ProcessLease.register(f.root, f.updates, "web");
        try (var leases = Files.list(f.updates.resolve("processes"))) {
            List<Path> files = leases.toList();
            assertEquals(2, files.size());
            for (Path file : files) UUID.fromString(file.getFileName().toString().replace(".json", ""));
        }
    }

    @Test void beforeUiRecoversInterruptedReadyPublicationBeforeScheduling() throws Exception {
        for (String source : List.of("Staging", "PreviousReady")) {
            BootstrapFixture f = new BootstrapFixture(temporary.resolve("recovery-" + source));
            Files.delete(f.updates.resolve("install-journal.json"));
            Files.move(f.updates.resolve("Ready"), f.updates.resolve(source));
            if (source.equals("Staging")) {
                InstallFiles.write(f.updates.resolve("prepare-journal.json"),
                        ru.cashprediction.core.update.model.UpdateCodec.write(f.target));
            }
            AtomicInteger starts = new AtomicInteger();
            InstallCoordinator coordinator = new InstallCoordinator(f.root, f.root.resolve("CashMemory"),
                    "swing", new String[0], 1, (root, updates) -> starts.incrementAndGet(), true);
            assertFalse(coordinator.beforeUi());
            assertEquals(1, starts.get());
            assertTrue(Files.exists(f.updates.resolve("Ready/update.json")));
            ru.cashprediction.core.update.tree.TreeDeltaEngine.verify(f.ready, f.target.files(), f.target.treeSha256());
            assertTrue(Files.exists(f.updates.resolve("install-journal.json")));
            f.userData();
        }
    }

    @Test void busyReadyRecoveryDoesNotExitOrScheduleAnotherHelper() throws Exception {
        BootstrapFixture f = new BootstrapFixture(temporary.resolve("recovery-busy"));
        Files.delete(f.updates.resolve("install-journal.json"));
        Files.move(f.updates.resolve("Ready"), f.updates.resolve("Staging"));
        InstallFiles.write(f.updates.resolve("prepare-journal.json"),
                ru.cashprediction.core.update.model.UpdateCodec.write(f.target));
        AtomicInteger starts = new AtomicInteger();
        InstallCoordinator coordinator = new InstallCoordinator(f.root, f.root.resolve("CashMemory"),
                "fx", new String[0], 1, (root, updates) -> starts.incrementAndGet(), true);
        try (var channel = java.nio.channels.FileChannel.open(f.updates.resolve("prepare.lock"),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
             var ignored = channel.lock()) {
            assertTrue(coordinator.beforeUi());
        }
        assertEquals(0, starts.get());
        assertFalse(Files.exists(f.updates.resolve("install-journal.json")));
        assertTrue(Files.exists(f.updates.resolve("Staging/update.json")));
        f.userData();
    }
}
