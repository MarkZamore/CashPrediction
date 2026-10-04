package ru.cashprediction.parity.browserprobe;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.json.JsonWriter;
import ru.cashprediction.parity.browser.EdgeLauncher;
import ru.cashprediction.parity.launch.ReactorLayout;
import ru.cashprediction.parity.process.ProcessTree;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Строгий opt-in gate настоящего браузерного reconnect-probe, без зависимости на web test JAR.
 *
 * <p>Включается {@code parity.web.reconnect=true}. Отсутствие браузера, свежих классов,
 * результата или любого обязательного доказательства считается ошибкой, не пропуском.
 * Отдельная JVM читает замороженные классы и исходные JS-фикстуры; их SHA-256 проверяется
 * до и после исполнения. Этот gate проверяет HTTP-фикстуру и async-продолжения, а не заменяет
 * production E2E исходной вкладки после аварии сервера.</p>
 */
@EnabledIfSystemProperty(named = "parity.web.reconnect", matches = "true")
class WebReconnectBrowserIT {
    private static final int MINIMUM_CHECKS = 63;
    private static final String PROBE = "ru.cashprediction.web.js.BrowserReconnectProbe";

    /** Требует реальные проверки WebCrypto, отсутствия replay и применения старых async-ответов. */
    @Test
    @Timeout(150)
    void reconnectProbeHasFreshFrozenInputsAndStrictResult() throws Exception {
        var layout = ReactorLayout.fromSystemProperties();
        EdgeLauncher.findBrowser().orElseThrow(() -> new IllegalStateException(
                "Real browser required: " + EdgeLauncher.searchedLocations()));
        Path root = layout.root();
        Path run = Files.createDirectories(layout.parityRoot().resolve("web-reconnect-probe-" + UUID.randomUUID()));
        Path frozen = Files.createDirectories(run.resolve("input"));
        var hashes = new TreeMap<String, String>();
        for (String name : List.of("BrowserReconnectProbe", "BrowserFixtureProbe")) {
            Path source = root.resolve("web/src/test/java/ru/cashprediction/web/js/" + name + ".java");
            Path compiled = root.resolve("web/target/test-classes/ru/cashprediction/web/js/" + name + ".class");
            assertTrue(Files.isRegularFile(source) && Files.isRegularFile(compiled), "Build probe test classes before enabling gate: " + name);
            assertTrue(Files.getLastModifiedTime(compiled).compareTo(Files.getLastModifiedTime(source)) >= 0,
                    "Probe source is newer than compiled class: " + name);
            copy(source, frozen.resolve("source/" + name + ".java"), frozen, hashes);
        }
        copyTree(root.resolve("web/target/test-classes"), frozen.resolve("web-test"), frozen, hashes);
        copyTree(root.resolve("core/target/classes"), frozen.resolve("core"), frozen, hashes);
        // buildDirectory задаёт папку наблюдений gate, а не место компиляции Maven.
        // Замораживаем каталог реально загруженного моста, без поиска запасных старых классов.
        Path parityClasses = Path.of(EdgeLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        copyTree(parityClasses, frozen.resolve("parity-test"), frozen, hashes);
        copyTree(root.resolve("web/src/main/resources/web/app"), frozen.resolve("web/src/main/resources/web/app"), frozen, hashes);
        copy(root.resolve("web/src/test/resources/ui/reconnect-probe.js"),
                frozen.resolve("web/src/test/resources/ui/reconnect-probe.js"), frozen, hashes);
        assertTrue(Files.isRegularFile(frozen.resolve("parity-test/ru/cashprediction/parity/browser/EdgeLauncher.class")), "Compiled JDK browser bridge required");
        assertTrue(Files.isRegularFile(frozen.resolve("core/ru/cashprediction/core/json/JsonParser.class")), "Compiled core required");
        verifyInputs(frozen, hashes);
        Files.writeString(run.resolve("input-manifest.json"), JsonWriter.write(Map.of(
                "schemaVersion", 1, "gate", "WebReconnectBrowserIT", "minimumChecks", MINIMUM_CHECKS,
                "javaVersion", System.getProperty("java.version"), "files", hashes)));
        String classpath = String.join(File.pathSeparator, List.of(frozen.resolve("web-test").toString(),
                frozen.resolve("core").toString(), frozen.resolve("parity-test").toString()));
        Path evidence = Files.createDirectories(run.resolve("evidence"));
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        var command = List.of(java.toString(), "-XX:-UsePerfData", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-cp", classpath, PROBE, frozen.toString(), evidence.toString());
        Files.writeString(run.resolve("command.txt"), String.join("\n", command));
        Process child = new ProcessBuilder(command).directory(root.toFile())
                .redirectOutput(run.resolve("stdout.log").toFile()).redirectError(run.resolve("stderr.log").toFile()).start();
        try {
            assertTrue(child.waitFor(100, TimeUnit.SECONDS), "Probe deadline exceeded; evidence: " + run);
            assertEquals(0, child.exitValue(), () -> "Probe failed; evidence: " + run + "\n" + readLog(run.resolve("stderr.log")));
        } finally { ProcessTree.kill(child, Duration.ofSeconds(10)).requireClean(); }
        Path resultFile = evidence.resolve("reconnect-result.json");
        assertTrue(Files.isRegularFile(resultFile), "Exit zero without result is not proof");
        var result = JsonParser.parseObject(Files.readString(resultFile));
        assertTrue(Json.longValue(result, "checks", -1) >= MINIMUM_CHECKS, "All required browser checks must execute");
        FormShowEvidenceTest.requirePreserved(result.get("formShowEvidence"));
        assertEquals(1L, Json.longValue(result, "intents", -1), "Exactly one uncertain original intent; no replay");
        for (String counter : List.of("rawKeyLeaks", "badHeaders", "badClientProofs", "obsoleteCursors")) {
            assertEquals(0L, Json.longValue(result, counter, -1), "Missing or nonzero wire counter: " + counter);
        }
        verifyInputs(frozen, hashes);
        Files.writeString(run.resolve("gate-result.json"), JsonWriter.write(Map.of(
                "passed", true, "browser", result, "frozenFiles", hashes.size(), "resultSha256", sha256(resultFile))));
    }

    /** Копирует только реальные файлы одного каталога, сохраняя происхождение всех входов. */
    private static void copyTree(Path source, Path destination, Path frozen, Map<String, String> hashes) throws Exception {
        assertTrue(Files.isDirectory(source), "Missing build input: " + source);
        List<Path> paths;
        try (var walk = Files.walk(source)) { paths = walk.filter(Files::isRegularFile).sorted().toList(); }
        assertFalse(paths.isEmpty(), "Empty build input: " + source);
        for (Path path : paths) copy(path, destination.resolve(source.relativize(path)), frozen, hashes);
    }

    /** Фиксирует SHA исходника до копирования и требует точного совпадения копии. */
    private static void copy(Path source, Path destination, Path frozen, Map<String, String> hashes) throws Exception {
        assertTrue(Files.isRegularFile(source) && !Files.isSymbolicLink(source), "Regular non-link input required: " + source);
        String expected = sha256(source);
        Files.createDirectories(destination.getParent()); Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
        assertEquals(expected, sha256(destination), "Input changed while freezing: " + source);
        hashes.put(frozen.relativize(destination).toString().replace('\\', '/'), expected);
    }

    /** Проверяет неизменность каждого файла classpath и каждой JS-фикстуры. */
    private static void verifyInputs(Path frozen, Map<String, String> hashes) throws Exception {
        for (var entry : hashes.entrySet()) assertEquals(entry.getValue(), sha256(frozen.resolve(entry.getKey())), "Frozen input changed: " + entry.getKey());
    }

    /** Вычисляет SHA-256 без загрузки большого файла целиком в память. */
    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Возвращает сохранённую диагностическую ошибку без повторного запуска процесса. */
    private static String readLog(Path path) {
        try { return Files.readString(path); } catch (Exception error) { return "Log unavailable"; }
    }
}
