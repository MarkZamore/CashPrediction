package ru.cashprediction.parity.check.census;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.parity.launch.ClientTarget;
import static org.junit.jupiter.api.Assertions.*;

/** Статические регрессии единого снимка переписи, без запуска клиентов и без вымышленных счётчиков. */
class FrozenCensusTargetRegressionTest {
    @TempDir Path directory;

    /** Замена каждого исходного артефакта не меняет общий снимок FX, ядра и четырёх модулей OpenJFX. */
    @Test void replacingAllLiveArtifactsLeavesEntireFrozenModulePathUnchanged() throws Exception {
        List<Path> jars = new ArrayList<>();
        for (String name : List.of("fx", "core", "javafx-base", "javafx-graphics", "javafx-controls", "javafx-swing")) {
            Path jar = directory.resolve(name + ".jar");
            writeJar(jar, "before");
            jars.add(jar);
        }
        var live = new ClientTarget("fx", jars, ClientTarget.FX_MODULE, ClientTarget.FX_MAIN,
                List.of(), List.of(), List.of());
        var frozen = ClientJarSnapshot.copy(live, directory.resolve("frozen"));
        List<byte[]> original = new ArrayList<>();
        for (Path jar : frozen.modulePath()) original.add(Files.readAllBytes(jar));
        for (Path jar : live.modulePath()) writeJar(jar, "after replacement");
        for (int i = 0; i < jars.size(); i++) {
            assertArrayEquals(original.get(i), Files.readAllBytes(frozen.modulePath().get(i)));
            assertFalse(java.util.Arrays.equals(original.get(i), Files.readAllBytes(jars.get(i))));
        }
    }

    /** Дополнительная проба должна принимать готовую цель, а не только живую раскладку реактора. */
    @Test void directoryProbeAcceptsFrozenTarget() throws Exception {
        assertNotNull(DirectoryChooserProbe.class.getMethod("collect", ClientTarget.class, Path.class, Duration.class));
    }

    /** Два сборщика и каталог допустимых классов должны читать одну замороженную цель. */
    @Test void wholeCensusUsesOneTargetIncludingToolkitCatalog() throws Exception {
        String source = source("check/ClassUsageTest.java");
        assertTrue(source.contains("DirectoryChooserProbe.collect(target, output,"),
                "Directory probe must receive the same target returned to ScenarioCollector");
        assertTrue(source.contains("return target;"));
        assertTrue(source.contains("FxCensus.toolkitClasses(target.modulePath())"),
                "Toolkit catalog must not reread live JavaFX jars");
        assertTrue(source.contains("requiredScenarios.add(DirectoryChooserProbe.SCENARIO)"));
        assertTrue(source.contains("evidence.requireComplete(requiredScenarios,"));
    }

    /** Перегрузка готового снимка не должна заново разрешать или копировать живые jar. */
    @Test void frozenOverloadLaunchesPassedTargetWithoutLiveLookup() throws Exception {
        String source = source("check/census/DirectoryChooserProbe.java");
        int start = source.indexOf("collect(ClientTarget target,");
        assertTrue(start >= 0, "Frozen overload is missing");
        int end = source.indexOf("public static void validateLog", start);
        assertTrue(end > start);
        String body = source.substring(start, end);
        assertTrue(body.contains("ClientLauncher.launch(target, request)"));
        assertFalse(body.contains("ClientTarget.fx("));
        assertFalse(body.contains("ClientJarSnapshot.copy("));
        assertFalse(body.contains("ReactorLayout.fromSystemProperties("));
    }

    /** Читает только исходник ограниченного контракта из явно заданного корня, не общие target. */
    private static String source(String relative) throws Exception {
        Path root = Path.of(System.getProperty("parity.reactor.root", ".."));
        return Files.readString(root.resolve("ui-parity/src/test/java/ru/cashprediction/parity").resolve(relative));
    }

    /** Создаёт минимальный jar исключительно для проверки неизменности байтов, не для переписи виджетов. */
    private static void writeJar(Path path, String content) throws Exception {
        try (var output = new JarOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new JarEntry("payload"));
            output.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }
}
