package ru.cashprediction.fx;

import java.io.File;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in fork проверяет ранний handoff при уже живом toolkit и контрольный простой return.
 * Запускать только после terminal основного reactor под управлением владельца интеграции.
 * По умолчанию класс пропускается и не запускает JavaFX или процессы.
 */
@EnabledIfSystemProperty(named = "fx.earlyHandoffProof", matches = "true")
class FxEarlyHandoffProcessTest {
    @TempDir Path temporary;

    /** Настоящий exitAfterLaunch завершает fork с сохранённым кодом после реального beforeUi=false. */
    @Test void deniedSessionExitsWithToolkitAlreadyInitialized() throws Exception {
        checkFork("exit");
    }

    /** Тот же gate отвергает прежний простой return; hung negative control принудительно очищается. */
    @Test void returnWithoutExitIsRejectedByBoundedGate() throws Exception {
        checkFork("return");
    }

    /** Ограничивает запуск, handshake и завершение; журналы и runtime temp принадлежат одному опыту. */
    private void checkFork(String mode) throws Exception {
        String token = UUID.randomUUID().toString();
        Path owned = Files.createDirectory(temporary.resolve(mode + "-" + token));
        Path runtime = Files.createDirectory(owned.resolve("runtime")), log = owned.resolve("fork.log");
        List<String> command = command(mode, token, runtime);
        Files.writeString(owned.resolve("command.txt"), String.join(System.lineSeparator(), command), StandardCharsets.UTF_8);
        Process process = new ProcessBuilder(command).directory(owned.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        ProcessHandle handle = process.toHandle();
        long pid = handle.pid();
        Instant started = handle.info().startInstant().orElse(null);
        try {
            assertNotNull(started, "Owned process start identity unavailable");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            String prefix = "PROBE:" + token + ":";
            awaitMarkers(process, log, prefix, pid, mode.equals("return"), deadline);
            if (mode.equals("exit")) requireExit(process, log, deadline);
            else {
                assertTrue(process.isAlive(), read(log));
                var rejected = assertThrows(AssertionError.class,
                        () -> requireExit(process, log, Math.min(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(2))));
                assertTrue(rejected.getMessage().contains("exit deadline"), rejected.getMessage());
                assertTrue(process.isAlive(), "Negative control must remain alive until owned cleanup");
            }
        } finally { cleanup(process, handle, pid, started); }
    }

    /** Проверяет настоящие callback/denied markers с nonce и PID; отсутствие маркеров не становится PASS. */
    private static void awaitMarkers(Process process, Path log, String prefix, long pid,
                                     boolean returning, long deadline) throws Exception {
        while (System.nanoTime() - deadline < 0) {
            String output = read(log);
            boolean ready = output.lines().anyMatch(line -> line.equals(prefix + "TOOLKIT_READY:" + pid));
            boolean denied = output.lines().anyMatch(line -> line.equals(prefix + "BEFORE_DENIED:CLOSED=1"));
            boolean returned = !returning || output.lines().anyMatch(line -> line.equals(prefix + "RETURN_WITHOUT_EXIT"));
            if (ready && denied && returned) return;
            if (!process.isAlive()) fail("Fork ended before required actual-toolkit markers: " + output);
            Thread.sleep(25);
        }
        fail("Actual toolkit/denied handshake deadline: " + read(log));
    }

    /** Один и тот же ограниченный критерий принимает exit=7 и отвергает keepalive после простого return. */
    private static void requireExit(Process process, Path log, long deadline) throws Exception {
        long left = deadline - System.nanoTime();
        assertTrue(left > 0 && process.waitFor(left, TimeUnit.NANOSECONDS), "exit deadline: " + read(log));
        assertEquals(7, process.exitValue(), read(log));
    }

    /** Завершает только удержанный Process после проверки PID/start identity, без поиска процессов по имени. */
    private static void cleanup(Process process, ProcessHandle held, long pid, Instant started) throws Exception {
        if (!process.isAlive()) return;
        assertEquals(pid, process.pid(), "Owned PID changed");
        assertEquals(pid, held.pid(), "Held process identity changed");
        if (started != null) assertEquals(started, held.info().startInstant().orElse(null), "Owned start identity changed");
        // Process объект удерживается с момента start: новый ProcessHandle по повторно использованному PID не открывается.
        process.destroyForcibly();
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            while (process.isAlive() && System.nanoTime() - deadline < 0) {
                try { process.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
                catch (InterruptedException interruption) { interrupted = true; }
            }
            assertFalse(process.isAlive(), "Owned fork cleanup deadline");
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    /** Использует реальные named OpenJFX JAR из classpath стенда, чтобы Java launcher увидел Application. */
    private static List<String> command(String mode, String token, Path runtime) {
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var modules = new LinkedHashMap<String, Path>(); List<String> application = new ArrayList<>();
        for (String entry : cp.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            Path path = Path.of(entry).toAbsolutePath().normalize();
            if (Files.isRegularFile(path) && path.getFileName().toString().startsWith("javafx-")) {
                for (var module : ModuleFinder.of(path).findAll()) {
                    String name = module.descriptor().name();
                    if (!module.descriptor().isAutomatic() && List.of("javafx.base", "javafx.graphics", "javafx.controls").contains(name)) {
                        Path previous = modules.putIfAbsent(name, path);
                        assertTrue(previous == null || previous.equals(path), "Duplicate named JavaFX module: " + name);
                    }
                }
            } else application.add(path.toString());
        }
        assertTrue(modules.keySet().containsAll(List.of("javafx.base", "javafx.graphics", "javafx.controls")),
                "Actual named JavaFX modules absent from test classpath: " + cp);
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-XX:-UsePerfData", "-Dfile.encoding=UTF-8", "-Djava.io.tmpdir=" + runtime,
                "--module-path", String.join(File.pathSeparator, modules.values().stream().map(Path::toString).toList()),
                "--add-modules", "javafx.controls", "--enable-native-access=javafx.graphics",
                "-cp", String.join(File.pathSeparator, application),
                "ru.cashprediction.fx.FxEarlyHandoffApplication", mode, token);
    }

    /** Читает только собственный bounded журнал; неизвестный или чрезмерный вывод отвергается. */
    private static String read(Path log) throws Exception {
        if (!Files.exists(log)) return "";
        assertTrue(Files.size(log) <= 1024 * 1024, "Fork log exceeds limit");
        return Files.readString(log, StandardCharsets.UTF_8);
    }
}
