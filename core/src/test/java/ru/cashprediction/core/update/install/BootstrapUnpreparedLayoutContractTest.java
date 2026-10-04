package ru.cashprediction.core.update.install;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.update.tree.TreeDeltaEngine;

/** Контракты облегчённого test-only setup; синтетические файлы не являются native доказательством. */
class BootstrapUnpreparedLayoutContractTest {
    @TempDir Path temporary;

    /** Оба старых конструктора по-прежнему публикуют настоящий journal и helper. */
    @Test void existingConstructorsRemainPrepared() throws Exception {
        List<BootstrapFixture> fixtures = List.of(new BootstrapFixture(temporary.resolve("legacy")),
                new BootstrapFixture(temporary.resolve("external"), true));
        for (BootstrapFixture f : fixtures) {
            assertFalse(f.journal.isEmpty());
            assertEquals("PENDING", InstallJournal.read(f.root, f.updates).get("outcome"));
            assertEquals(PowerShellHelper.script(), Files.readString(f.updates.resolve("apply-update.ps1")));
            TreeDeltaEngine.verify(f.root, f.old, f.oldHash);
            f.userData();
        }
    }

    /** Физические bytes/инвентари обеих форм не меняются, исключается лишь устаревшая подготовка. */
    @Test void unpreparedLayoutMatchesPreparedPayloadAndUserBytes() throws Exception {
        for (boolean external : List.of(false, true)) {
            BootstrapFixture prepared = new BootstrapFixture(temporary.resolve("prepared-" + external), external);
            BootstrapFixture bare = BootstrapFixture.unpreparedLayout(temporary.resolve("bare-" + external), external);
            assertEquals(prepared.old, bare.old);
            assertEquals(prepared.oldHash, bare.oldHash);
            assertEquals(prepared.target, bare.target);
            assertEquals(prepared.userSnapshot, bare.userSnapshot);
            for (var file : prepared.old) {
                Path a = prepared.root.resolve(file.path()), b = bare.root.resolve(file.path());
                assertArrayEquals(Files.readAllBytes(a), Files.readAllBytes(b), file.path());
                assertFalse(Files.isSameFile(a, b), file.path());
            }
            assertTrue(bare.journal.isEmpty());
            for (String name : List.of("install-journal.json", "apply-update.ps1", "Bootstrap", "Backup", "requests")) {
                assertFalse(Files.exists(bare.updates.resolve(name), LinkOption.NOFOLLOW_LINKS), name);
            }
            assertTrue(Files.isRegularFile(bare.updates.resolve("Ready/update.json"), LinkOption.NOFOLLOW_LINKS));
            TreeDeltaEngine.verify(bare.root, bare.old, bare.oldHash);
            TreeDeltaEngine.verify(bare.ready, bare.target.files(), bare.target.treeSha256());
            bare.userData();
        }
    }

    /** Реальный producer принимает layout, сохраняя путь и UUID нового журнала, без cached checks. */
    @Test void unpreparedLayoutStillSupportsActualPreparation() throws Exception {
        BootstrapFixture f = BootstrapFixture.unpreparedLayout(temporary.resolve("prepare"), true);
        var journal = InstallJournal.preparePortable(f.root, f.target);
        assertEquals(f.root.toString(), journal.get("installationRoot"));
        assertEquals("PENDING", journal.get("outcome"));
        assertNotNull(journal.get("transactionId"));
        InstallJournal.write(f.updates, journal);
        // JSON wire числа одинаковы, хотя producer использует Integer, а reader - Long.
        assertEquals(ru.cashprediction.core.json.JsonWriter.write(journal),
                ru.cashprediction.core.json.JsonWriter.write(InstallJournal.read(f.root, f.updates)));
        f.userData();
    }

    /** Облегчённая fixture явно запрещает случайный запуск отсутствующего helper. */
    @Test void unpreparedLayoutCannotExecuteHelperAndIsIsolated() throws Exception {
        BootstrapFixture a = BootstrapFixture.unpreparedLayout(temporary.resolve("a"), true);
        BootstrapFixture b = BootstrapFixture.unpreparedLayout(temporary.resolve("b"), true);
        var failure = assertThrows(IllegalStateException.class, () -> a.run(0, false, "", 0));
        assertEquals("UNPREPARED_LAYOUT_CANNOT_RUN_HELPER", failure.getMessage());
        Files.writeString(a.root.resolve("app/old-resource.txt"), "mutated-first-only");
        assertEquals("old-resource", Files.readString(b.root.resolve("app/old-resource.txt")));
        TreeDeltaEngine.verify(b.root, b.old, b.oldHash);
        b.userData();
    }

    /** Новый setup проходит исходный malicious assert со всеми bytes/readonly/user guards. */
    @Test void maliciousChecksRetainExactReasonsAndFullSnapshots() throws Exception {
        var assertion = ExternalModuleLayoutSecurityTest.class.getDeclaredMethod(
                "assertReprepareRefused", BootstrapFixture.class, String.class);
        assertion.setAccessible(true);
        int index = 0;
        for (boolean targetSide : List.of(false, true)) {
            for (String fault : List.of("cfg", "missing", "duplicate", "empty")) {
                BootstrapFixture f = BootstrapFixture.unpreparedLayout(temporary.resolve("malicious-" + index++), true);
                Path side = targetSide ? f.ready : f.root;
                if (fault.equals("cfg")) {
                    Path cfg = side.resolve("app/CashPrediction.cfg");
                    Files.writeString(cfg, Files.readString(cfg).replace(
                            "java-options=--module-path\r\njava-options=$APPDIR\r\n",
                            "java-options=--module-path=C:\\foreign\\modules\r\n"));
                } else {
                    Path jar = side.resolve("app/cashprediction-core-1.0.0.jar");
                    switch (fault) {
                        case "missing" -> Files.delete(jar);
                        case "duplicate" -> Files.copy(jar, side.resolve("app/cashprediction-core-2.0.0.jar"));
                        case "empty" -> Files.write(jar, new byte[0]);
                        default -> throw new AssertionError("UNKNOWN_TEST_FAULT");
                    }
                }
                Path protectedFile = f.root.resolve("app/old-resource.txt");
                var dos = Files.getFileAttributeView(protectedFile,
                        java.nio.file.attribute.DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (dos != null) dos.setReadOnly(true);
                try {
                    assertion.invoke(null, f, fault.equals("cfg")
                            ? "BOOTSTRAP_CFG_MODULE_PATH" : "BOOTSTRAP_MODULE_LAYOUT");
                } finally {
                    if (dos != null) dos.setReadOnly(false);
                }
            }
        }
        assertEquals(8, index);
    }
}
