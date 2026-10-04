package ru.cashprediction.core.update.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import ru.cashprediction.core.json.JsonWriter;
import ru.cashprediction.core.update.model.FileEntry;
import ru.cashprediction.core.update.model.UpdateCodec;
import ru.cashprediction.core.update.model.UpdateManifest;
import ru.cashprediction.core.update.tree.TreeDeltaEngine;

/** Реальное файловое дерево и автономный PowerShell-процесс для аварийных сценариев. */
final class HelperFixture {
    final Path root;
    final Path updates;
    final Path ready;
    final List<FileEntry> old;
    final String oldHash;
    final UpdateManifest target;
    final Map<String, Object> journal;

    HelperFixture(Path root) throws IOException {
        this.root = Files.createDirectories(root).toRealPath();
        updates = Files.createDirectories(this.root.resolve("CashMemory/Updates"));
        ready = Files.createDirectories(updates.resolve("Ready/tree"));
        for (String exe : List.of("CashPrediction.exe", "CashPrediction-Swing.exe", "CashPrediction-Web.exe")) {
            put(this.root, exe, "old-" + exe);
            put(ready, exe, "new-" + exe);
        }
        put(this.root, "app/old.jar", "old-app");
        put(this.root, "runtime/bin/server/jvm.dll", "old-runtime");
        put(ready, "app/new.jar", "new-app");
        put(ready, "runtime/bin/server/jvm.dll", "new-runtime");
        put(this.root, "CashMemory/plans/user.md", "user-plan");
        put(this.root, "CashMemory/settings.md", "user-settings");
        put(this.root, "notes.txt", "unmanaged-root");
        old = TreeDeltaEngine.inventory(this.root);
        oldHash = TreeDeltaEngine.treeHash(old);
        List<FileEntry> files = TreeDeltaEngine.inventory(ready);
        target = new UpdateManifest(2, "b".repeat(40), "2", Instant.parse("2026-10-03T00:00:00Z"),
                "CashPrediction-portable.zip", 1, "c".repeat(64), TreeDeltaEngine.treeHash(files), files, List.of());
        InstallFiles.write(updates.resolve("Ready/update.json"), UpdateCodec.write(target));
        journal = InstallJournal.prepare(this.root, target);
        InstallJournal.write(updates, journal);
        PowerShellHelper.publish(updates);
    }

    static void put(Path base, String relative, String bytes) throws IOException {
        Path file = base.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, bytes, StandardCharsets.UTF_8);
    }

    void save() throws IOException {
        InstallJournal.write(updates, journal);
    }

    Process start(int fault, boolean crash) throws IOException {
        return start(fault, crash, "");
    }

    Process start(int fault, boolean crash, String phase) throws IOException {
        return start(fault, crash, phase, 0);
    }

    Process start(int fault, boolean crash, String phase, int journalFail) throws IOException {
        String windows = System.getenv("SystemRoot");
        var command = new java.util.ArrayList<>(List.of(
                Path.of(windows, "System32/WindowsPowerShell/v1.0/powershell.exe").toString(),
                "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass",
                "-File", updates.resolve("apply-update.ps1").toString(), "-InstallationRoot", root.toString(),
                "-FaultAt", Integer.toString(fault), "-Diagnostics"));
        if (crash) command.add("-Crash");
        if (!phase.isEmpty()) command.addAll(List.of("-FaultPhase", phase));
        if (journalFail != 0) command.addAll(List.of("-JournalFailAt", Integer.toString(journalFail)));
        return new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(updates.resolve("helper-test-output.md").toFile()).start();
    }

    /** Возвращает ограниченную диагностику тестового помощника вместо потери причины nonzero exit. */
    String diagnostics() {
        try (var stream = Files.newInputStream(updates.resolve("helper-test-output.md"))) {
            return new String(stream.readNBytes(65536), StandardCharsets.UTF_8);
        } catch (IOException failure) { return failure.getClass().getSimpleName(); }
    }

    int run(int fault, boolean crash) throws Exception {
        return run(fault, crash, "");
    }

    int run(int fault, boolean crash, String phase) throws Exception {
        return run(fault, crash, phase, 0);
    }

    int run(int fault, boolean crash, String phase, int journalFail) throws Exception {
        Process helper = start(fault, crash, phase, journalFail);
        try {
            if (!helper.waitFor(20, TimeUnit.SECONDS)) throw new AssertionError("HELPER_TIMEOUT");
            return helper.exitValue();
        } finally { helper.destroyForcibly(); }
    }

    void lease(String name, long pid, long birth, String claimedRoot, String client) throws IOException {
        InstallFiles.write(updates.resolve("processes").resolve(name + ".json"), JsonWriter.write(Map.of(
                "schemaVersion", 1, "leaseId", name, "pid", pid, "startedAtEpochMillis", birth,
                "installationRoot", claimedRoot, "client", client)));
    }

    void assertUserFiles() throws IOException {
        org.junit.jupiter.api.Assertions.assertEquals("user-plan", Files.readString(root.resolve("CashMemory/plans/user.md")));
        org.junit.jupiter.api.Assertions.assertEquals("user-settings", Files.readString(root.resolve("CashMemory/settings.md")));
        org.junit.jupiter.api.Assertions.assertEquals("unmanaged-root", Files.readString(root.resolve("notes.txt")));
    }
}
