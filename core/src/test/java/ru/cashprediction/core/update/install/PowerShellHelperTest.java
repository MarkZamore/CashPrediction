package ru.cashprediction.core.update.install;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.update.tree.TreeDeltaEngine;

/** Реальные процессы помощника с авариями на каждой границе файлового переноса. */
class PowerShellHelperTest {
    @TempDir Path temporary;

    private void windows() {
        assumeTrue(System.getProperty("os.name", "").startsWith("Windows"));
    }

    @Test void installCommitsExactTargetWithoutRestartAndPreservesUserData() throws Exception {
        windows();
        HelperFixture f = new HelperFixture(temporary.resolve("complete"));
        assertEquals(0, f.run(0, false), f::diagnostics);
        TreeDeltaEngine.verify(f.root, f.target.files(), f.target.treeSha256());
        assertFalse(Files.exists(f.updates.resolve("install-journal.json")));
        assertFalse(Files.exists(f.updates.resolve("requests")));
        assertEquals("UPDATED", InstallFiles.object(f.updates.resolve("last-install.json")).get("outcome"));
        f.assertUserFiles();
    }

    @Test void killBeforeAndAfterEveryBackupAndInstallMoveRecoversOldTree() throws Exception {
        windows();
        HelperFixture count = new HelperFixture(temporary.resolve("count"));
        int boundaries = 2 * (count.old.size() + count.target.files().size());
        for (int boundary = 1; boundary <= boundaries; boundary++) {
            HelperFixture f = new HelperFixture(temporary.resolve("crash-" + boundary));
            assertNotEquals(0, f.run(boundary, true), "boundary=" + boundary);
            assertTrue(Files.exists(f.updates.resolve("install-journal.json")));
            assertEquals(0, f.run(0, false), "recovery=" + boundary);
            TreeDeltaEngine.verify(f.root, f.old, f.oldHash);
            TreeDeltaEngine.verify(f.ready, f.target.files(), f.target.treeSha256());
            f.assertUserFiles();
        }
    }

    @Test void caughtFailureRollsBackAndRecoveryCanItselfCrashAtEveryMove() throws Exception {
        windows();
        HelperFixture baseline = new HelperFixture(temporary.resolve("caught"));
        int installFirst = 2 * baseline.old.size() + 2;
        assertEquals(0, baseline.run(installFirst, false));
        TreeDeltaEngine.verify(baseline.root, baseline.old, baseline.oldHash);
        for (int boundary = 1; boundary <= 2 * (baseline.old.size() + 1); boundary++) {
            HelperFixture f = new HelperFixture(temporary.resolve("rollback-" + boundary));
            assertNotEquals(0, f.run(installFirst, true));
            assertNotEquals(0, f.run(boundary, true));
            assertEquals(0, f.run(0, false));
            TreeDeltaEngine.verify(f.root, f.old, f.oldHash);
            f.assertUserFiles();
        }
    }

    /** Все три совпадающие PID/birth lease сохраняют запрет переноса файлов. */
    @Test void allThreeLiveClientLeasesBlockEveryMove() throws Exception {
        windows();
        HelperFixture f = new HelperFixture(temporary.resolve("live"));
        long pid = ProcessHandle.current().pid();
        long birth = ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli();
        for (String client : List.of("fx", "swing", "web")) {
            f.lease(UUID.randomUUID().toString(), pid, birth, f.root.toString(), client);
        }
        Process helper = f.start(0, false);
        try {
            assertLeaseBarrierWaiting(f, helper);
            assertFalse(helper.waitFor(2, TimeUnit.SECONDS));
            TreeDeltaEngine.verify(f.root, f.old, f.oldHash);
        } finally { helper.destroyForcibly().waitFor(10, TimeUnit.SECONDS); }
        f.assertUserFiles();
    }

    /** Старый birth живой независимой JVM не требует её выхода и не выдаётся за kernel PID reuse. */
    @Test void staleLeaseBirthMismatchInstallsWhileForeignPidRemainsAlive() throws Exception {
        windows();
        ProcessHandle foreign = ProcessHandle.current();
        long birth = foreign.info().startInstant().orElseThrow().toEpochMilli();
        for (String client : List.of("fx", "swing", "web")) {
            HelperFixture f = new HelperFixture(temporary.resolve("stale-birth-" + client));
            String id = UUID.randomUUID().toString();
            Path lease = f.updates.resolve("processes").resolve(id + ".json");
            f.lease(id, foreign.pid(), birth - 10000, f.root.toString(), client);
            assertEquals(0, f.run(0, false), f::diagnostics);
            assertTrue(foreign.isAlive());
            assertEquals(birth, foreign.info().startInstant().orElseThrow().toEpochMilli());
            assertFalse(Files.exists(lease));
            TreeDeltaEngine.verify(f.root, f.target.files(), f.target.treeSha256());
            assertEquals("UPDATED", InstallFiles.object(f.updates.resolve("last-install.json")).get("outcome"));
            assertFalse(Files.exists(f.updates.resolve("install-journal.json")));
            f.assertUserFiles();
        }
    }

    /** Удаление stale identity не разрешает обойти оставшуюся настоящую живую lease. */
    @Test void staleLeaseDoesNotBypassAnotherMatchingLiveLease() throws Exception {
        windows();
        HelperFixture f = new HelperFixture(temporary.resolve("stale-and-live"));
        ProcessHandle foreign = ProcessHandle.current();
        long birth = foreign.info().startInstant().orElseThrow().toEpochMilli();
        String stale = UUID.randomUUID().toString();
        String live = UUID.randomUUID().toString();
        f.lease(stale, foreign.pid(), birth - 10000, f.root.toString(), "fx");
        f.lease(live, foreign.pid(), birth, f.root.toString(), "swing");
        Process helper = f.start(0, false);
        try {
            assertLeaseBarrierWaiting(f, helper);
            assertFalse(helper.waitFor(2, TimeUnit.SECONDS), f::diagnostics);
            assertTrue(Files.exists(f.updates.resolve("processes").resolve(live + ".json")));
            TreeDeltaEngine.verify(f.root, f.old, f.oldHash);
            // Снята только собственная тестовая live lease; чужая JVM не завершается.
            Files.delete(f.updates.resolve("processes").resolve(live + ".json"));
            assertTrue(helper.waitFor(20, TimeUnit.SECONDS), f::diagnostics);
            assertEquals(0, helper.exitValue(), f::diagnostics);
            assertFalse(Files.exists(f.updates.resolve("processes").resolve(stale + ".json")));
            assertTrue(foreign.isAlive());
            assertEquals(birth, foreign.info().startInstant().orElseThrow().toEpochMilli());
            TreeDeltaEngine.verify(f.root, f.target.files(), f.target.treeSha256());
            f.assertUserFiles();
        } finally { helper.destroyForcibly().waitFor(10, TimeUnit.SECONDS); }
    }

    /** Недоступный lookup, birth и неполная личность не становятся разрешением удалить lease. */
    @Test void leaseLookupAndBirthUncertaintyRemainFailSafe() throws Exception {
        windows();
        List<String> lookups = List.of(
                "throw [System.UnauthorizedAccessException]::new('ACCESS_DENIED')",
                "Write-Error -Message 'LOOKUP_UNKNOWN' -ErrorId 'UnexpectedLookupFailure' -Category ObjectNotFound",
                "return $null",
                "return [pscustomobject]@{Id=$Id}",
                "return [pscustomobject]@{Id=($Id+1);StartTime=[datetime]::UtcNow.AddSeconds(-10)}",
                "return [pscustomobject]@{Id=$Id;StartTime=[datetime]::MinValue}",
                "return [pscustomobject]@{Id=$Id;StartTime='NOT_A_DATETIME'}",
                "return [pscustomobject]@{Id=$Id;StartTime=$null}",
                "$p=[pscustomobject]@{Id=$Id}; $p | Add-Member ScriptProperty StartTime {throw 'BIRTH_ACCESS_DENIED'}; return $p");
        for (int i = 0; i < lookups.size(); i++) {
            HelperFixture f = new HelperFixture(temporary.resolve("uncertain-lease-" + i));
            String id = UUID.randomUUID().toString();
            Path lease = f.updates.resolve("processes").resolve(id + ".json");
            f.lease(id, ProcessHandle.current().pid(), 1, f.root.toString(), "fx");
            byte[] before = Files.readAllBytes(lease);
            // Подменяется только lookup для guard-теста, не production store и не native доказательство.
            String lookup = "function Get-Process { [CmdletBinding()] param([int]$Id) " + lookups.get(i) + " }";
            runLeaseProbe(f, lookup, true);
            assertArrayEquals(before, Files.readAllBytes(lease), "lookup=" + i);
            TreeDeltaEngine.verify(f.root, f.old, f.oldHash);
            f.assertUserFiles();
        }
    }

    /** Только настоящий точный отказ поиска PID позволяет убрать orphan lease. */
    @Test void confirmedMissingPidRemovesOrphanLease() throws Exception {
        windows();
        HelperFixture f = new HelperFixture(temporary.resolve("missing-lease"));
        String id = UUID.randomUUID().toString();
        Path lease = f.updates.resolve("processes").resolve(id + ".json");
        // Недостижимый для текущей Windows PID не требует запуска или завершения чужих процессов.
        f.lease(id, Integer.MAX_VALUE, 1, f.root.toString(), "web");
        runLeaseProbe(f, "", false);
        assertFalse(Files.exists(lease));
        TreeDeltaEngine.verify(f.root, f.old, f.oldHash);
        f.assertUserFiles();
    }

    /** Другой birth не отменяет проверки root, filename, типов и полного JSON контракта. */
    @Test void staleLeaseCannotBypassRootFilenameOrWireGuards() throws Exception {
        windows();
        long pid = ProcessHandle.current().pid();
        long birth = ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli();
        List<Map<String, Object>> changes = List.of(
                Map.of("installationRoot", temporary.resolve("foreign-root").toString()),
                Map.of("leaseId", UUID.randomUUID().toString()),
                Map.of("schemaVersion", "1"), Map.of("schemaVersion", true),
                Map.of("pid", Long.toString(pid)), Map.of("pid", true),
                Map.of("startedAtEpochMillis", Long.toString(birth - 10000)), Map.of("startedAtEpochMillis", true),
                Map.of("client", "FX"), Map.of("unexpected", 1));
        for (int i = 0; i < changes.size(); i++) {
            HelperFixture f = new HelperFixture(temporary.resolve("invalid-stale-lease-" + i));
            String id = UUID.randomUUID().toString();
            Path lease = f.updates.resolve("processes").resolve(id + ".json");
            f.lease(id, pid, birth - 10000, f.root.toString(), "fx");
            Map<String, Object> wire = new LinkedHashMap<>(InstallFiles.object(lease));
            wire.putAll(changes.get(i));
            InstallFiles.write(lease, ru.cashprediction.core.json.JsonWriter.write(wire));
            byte[] before = Files.readAllBytes(lease);
            assertEquals(1, f.run(0, false), f::diagnostics);
            assertArrayEquals(before, Files.readAllBytes(lease));
            TreeDeltaEngine.verify(f.root, f.old, f.oldHash);
            assertTrue(ProcessHandle.current().isAlive());
            f.assertUserFiles();
        }
    }

    /** Барьер должен реально достигнуть WAITING, а не просто потратить окно теста на запуск PowerShell. */
    private void assertLeaseBarrierWaiting(HelperFixture f, Process helper) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            assertTrue(helper.isAlive(), f::diagnostics);
            if ("WAITING".equals(InstallFiles.object(f.updates.resolve("install-journal.json")).get("phase"))) return;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        fail("LEASE_WAITING_NOT_OBSERVED: " + f.diagnostics());
    }

    /** Исполняет только AST определения production helper; не запускает его установку или UI. */
    private void runLeaseProbe(HelperFixture f, String lookup, boolean expectedAlive) throws Exception {
        String encodedRoot = Base64.getEncoder().encodeToString(f.root.toString().getBytes(StandardCharsets.UTF_8));
        String command = """
                $ErrorActionPreference='Stop'
                Set-StrictMode -Version 2
                $utf8=New-Object System.Text.UTF8Encoding($false,$true)
                $root=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('%s'))
                $updates=Join-Path $root 'CashMemory/Updates'
                $tokens=$null; $errors=$null
                $ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $updates 'apply-update.ps1'),[ref]$tokens,[ref]$errors)
                if ($errors.Count) { throw 'LEASE_PROBE_PARSE' }
                foreach ($definition in $ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst]},$true)) {
                    . ([scriptblock]::Create($definition.Extent.Text))
                }
                %s
                $alive=AliveLeases
                if ($alive -isnot [bool] -or $alive -cne $%s) { throw 'LEASE_PROBE_RESULT' }
                """.formatted(encodedRoot, lookup, expectedAlive);
        String encoded = Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_16LE));
        Path output = f.updates.resolve("lease-probe-output.md");
        Process probe = new ProcessBuilder(Path.of(System.getenv("SystemRoot"), "System32/WindowsPowerShell/v1.0/powershell.exe").toString(),
                "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(probe.waitFor(10, TimeUnit.SECONDS), "LEASE_PROBE_TIMEOUT");
            assertEquals(0, probe.exitValue(), () -> {
                try { return Files.readString(output); }
                catch (java.io.IOException failure) { return failure.toString(); }
            });
        } finally { probe.destroyForcibly().waitFor(10, TimeUnit.SECONDS); }
    }

    @Test void foreignRootLeaseAndUnmanagedJournalPathFailClosed() throws Exception {
        windows();
        HelperFixture f = new HelperFixture(temporary.resolve("foreign"));
        f.lease(UUID.randomUUID().toString(), 1, 1, temporary.resolve("other").toString(), "fx");
        assertEquals(1, f.run(0, false));
        TreeDeltaEngine.verify(f.root, f.old, f.oldHash);
        HelperFixture malicious = new HelperFixture(temporary.resolve("operation"));
        malicious.journal.put("operations", List.of(Map.of("path", "CashMemory/plans/user.md",
                "kind", "RESTORE", "state", "BEFORE")));
        malicious.save();
        assertEquals(1, malicious.run(0, false));
        malicious.assertUserFiles();
    }

    @Test void lockedSourceAndCorruptReadyNeverLoseOldFiles() throws Exception {
        windows();
        HelperFixture f = new HelperFixture(temporary.resolve("corrupt"));
        Files.writeString(f.ready.resolve("runtime/bin/server/jvm.dll"), "corrupt");
        assertEquals(0, f.run(0, false));
        TreeDeltaEngine.verify(f.root, f.old, f.oldHash);
        HelperFixture locked = new HelperFixture(temporary.resolve("locked"));
        try (var channel = java.nio.channels.FileChannel.open(locked.root.resolve("runtime/bin/server/jvm.dll"),
                java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            assertEquals(1, locked.run(0, false));
            assertTrue(lock.isValid());
            assertEquals("old-runtime".length(), channel.size());
            // На Windows чужой дескриптор не может прочитать захваченный диапазон:
            // полный независимый hash проверяем сразу после освобождения, а не внутри lock.
        }
        TreeDeltaEngine.verify(locked.root, locked.old, locked.oldHash);
        assertEquals(0, locked.run(0, false));
        TreeDeltaEngine.verify(locked.root, locked.old, locked.oldHash);
        f.assertUserFiles();
        locked.assertUserFiles();
    }

    @Test void helperWorksWithoutInstalledRuntimeButDoesNotProveOrdinaryExeBootstrap() throws Exception {
        windows();
        HelperFixture f = new HelperFixture(temporary.resolve("bootstrap"));
        assertNotEquals(0, f.run(2 * f.old.size(), true));
        assertFalse(Files.exists(f.root.resolve("runtime/bin/server/jvm.dll")));
        assertEquals(0, f.run(0, false));
        TreeDeltaEngine.verify(f.root, f.old, f.oldHash);
        // Здесь запускается ОС PowerShell; файлы exe фиктивны, portable E2E этим не доказывается.
    }

    @Test void unexpectedBackupContentSurvivesCleanupAndDuplicateKeysAreRejected() throws Exception {
        windows();
        HelperFixture f = new HelperFixture(temporary.resolve("unmanaged"));
        assertNotEquals(0, f.run(2, true));
        HelperFixture.put(f.updates, "Backup/private.txt", "unmanaged-backup");
        assertEquals(0, f.run(0, false));
        assertEquals("unmanaged-backup", Files.readString(f.updates.resolve("Backup/private.txt")));
        HelperFixture duplicate = new HelperFixture(temporary.resolve("duplicates"));
        String journal = InstallFiles.read(duplicate.updates.resolve("install-journal.json"), InstallFiles.MAX_METADATA_BYTES);
        InstallFiles.write(duplicate.updates.resolve("install-journal.json"),
                journal.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"));
        assertEquals(1, duplicate.run(0, false));
        duplicate.assertUserFiles();
    }

    @Test void phaseCrashesRecoverOnlyACompleteOldOrNewVersion() throws Exception {
        windows();
        for (String phase : List.of("PREPARED", "BACKING_UP", "INSTALLING", "VERIFYING", "COMMITTED")) {
            HelperFixture f = new HelperFixture(temporary.resolve("phase-" + phase));
            assertNotEquals(0, f.run(0, true, phase));
            assertEquals(0, f.run(0, false));
            List<ru.cashprediction.core.update.model.FileEntry> actual = TreeDeltaEngine.inventory(f.root);
            assertTrue(actual.equals(f.old) || actual.equals(f.target.files()), phase);
            f.assertUserFiles();
        }
        HelperFixture waiting = new HelperFixture(temporary.resolve("phase-WAITING"));
        String lease = UUID.randomUUID().toString();
        waiting.lease(lease, ProcessHandle.current().pid(),
                ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli(), waiting.root.toString(), "web");
        assertNotEquals(0, waiting.run(0, true, "WAITING"));
        Files.delete(waiting.updates.resolve("processes").resolve(lease + ".json"));
        assertEquals(0, waiting.run(0, false));
        TreeDeltaEngine.verify(waiting.root, waiting.target.files(), waiting.target.treeSha256());
        HelperFixture rollback = new HelperFixture(temporary.resolve("phase-ROLLING_BACK"));
        assertNotEquals(0, rollback.run(2 * rollback.old.size() + 2, true));
        assertNotEquals(0, rollback.run(0, true, "ROLLING_BACK"));
        assertEquals(0, rollback.run(0, false));
        TreeDeltaEngine.verify(rollback.root, rollback.old, rollback.oldHash);
    }

    /** Symlink отклоняется до обращения к внешним данным; успешный rollback не означает установку. */
    @Test void sourceAndDestinationReparsePointsAreRejectedBeforeTouchingExternalData() throws Exception {
        windows();
        for (String location : List.of("Backup", "Ready/tree/runtime")) {
            HelperFixture f = new HelperFixture(temporary.resolve("reparse-" + location.replace('/', '-')));
            Path outside = Files.createDirectory(temporary.resolve("outside-" + location.replace('/', '-')));
            Files.writeString(outside.resolve("sentinel.txt"), "outside-data");
            HelperFixture.put(outside, "nested/untouched.txt", "outside-nested");
            Map<String, String> before = junctionOutsideInventory(outside);
            Path link = f.updates.resolve(location);
            if (Files.exists(link)) Files.move(link, f.updates.resolve("held-runtime"));
            // Прежний capability assumption только для symlink; обязательные junction-тесты не пропускаются.
            try { Files.createSymbolicLink(link, outside); }
            catch (java.io.IOException | UnsupportedOperationException denied) {
                assumeTrue(false, "Windows symlink privilege unavailable");
            }
            try {
                assertTrue(Files.isSymbolicLink(link));
                assertEquals(outside.toRealPath(), link.toRealPath());
                assertFalse(Files.exists(f.updates.resolve("requests")));
                assertFalse(Files.exists(f.updates.resolve("processes")));
                assertPublishedGuardRejectsSymlink(f, link);
                int exit = f.run(0, false);
                if (location.equals("Backup")) {
                    // Reparse в backup запрещает также восстановление: outer catch возвращает ошибку.
                    assertEquals(1, exit, f::diagnostics);
                    assertTrue(f.diagnostics().contains("REPARSE_PATH"), f::diagnostics);
                    assertFalse(Files.exists(f.updates.resolve("last-install.json")));
                } else {
                    // Source Guard срабатывает до moves; проверенное старое дерево завершает rollback.
                    assertEquals(0, exit, f::diagnostics);
                    assertEquals("ROLLED_BACK",
                            InstallFiles.object(f.updates.resolve("last-install.json")).get("outcome"));
                    String log = Files.readString(f.updates.resolve("update-log.md"));
                    assertTrue(log.contains("PHASE_ROLLING_BACK"));
                    assertFalse(log.contains("PHASE_COMMITTED"));
                    assertFalse(Files.exists(f.updates.resolve("install-journal.json")));
                }
                assertEquals(before, junctionOutsideInventory(outside));
                TreeDeltaEngine.verify(f.root, f.old, f.oldHash);
                f.assertUserFiles();
            } finally {
                // Files.delete удаляет только собственный symlink entry, не target и не его дерево.
                if (Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    assertTrue(Files.isSymbolicLink(link), "UNLINK_NOT_SYMLINK");
                    Files.delete(link);
                }
            }
            assertEquals(before, junctionOutsideInventory(outside));
        }
    }

    /** Исполняет только настоящий Guard опубликованного helper и требует точный отказ reparse. */
    private void assertPublishedGuardRejectsSymlink(HelperFixture f, Path link) throws Exception {
        String helper = f.updates.resolve("apply-update.ps1").toString().replace("'", "''");
        String root = f.root.toString().replace("'", "''");
        String path = link.toString().replace("'", "''");
        Path output = f.updates.resolve("symlink-guard-output.txt");
        runJunctionCommand("$ErrorActionPreference='Stop'; $root='" + root + "'; "
                + "$tokens=$null;$errors=$null; $ast=[Management.Automation.Language.Parser]::ParseFile('"
                + helper + "',[ref]$tokens,[ref]$errors); if ($errors.Count) {throw 'HELPER_PARSE'}; "
                + "$guards=@($ast.EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.FunctionDefinitionAst] -and $_.Name -ceq 'Guard'}); "
                + "if ($guards.Count -ne 1) {throw 'GUARD_CENSUS'}; . ([scriptblock]::Create($guards[0].Extent.Text)); "
                + "try { [void](Guard '" + path + "'); throw 'GUARD_ACCEPTED_SYMLINK' } "
                + "catch {if ($_.Exception.Message -cne 'REPARSE_PATH') {throw}; Write-Output 'EXACT_GUARD_REPARSE_PATH'}", output);
        assertTrue(Files.readString(output).contains("EXACT_GUARD_REPARSE_PATH"));
    }

    /** Junction в source проверяется без права создания symbolic link. */
    @Test void sourceDirectoryJunctionIsRejectedBeforeTouchingExternalData() throws Exception {
        windows();
        assertDirectoryJunctionRejected("Ready/tree/runtime");
    }

    /** Junction в destination backup не должен разрешать запись вне installation root. */
    @Test void destinationDirectoryJunctionIsRejectedBeforeTouchingExternalData() throws Exception {
        windows();
        assertDirectoryJunctionRejected("Backup");
    }

    /** Проверяет настоящий reparse tag junction, точный отказ Guard и полное внешнее дерево. */
    private void assertDirectoryJunctionRejected(String location) throws Exception {
        HelperFixture f = new HelperFixture(temporary.resolve("junction-" + location.replace('/', '-')));
        Path outside = Files.createDirectory(temporary.resolve("outside-junction"));
        Path sentinel = outside.resolve("sentinel.bin");
        byte[] bytes = new byte[] {0, 1, 42, (byte) 0xff, 13, 10};
        Files.write(sentinel, bytes);
        HelperFixture.put(outside, "nested/untouched.txt", "outside-data");
        Map<String, String> before = junctionOutsideInventory(outside);
        Path link = f.updates.resolve(location);
        if (Files.exists(link)) Files.move(link, f.updates.resolve("held-runtime"));
        // Пустые requests/leases исключают запуск клиентов и lookup чужих PID из lease.
        assertFalse(Files.exists(f.updates.resolve("requests")));
        assertFalse(Files.exists(f.updates.resolve("processes")));
        Path diagnostics = temporary.resolve("junction-output.txt");
        String quotedLink = link.toString().replace("'", "''");
        String quotedOutside = outside.toString().replace("'", "''");
        try {
            runJunctionCommand("$ErrorActionPreference='Stop'; "
                    + "New-Item -ItemType Junction -Path '" + quotedLink + "' -Target '" + quotedOutside + "' | Out-Null; "
                    + "$item=Get-Item -LiteralPath '" + quotedLink + "' -Force; "
                    + "if ($item.LinkType -cne 'Junction' -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -eq 0) {throw 'NOT_JUNCTION'}; "
                    + "Write-Output ('JUNCTION_VERIFIED '+$item.FullName)", diagnostics);
            assertTrue(Files.isDirectory(link));
            assertEquals(outside.toRealPath(), link.toRealPath());
            // Точный Guard извлекается из фактически опубликованного helper, без исполнения его top-level body.
            Path guardOutput = temporary.resolve("junction-guard-output.txt");
            String quotedHelper = f.updates.resolve("apply-update.ps1").toString().replace("'", "''");
            runJunctionCommand("$ErrorActionPreference='Stop'; $root='" + f.root.toString().replace("'", "''") + "'; "
                    + "$tokens=$null;$errors=$null; $ast=[Management.Automation.Language.Parser]::ParseFile('"
                    + quotedHelper + "',[ref]$tokens,[ref]$errors); if ($errors.Count) {throw 'HELPER_PARSE'}; "
                    + "$guards=@($ast.EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.FunctionDefinitionAst] -and $_.Name -ceq 'Guard'}); "
                    + "if ($guards.Count -ne 1) {throw 'GUARD_CENSUS'}; . ([scriptblock]::Create($guards[0].Extent.Text)); "
                    + "try { [void](Guard '" + quotedLink + "'); throw 'GUARD_ACCEPTED_JUNCTION' } "
                    + "catch {if ($_.Exception.Message -cne 'REPARSE_PATH') {throw}; Write-Output 'EXACT_GUARD_REPARSE_PATH'}", guardOutput);
            assertTrue(Files.readString(guardOutput).contains("EXACT_GUARD_REPARSE_PATH"));
            int exit = f.run(0, false);
            if (location.equals("Backup")) {
                assertEquals(1, exit, f::diagnostics);
                assertTrue(f.diagnostics().contains("REPARSE_PATH"), f::diagnostics);
            } else {
                // Source отказ ловится install catch: штатный rollback возвращает 0, но target не committed.
                assertEquals(0, exit, f::diagnostics);
                assertEquals("ROLLED_BACK", InstallFiles.object(f.updates.resolve("last-install.json")).get("outcome"));
                assertTrue(Files.readString(f.updates.resolve("update-log.md")).contains("PHASE_ROLLING_BACK"));
            }
            assertArrayEquals(bytes, Files.readAllBytes(sentinel));
            assertEquals(before, junctionOutsideInventory(outside));
            TreeDeltaEngine.verify(f.root, f.old, f.oldHash);
            f.assertUserFiles();
            System.out.println("JUNCTION_GUARD " + location + " exit=" + exit + " EXACT_GUARD_REPARSE_PATH outsideTreeUnchanged=true sentinelUnchanged=true helperSha256="
                    + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(Files.readAllBytes(f.updates.resolve("apply-update.ps1")))));
        } finally {
            // Удаляется только junction entry, никогда не target или рекурсивное внешнее дерево.
            runJunctionCommand("$ErrorActionPreference='Stop'; if (Test-Path -LiteralPath '" + quotedLink + "') { "
                    + "$item=Get-Item -LiteralPath '" + quotedLink + "' -Force; "
                    + "if ($item.LinkType -cne 'Junction') {throw 'UNLINK_NOT_JUNCTION'}; "
                    + "[IO.Directory]::Delete('" + quotedLink + "') }", temporary.resolve("junction-unlink-output.txt"));
        }
        assertFalse(Files.exists(link));
        assertArrayEquals(bytes, Files.readAllBytes(sentinel));
        assertEquals(before, junctionOutsideInventory(outside));
    }

    /** Снимок всех каталогов и содержимого файлов вне installation root, без traversal через links. */
    private Map<String, String> junctionOutsideInventory(Path outside) throws Exception {
        Map<String, String> inventory = new LinkedHashMap<>();
        try (var paths = Files.walk(outside)) {
            for (Path path : paths.sorted().toList()) {
                assertFalse(Files.isSymbolicLink(path));
                String value = Files.isDirectory(path) ? "DIRECTORY" : java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
                inventory.put(outside.relativize(path).toString(), value);
            }
        }
        return inventory;
    }

    /** Создаёт/снимает только fixture junction; отказ является FAIL, а не privilege skip. */
    private void runJunctionCommand(String command, Path output) throws Exception {
        // Только child process: исключаем локализованный CLIXML progress и явно фиксируем UTF-8 output.
        String isolatedCommand = "$ProgressPreference='SilentlyContinue'; [Console]::OutputEncoding=[Text.UTF8Encoding]::new($false); " + command;
        String encoded = Base64.getEncoder().encodeToString(isolatedCommand.getBytes(StandardCharsets.UTF_16LE));
        Process junction = new ProcessBuilder(Path.of(System.getenv("SystemRoot"),
                "System32/WindowsPowerShell/v1.0/powershell.exe").toString(),
                "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(junction.waitFor(20, TimeUnit.SECONDS), "JUNCTION_COMMAND_TIMEOUT");
            assertEquals(0, junction.exitValue(), () -> {
                try { return Files.readString(output); } catch (java.io.IOException error) { return error.toString(); }
            });
        } finally { junction.destroyForcibly(); }
    }

    @Test void unicodeReadOnlyFilesSurviveRollback() throws Exception {
        windows();
        HelperFixture f = new HelperFixture(temporary.resolve("unicode-\u041f\u0443\u0442\u044c-'quote'"));
        Files.setAttribute(f.root.resolve("app/old.jar"), "dos:readonly", true);
        Files.setAttribute(f.ready.resolve("app/new.jar"), "dos:readonly", true);
        List<ru.cashprediction.core.update.model.FileEntry> old = TreeDeltaEngine.inventory(f.root);
        List<ru.cashprediction.core.update.model.FileEntry> target = TreeDeltaEngine.inventory(f.ready);
        var updated = new ru.cashprediction.core.update.model.UpdateManifest(f.target.releaseNumber(),
                f.target.commitSha(), f.target.version(), f.target.publishedAtUtc(), f.target.assetName(),
                f.target.sizeBytes(), f.target.sha256(), TreeDeltaEngine.treeHash(target), target, List.of());
        Map<String, Object> journal = InstallJournal.prepare(f.root, updated);
        InstallJournal.write(f.updates, journal);
        assertNotEquals(0, f.run(2 * old.size() + 2, true));
        assertEquals(0, f.run(0, false));
        TreeDeltaEngine.verify(f.root, old, TreeDeltaEngine.treeHash(old));
        assertTrue((Boolean) Files.getAttribute(f.root.resolve("app/old.jar"), "dos:readonly"));
        f.assertUserFiles();
    }

    @Test void registrationWriteFailureDoesNotAuthorizeReplacementWhileUnregisteredNativeClientLives() throws Exception {
        windows();
        HelperFixture f = new HelperFixture(temporary.resolve("unregistered"));
        Path launcher = f.root.resolve("CashPrediction-Swing.exe");
        Files.copy(Path.of(System.getenv("SystemRoot"), "System32/ping.exe"), launcher,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        List<ru.cashprediction.core.update.model.FileEntry> old = TreeDeltaEngine.inventory(f.root);
        InstallJournal.write(f.updates, InstallJournal.prepare(f.root, f.target));
        // Ошибка регистрации моделируется настоящим отказом создать каталог поверх обычного файла.
        Files.writeString(f.updates.resolve("processes"), "registration-denied");
        assertThrows(java.io.IOException.class, () -> ProcessLease.register(f.root, f.updates, "swing"));
        Files.delete(f.updates.resolve("processes"));
        Process nativeClient = new ProcessBuilder(launcher.toString(), "-n", "30", "127.0.0.1")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        Process helper = f.start(0, false);
        try {
            assertTrue(nativeClient.isAlive());
            assertFalse(helper.waitFor(2, TimeUnit.SECONDS));
            TreeDeltaEngine.verify(f.root, old, TreeDeltaEngine.treeHash(old));
            assertFalse(Files.exists(f.updates.resolve("Backup")));
            f.assertUserFiles();
            nativeClient.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            assertTrue(helper.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, helper.exitValue());
            TreeDeltaEngine.verify(f.root, f.target.files(), f.target.treeSha256());
            f.assertUserFiles();
        } finally {
            // Только собственный тестовый процесс; production-помощник клиентов не завершает.
            nativeClient.destroyForcibly();
            helper.destroyForcibly();
        }
    }

    @Test void everyJournalPublicationFailureEitherRollsBackOrLeavesReplayableEvidence() throws Exception {
        windows();
        HelperFixture count = new HelperFixture(temporary.resolve("journal-count"));
        int writes = 4 + 2 * (count.old.size() + count.target.files().size());
        for (int write = 1; write <= writes; write++) {
            HelperFixture f = new HelperFixture(temporary.resolve("journal-fail-" + write));
            int exit = f.run(0, false, "", write);
            if (exit != 0) {
                assertTrue(Files.exists(f.updates.resolve("install-journal.json")));
                assertEquals(0, f.run(0, false));
            }
            List<ru.cashprediction.core.update.model.FileEntry> actual = TreeDeltaEngine.inventory(f.root);
            assertTrue(actual.equals(f.old) || actual.equals(f.target.files()), "write=" + write);
            f.assertUserFiles();
        }
        HelperFixture persistent = new HelperFixture(temporary.resolve("journal-persistent"));
        assertEquals(1, persistent.run(0, false, "", -1));
        TreeDeltaEngine.verify(persistent.root, persistent.old, persistent.oldHash);
        assertFalse(Files.exists(persistent.updates.resolve("Backup")));
        assertEquals(0, persistent.run(0, false));
    }

    @Test void fileDirectoryTransitionDoesNotPreventCompleteRollback() throws Exception {
        windows();
        for (boolean forward : List.of(true, false)) {
            HelperFixture f = new HelperFixture(temporary.resolve("transition-" + forward));
            HelperFixture.put(f.root, forward ? "app/switch" : "app/switch/child", "old-transition");
            HelperFixture.put(f.ready, forward ? "app/switch/child" : "app/switch", "new-transition");
            List<ru.cashprediction.core.update.model.FileEntry> old = TreeDeltaEngine.inventory(f.root);
            List<ru.cashprediction.core.update.model.FileEntry> target = TreeDeltaEngine.inventory(f.ready);
            var manifest = new ru.cashprediction.core.update.model.UpdateManifest(f.target.releaseNumber(),
                    f.target.commitSha(), f.target.version(), f.target.publishedAtUtc(), f.target.assetName(),
                    f.target.sizeBytes(), f.target.sha256(), TreeDeltaEngine.treeHash(target), target, List.of());
            InstallJournal.write(f.updates, InstallJournal.prepare(f.root, manifest));
            assertNotEquals(0, f.run(2 * (old.size() + target.size()), true));
            assertEquals(0, f.run(0, false));
            TreeDeltaEngine.verify(f.root, old, TreeDeltaEngine.treeHash(old));
            f.assertUserFiles();
        }
    }
}
