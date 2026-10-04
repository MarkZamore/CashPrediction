package ru.cashprediction.core.update.install;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.update.model.UpdateCodec;
import ru.cashprediction.core.update.model.UpdateManifest;
import ru.cashprediction.core.update.tree.TreeDeltaEngine;

/**
 * Проверяет отказ external-module bootstrap до записи или переноса файлов.
 * Использует существующую синтетическую BootstrapFixture, только файловые API JDK:
 * помощник не запускается, настоящие JAR/exe и native lifecycle не имитируются.
 */
class ExternalModuleLayoutSecurityTest {
    private static final List<String> ROLES = List.of("core", "ui-fx", "ui-swing", "web");
    private static final String MODULE_PATH = "java-options=--module-path\r\njava-options=$APPDIR\r\n";

    @TempDir Path temporary;

    /** Корректные три cfg и четыре роли проходят повторную подготовку без durable изменений. */
    @Test void validExternalLayoutRepreparesWithoutMutation() throws Exception {
        BootstrapFixture f = new BootstrapFixture(temporary.resolve("valid"), true);
        Snapshot before = snapshot(f.root);
        Map<String, Object> plan = InstallJournal.preparePortable(f.root, f.target);
        assertEquals(2, plan.get("schemaVersion"));
        assertEquals(4, f.old.stream().filter(file -> file.path().startsWith("app/")
                && PortableBootstrap.protectedPayload(file.path())).count());
        Map<String, Object> bootstrap = Json.asObject(plan.get("bootstrap"), "bootstrap");
        for (Object value : (List<?>) bootstrap.get("cfgTexts")) {
            String text = (String) Json.asObject(value, "cfg").get("text");
            assertTrue(text.contains("java-options=--module-path\r\njava-options=" + PortableBootstrap.MODULES + "\r\n"));
            assertFalse(text.contains("java-options=$APPDIR"));
        }
        InstallJournal.read(f.root, f.updates);
        assertUnchanged(f, before);
    }

    /** Inline module-path и короткий -p не позволяют подставить внешний код в old или target cfg. */
    @Test void injectedModulePathsAreRejectedBeforeMutation() throws Exception {
        rejectConfigurations("injection", List.of(
                "java-options=--module-path=C:\\foreign\\modules\r\n",
                "java-options=-pC:\\foreign\\modules\r\n",
                "java-options=-p\r\njava-options=$APPDIR\r\n",
                "java-options=--module-path\r\njava-options=$APPDIR;C:\\foreign\\modules\r\n",
                "java-options=--module-path\r\njava-options=$ROOTDIR\r\n"));
    }

    /** Повторная пара --module-path или одиночный APPDIR отвергаются, а не выбираются произвольно. */
    @Test void duplicateModulePathArgumentsAreRejectedBeforeMutation() throws Exception {
        rejectConfigurations("duplicate-path", List.of(MODULE_PATH + MODULE_PATH,
                MODULE_PATH + "java-options=$APPDIR\r\n"));
    }

    /** Аргумент должен быть следующей строкой: конец файла, пустая строка и JVM flag недопустимы. */
    @Test void missingAdjacentModulePathArgumentIsRejectedBeforeMutation() throws Exception {
        rejectConfigurations("missing-adjacent", List.of("java-options=--module-path",
                "java-options=--module-path\r\n\r\njava-options=$APPDIR\r\n",
                "java-options=--module-path\r\njava-options=-Xmx512m\r\njava-options=$APPDIR\r\n"));
    }

    /** Разрыв пары заголовком секции запрещён, включая повторный JavaOptions. */
    @Test void modulePathSplitAcrossSectionsIsRejectedBeforeMutation() throws Exception {
        rejectConfigurations("split-section", List.of(
                "java-options=--module-path\r\n[ArgOptions]\r\njava-options=$APPDIR\r\n",
                "java-options=--module-path\r\n[JavaOptions]\r\njava-options=$APPDIR\r\n"));
    }

    /** Отсутствие любой из четырёх ролей в old или target не публикует bootstrap/journal. */
    @Test void missingOldAndTargetRolesAreRejectedBeforeMutation() throws Exception {
        rejectRoleLayouts("missing");
    }

    /** Две версии каждой роли запрещены независимо от остальных трёх корректных модулей. */
    @Test void duplicateOldAndTargetRolesAreRejectedBeforeMutation() throws Exception {
        rejectRoleLayouts("duplicate");
    }

    /** Нулевой JAR не считается присутствующим модулем в old или target. */
    @Test void emptyOldAndTargetRolesAreRejectedBeforeMutation() throws Exception {
        rejectRoleLayouts("empty");
    }

    /** BOOT_COPY настоящих четырёх ролей допустим в обоих durable состояниях, без исполнения. */
    @Test void legitimateRoleBootCopyOperationsRemainReadableWithoutMutation() throws Exception {
        for (String role : ROLES) {
            for (String state : List.of("BEFORE", "AFTER")) {
                BootstrapFixture f = new BootstrapFixture(temporary.resolve("copy-" + role + "-" + state), true);
                f.journal.put("operations", List.of(Map.of("kind", "BOOT_COPY", "path", rolePath(role), "state", state)));
                InstallJournal.write(f.updates, f.journal);
                Snapshot before = snapshot(f.root);
                assertEquals(1, ((List<?>) InstallJournal.read(f.root, f.updates).get("operations")).size());
                assertUnchanged(f, before);
            }
        }
    }

    /** Даже присутствующий в oldFiles чужой JAR не может стать protected BOOT_COPY. */
    @Test void forgedForeignJarBootCopyIsRejectedWithoutMutation() throws Exception {
        List<String> foreign = List.of("app/foreign.jar", "app/cashprediction-tools-1.0.0.jar",
                "app/lib/cashprediction-core-1.0.0.jar");
        int index = 0;
        for (String path : foreign) {
            for (String state : List.of("BEFORE", "AFTER")) {
                BootstrapFixture f = new BootstrapFixture(temporary.resolve("foreign-" + index++), true);
                BootstrapFixture.put(f.root, path, "foreign-managed-resource");
                Map<String, Object> journal = InstallJournal.preparePortable(f.root, f.target);
                InstallJournal.write(f.updates, journal);
                // Положительный control: посторонний файл сам по себе не портит журнал.
                InstallJournal.read(f.root, f.updates);
                assertFalse(PortableBootstrap.protectedPayload(path));
                assertTrue(TreeDeltaEngine.inventory(f.root).stream().anyMatch(file -> file.path().equals(path)));
                journal.put("operations", List.of(Map.of("kind", "BOOT_COPY", "path", path, "state", state)));
                InstallJournal.write(f.updates, journal);
                Snapshot before = snapshot(f.root);
                IOException failure = assertThrows(IOException.class, () -> InstallJournal.read(f.root, f.updates));
                assertEquals("JOURNAL_INVALID", failure.getMessage());
                assertUnchanged(f, before);
            }
        }
    }

    /** Все варианты cfg проверяются для каждого launcher и обеих сторон с актуальными target hashes. */
    private void rejectConfigurations(String family, List<String> replacements) throws Exception {
        int index = 0;
        for (boolean targetSide : List.of(false, true)) {
            for (String cfg : PortableBootstrap.CONFIGS) {
                for (String replacement : replacements) {
                    BootstrapFixture f = new BootstrapFixture(temporary.resolve(family + "-" + index++), true);
                    Files.delete(f.updates.resolve("install-journal.json"));
                    Path path = (targetSide ? f.ready : f.root).resolve(cfg);
                    String original = Files.readString(path);
                    assertTrue(original.contains(MODULE_PATH));
                    Files.writeString(path, original.replace(MODULE_PATH, replacement));
                    assertReprepareRefused(f, "BOOTSTRAP_CFG_MODULE_PATH");
                }
            }
        }
    }

    /** Изменяет реальные fixture файлы, а не только переданный список FileEntry. */
    private void rejectRoleLayouts(String fault) throws Exception {
        int index = 0;
        for (boolean targetSide : List.of(false, true)) {
            for (String role : ROLES) {
                BootstrapFixture f = new BootstrapFixture(temporary.resolve(fault + "-role-" + index++), true);
                Files.delete(f.updates.resolve("install-journal.json"));
                Path side = targetSide ? f.ready : f.root;
                Path jar = side.resolve(rolePath(role));
                switch (fault) {
                    case "missing" -> Files.delete(jar);
                    case "duplicate" -> Files.copy(jar, side.resolve("app/cashprediction-" + role + "-2.0.0.jar"));
                    case "empty" -> Files.write(jar, new byte[0]);
                    default -> throw new AssertionError("UNKNOWN_LAYOUT_FAULT");
                }
                assertReprepareRefused(f, "BOOTSTRAP_MODULE_LAYOUT");
            }
        }
    }

    /** Пересчитывает target inventory после fault, исключая отказ из-за устаревшего digest. */
    private static UpdateManifest currentTarget(BootstrapFixture f) throws IOException {
        var files = TreeDeltaEngine.inventory(f.ready);
        UpdateManifest old = f.target;
        return new UpdateManifest(old.releaseNumber(), old.commitSha(), old.version(), old.publishedAtUtc(),
                old.assetName(), old.sizeBytes(), old.sha256(), TreeDeltaEngine.treeHash(files), files, old.deltaPatches());
    }

    /** Проверяет точную причину отказа и полное отсутствие побочных файлов/изменений. */
    private static void assertReprepareRefused(BootstrapFixture f, String code) throws Exception {
        UpdateManifest target = currentTarget(f);
        // Durable Ready соответствует fault дереву ещё до снимка проверяемой операции.
        InstallFiles.write(f.updates.resolve("Ready/update.json"), UpdateCodec.write(target));
        Snapshot before = snapshot(f.root);
        IOException failure = assertThrows(IOException.class, () -> InstallJournal.preparePortable(f.root, target));
        assertEquals(code, failure.getMessage());
        assertFalse(Files.exists(f.updates.resolve("install-journal.json"), LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(f.updates.resolve("Bootstrap"), LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(f.updates.resolve("Backup"), LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(f.updates.resolve("requests"), LinkOption.NOFOLLOW_LINKS));
        assertUnchanged(f, before);
    }

    /** Точный корневой JAR одной роли существующей fixture. */
    private static String rolePath(String role) {
        return "app/cashprediction-" + role + "-1.0.0.jar";
    }

    /** Снимок включает все файлы, каталоги и readonly, в том числе весь CashMemory/Updates. */
    private static Snapshot snapshot(Path root) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        Map<String, Boolean> readOnly = new LinkedHashMap<>();
        Set<String> directories = new TreeSet<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                String relative = root.relativize(path).toString();
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) directories.add(relative);
                else {
                    files.put(relative, Files.readAllBytes(path));
                    DosFileAttributeView dos = Files.getFileAttributeView(path, DosFileAttributeView.class,
                            LinkOption.NOFOLLOW_LINKS);
                    readOnly.put(relative, dos == null ? !Files.isWritable(path) : dos.readAttributes().isReadOnly());
                }
            }
        }
        return new Snapshot(files, readOnly, directories);
    }

    /** Сравнение массива каждого файла не полагается на Map.equals для byte[]. */
    private static void assertUnchanged(BootstrapFixture f, Snapshot before) throws IOException {
        Snapshot after = snapshot(f.root);
        assertEquals(before.files().keySet(), after.files().keySet());
        assertEquals(before.directories(), after.directories());
        assertEquals(before.readOnly(), after.readOnly());
        for (String path : before.files().keySet()) assertArrayEquals(before.files().get(path), after.files().get(path), path);
        f.userData();
    }

    /** Полный снимок fixture до вызова проверяемого отказа. */
    private record Snapshot(Map<String, byte[]> files, Map<String, Boolean> readOnly, Set<String> directories) { }
}
