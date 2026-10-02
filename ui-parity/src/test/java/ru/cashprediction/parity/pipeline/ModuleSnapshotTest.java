package ru.cashprediction.parity.pipeline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.parity.launch.ClientTarget;
import static org.junit.jupiter.api.Assertions.*;

/** Снимок модулей не зависит от последующих сборок и не меняет имена автоматических модулей. */
class ModuleSnapshotTest {
    @TempDir Path root;

    /** Перегенерация эталонов между сценариями не меняет сравнения текущей матрицы. */
    @Test void goldenTreeAndAllowancesAreFrozenTogether() throws Exception {
        Path source = root.resolve("goldens");
        Files.createDirectories(source.resolve("scenario"));
        Files.writeString(source.resolve("allowed-diffs.json"), "original-allowances");
        Files.writeString(source.resolve("scenario/main.json"), "original-dump");
        Path frozen = ModuleSnapshot.captureTree(source, root.resolve("matrix/goldens"));
        Files.writeString(source.resolve("allowed-diffs.json"), "new-allowances");
        Files.writeString(source.resolve("scenario/main.json"), "new-dump");
        assertEquals("original-allowances", Files.readString(frozen.resolve("allowed-diffs.json")));
        assertEquals("original-dump", Files.readString(frozen.resolve("scenario/main.json")));
        assertThrows(java.io.IOException.class,
                () -> ModuleSnapshot.captureTree(root.resolve("missing"), root.resolve("unused")));
        assertThrows(java.io.IOException.class, () -> ModuleSnapshot.captureTree(source, source));
        assertThrows(java.io.IOException.class, () -> ModuleSnapshot.captureTree(source, source.resolve("nested")));
        assertFalse(Files.exists(source.resolve("nested")));
    }

    /** Сценарии и клиенты используют одну версию ядра; пересборка исходников не меняет снимок матрицы. */
    @Test void wholeMatrixSharesOneFrozenCoreAndKeepsClientVersions() throws Exception {
        Path project = root.resolve("project"), core = project.resolve("core/target/core.jar");
        Path fx = project.resolve("ui-fx/target/fx.jar"), swing = project.resolve("ui-swing/target/swing.jar");
        for (Path path : List.of(core, fx, swing)) { Files.createDirectories(path.getParent()); Files.writeString(path, "before-" + path.getFileName()); }
        var targets = new java.util.LinkedHashMap<String, ClientTarget>();
        targets.put("fx", new ClientTarget("fx", List.of(fx, core), "fx", "Main", List.of(), List.of(), List.of()));
        targets.put("swing", new ClientTarget("swing", List.of(swing, core), "swing", "Main", List.of(), List.of(), List.of()));
        Map<String, ClientTarget> frozen = ModuleSnapshot.captureAll(targets, root.resolve("matrix"), project);
        assertEquals(frozen.get("fx").modulePath().get(1), frozen.get("swing").modulePath().get(1));
        for (Path path : List.of(core, fx, swing)) Files.writeString(path, "after");
        assertEquals("before-core.jar", Files.readString(frozen.get("fx").modulePath().get(1)));
        assertEquals("before-fx.jar", Files.readString(frozen.get("fx").modulePath().getFirst()));
        assertEquals("before-swing.jar", Files.readString(frozen.get("swing").modulePath().getFirst()));
        assertThrows(UnsupportedOperationException.class, () -> frozen.clear());
    }

    /** Одинаковые имена не сталкиваются, внешний Maven-модуль не копируется, опции сохраняются. */
    @Test void mutableJarsAreIsolatedWithoutChangingModuleNames() throws Exception {
        Path project = root.resolve("project");
        Path one = project.resolve("core/target/module.jar"), two = project.resolve("client/target/module.jar");
        Path dependency = root.resolve("maven/dependency.jar");
        for (Path file : List.of(one, two, dependency)) { Files.createDirectories(file.getParent()); Files.writeString(file, file.toString()); }
        var target = new ClientTarget("fx", List.of(one, two, dependency), "core", "Main", List.of("javafx.controls"),
                List.of("-Dprobe=true"), List.of("--ui", "core"));
        var snapshot = ModuleSnapshot.capture(target, root.resolve("snapshot"), project);
        assertEquals(dependency, snapshot.modulePath().get(2));
        assertNotEquals(snapshot.modulePath().get(0), snapshot.modulePath().get(1));
        for (int i = 0; i < 2; i++) {
            assertEquals("module.jar", snapshot.modulePath().get(i).getFileName().toString());
            assertEquals(target.modulePath().get(i).toString(), Files.readString(snapshot.modulePath().get(i)));
        }
        Files.writeString(one, "rebuilt");
        assertEquals(one.toString(), Files.readString(snapshot.modulePath().getFirst()));
        assertEquals(target.jvmOptions(), snapshot.jvmOptions());
        assertEquals(target.arguments(), snapshot.arguments());
        assertEquals(target.addModules(), snapshot.addModules());
    }

    /** Каталог классов, используемый фиктивным модульным клиентом, также получает независимую копию. */
    @Test void compiledDirectoryIsCopiedRecursively() throws Exception {
        Path project = root.resolve("project"), classes = project.resolve("core/target/classes");
        Files.createDirectories(classes.resolve("package")); Files.writeString(classes.resolve("package/Main.class"), "class-bytes");
        var snapshot = ModuleSnapshot.capture(new ClientTarget("dummy", List.of(classes), "core", "Main", List.of(), List.of(), List.of()),
                root.resolve("snapshot"), project);
        Files.writeString(classes.resolve("package/Main.class"), "new-bytes");
        assertEquals("class-bytes", Files.readString(snapshot.modulePath().getFirst().resolve("package/Main.class")));
    }
}
