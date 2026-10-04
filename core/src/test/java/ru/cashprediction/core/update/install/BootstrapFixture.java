package ru.cashprediction.core.update.install;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import ru.cashprediction.core.update.model.FileEntry;
import ru.cashprediction.core.update.model.UpdateCodec;
import ru.cashprediction.core.update.model.UpdateManifest;
import ru.cashprediction.core.update.tree.TreeDeltaEngine;

/** Отдельная portable-shaped fixture; её синтетические exe не доказывают нативный cold launch. */
final class BootstrapFixture {
    final Path root;
    final Path updates;
    final Path ready;
    final List<FileEntry> old;
    final String oldHash;
    final UpdateManifest target;
    final Map<String, Object> journal;
    final Map<String, String> userSnapshot;

    BootstrapFixture(Path path) throws IOException {
        this(path, false);
    }

    /** Внешние модульные JAR моделируются отдельными управляемыми файлами, не нативным кодом. */
    BootstrapFixture(Path path, boolean externalModules) throws IOException {
        root = Files.createDirectories(path).toRealPath();
        updates = Files.createDirectories(root.resolve("CashMemory/Updates"));
        ready = Files.createDirectories(updates.resolve("Ready/tree"));
        for (String name : List.of("CashPrediction", "CashPrediction-Swing", "CashPrediction-Web")) {
            put(root, name + ".exe", "old-native-" + name);
            put(ready, name + ".exe", "new-native-" + name);
            String module = name.equals("CashPrediction") ? "ru.cashprediction.fx/ru.cashprediction.fx.FxMain"
                    : name.endsWith("Swing") ? "ru.cashprediction.swing/ru.cashprediction.swing.SwingMain"
                    : "ru.cashprediction.web/ru.cashprediction.web.WebMain";
            String modulePath = externalModules ? "java-options=--module-path\r\njava-options=$APPDIR\r\n" : "";
            put(root, "app/" + name + ".cfg", cfg(module, 1) + modulePath);
            put(ready, "app/" + name + ".cfg", cfg(module, 2) + modulePath);
        }
        if (externalModules) {
            for (String module : List.of("core", "ui-fx", "ui-swing", "web")) {
                put(root, "app/cashprediction-" + module + "-1.0.0.jar", "old-module-" + module);
                put(ready, "app/cashprediction-" + module + "-1.0.0.jar", "new-module-" + module);
            }
        }
        for (String pathPart : List.of("runtime/bin/jli.dll", "runtime/bin/server/jvm.dll", "runtime/lib/modules",
                "runtime/bin/java.exe", "runtime/release")) {
            put(root, pathPart, "old-runtime-" + pathPart);
            put(ready, pathPart, "new-runtime-" + pathPart);
        }
        put(root, "app/.jpackage.xml", "old-package-metadata");
        put(ready, "app/.jpackage.xml", "new-package-metadata");
        put(root, "app/old-resource.txt", "old-resource");
        put(ready, "app/new-resource.txt", "new-resource");
        put(root, "CashMemory/plans/user.md", "user-plan");
        put(root, "CashMemory/settings.md", "user-settings");
        put(root, "private.txt", "unmanaged-root");
        userSnapshot = snapshot();
        old = TreeDeltaEngine.inventory(root);
        oldHash = TreeDeltaEngine.treeHash(old);
        var files = TreeDeltaEngine.inventory(ready);
        target = new UpdateManifest(2, "b".repeat(40), "2", Instant.parse("2026-10-03T00:00:00Z"),
                "CashPrediction-portable.zip", 1, "c".repeat(64), TreeDeltaEngine.treeHash(files), files, List.of());
        InstallFiles.write(updates.resolve("Ready/update.json"), UpdateCodec.write(target));
        journal = InstallJournal.preparePortable(root, target);
        InstallJournal.write(updates, journal);
        PowerShellHelper.publish(updates);
    }

    private static String cfg(String module, int release) {
        return "[Application]\r\napp.mainmodule=" + module + "\r\n\r\n[JavaOptions]\r\n"
                + "java-options=-Djpackage.app-version=" + release + "\r\njava-options=-Xmx512m\r\n";
    }

    static void put(Path root, String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    Result run(int boundary, boolean crash, String phase, int journalFailure) throws Exception {
        var command = new java.util.ArrayList<>(List.of(Path.of(System.getenv("SystemRoot"),
                "System32/WindowsPowerShell/v1.0/powershell.exe").toString(), "-NoProfile", "-NonInteractive",
                "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass", "-File", updates.resolve("apply-update.ps1").toString(),
                "-InstallationRoot", root.toString(), "-Diagnostics", "-FaultAt", Integer.toString(boundary)));
        if (crash) command.add("-Crash");
        if (!phase.isEmpty()) command.addAll(List.of("-FaultPhase", phase));
        if (journalFailure != 0) command.addAll(List.of("-JournalFailAt", Integer.toString(journalFailure)));
        Process helper = new ProcessBuilder(command).redirectErrorStream(true).start();
        AtomicReference<String> diagnostics = new AtomicReference<>("");
        Thread capture = new Thread(() -> {
            ByteArrayOutputStream kept = new ByteArrayOutputStream();
            try (var stream = helper.getInputStream()) {
                byte[] buffer = new byte[4096];
                for (int count; (count = stream.read(buffer)) != -1;) {
                    int keep = Math.min(count, 65536 - kept.size());
                    if (keep > 0) kept.write(buffer, 0, keep);
                }
            } catch (IOException ignored) { }
            diagnostics.set(kept.toString(StandardCharsets.UTF_8));
        }, "bootstrap-fixture-output");
        capture.setDaemon(true);
        capture.start();
        try {
            if (!helper.waitFor(30, TimeUnit.SECONDS)) throw new AssertionError("BOOTSTRAP_HELPER_TIMEOUT");
            capture.join(5000);
            return new Result(helper.exitValue(), diagnostics.get());
        } finally { helper.destroyForcibly(); }
    }

    int boundaries() {
        long runtime = old.stream().filter(file -> PortableBootstrap.protectedPayload(file.path())).count();
        long stable = old.stream().filter(file -> PortableBootstrap.STABLE.contains(file.path())).count();
        long replacements = target.files().stream().filter(file -> PortableBootstrap.STABLE.contains(file.path())
                && !PortableBootstrap.CONFIGS.contains(file.path())).count();
        long oldMoves = old.size() - stable;
        long newMoves = target.files().size() - stable;
        return Math.toIntExact(2 * (runtime + stable + 3 + 1 + oldMoves + newMoves + replacements + 3));
    }

    void userData() throws IOException {
        org.junit.jupiter.api.Assertions.assertEquals(userSnapshot, snapshot());
        org.junit.jupiter.api.Assertions.assertEquals("unmanaged-root", Files.readString(root.resolve("private.txt")));
    }

    private Map<String, String> snapshot() throws IOException {
        Map<String, String> result = new java.util.TreeMap<>();
        Path memory = root.resolve("CashMemory");
        try (var files = Files.walk(memory)) {
            for (Path file : files.filter(Files::isRegularFile).filter(path -> !path.startsWith(updates)).toList()) {
                result.put(memory.relativize(file).toString(), PortableBootstrap.sha(Files.readAllBytes(file)));
            }
        }
        return result;
    }

    /** Ограниченный результат одного самостоятельного PowerShell-процесса. */
    record Result(int exit, String diagnostics) { }
}
