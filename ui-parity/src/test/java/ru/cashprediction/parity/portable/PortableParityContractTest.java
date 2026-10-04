package ru.cashprediction.parity.portable;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.selftest.SelfTestScript;
import static org.junit.jupiter.api.Assertions.*;

/** Чистые проверки строгого журнала и границ пути; процессы, браузер и реестр не запускаются. */
class PortableParityContractTest {
    /** Период одного клиента не становится исходным периодом другого; остальные данные не меняются. */
    @Test
    void eachClientStartsWithSameSettings(@org.junit.jupiter.api.io.TempDir Path copy) throws Exception {
        Path memory = java.nio.file.Files.createDirectory(copy.resolve("CashMemory"));
        Path settings = memory.resolve("settings.md");
        Path plan = memory.resolve("plan.md");
        java.nio.file.Files.writeString(plan, "owned plan");
        byte[] baseline = "baseline".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (String client : java.util.List.of("fx", "swing", "web")) {
            java.nio.file.Files.writeString(settings, "changed by " + client);
            PortableParityHarness.restoreBaselineSettings(copy, baseline);
            assertArrayEquals(baseline, java.nio.file.Files.readAllBytes(settings));
            assertEquals("owned plan", java.nio.file.Files.readString(plan));
        }
        PortableParityHarness.restoreBaselineSettings(copy, null);
        assertFalse(java.nio.file.Files.exists(settings));
        java.nio.file.Files.createDirectory(settings);
        assertThrows(IllegalStateException.class, () -> PortableParityHarness.restoreBaselineSettings(copy, baseline));
    }

    /** Аргументы EXE соблюдают непустой module-path контракт без подмены EXE запуском Java. */
    @Test
    void packagedTargetUsesOwnAppModules() {
        Path copy = Path.of("portable-copy").toAbsolutePath();
        for (String client : java.util.List.of("fx", "swing", "web")) {
            var target = PortableExeProcess.argumentTarget(copy, client);
            assertEquals(java.util.List.of(copy.resolve("app")), target.modulePath());
            assertEquals(client, target.client());
            assertEquals(client.equals("web")
                    ? java.util.List.of("--no-browser", "--no-window", "--test-api") : java.util.List.of(), target.arguments());
        }
    }

    /** Метка Unicode сама по себе не доказывает Unicode-путь. */
    @Test
    void pathClassRequiresActualCharacters() {
        assertDoesNotThrow(() -> PortableParityHarness.requirePathClass(Path.of("plain", "CashPrediction"), "ascii"));
        assertDoesNotThrow(() -> PortableParityHarness.requirePathClass(Path.of("\u041c\u043e\u0438 \u043f\u0440\u043e\u0433\u0440\u0430\u043c\u043c\u044b", "CashPrediction"), "cyrillic"));
        assertDoesNotThrow(() -> PortableParityHarness.requirePathClass(Path.of("\u0394 \u6d4b\u8bd5", "CashPrediction"), "unicode"));
        assertThrows(IllegalStateException.class, () -> PortableParityHarness.requirePathClass(Path.of("plain", "CashPrediction"), "unicode"));
        assertThrows(IllegalStateException.class, () -> PortableParityHarness.requirePathClass(Path.of("\u0394 \u6d4b\u8bd5", "CashPrediction"), "ascii"));
    }
    /** DONE не скрывает пропущенные, переставленные, ошибочные или дополнительные команды. */
    @Test
    void completeJournalIsMandatory() {
        var script = SelfTestScript.parse("fixture", "sample\ndump table\n");
        String good = "SELFTEST 1 OK sample\nSELFTEST 2 OK dump table\nSELFTEST DONE\n";
        assertDoesNotThrow(() -> PortableParityHarness.validateLog(script, good, true));
        for (String bad : new String[]{"SELFTEST DONE\n", good.replace(" OK ", " FAIL "),
                good.replace("SELFTEST 1 OK sample\n", ""), good + "SELFTEST DONE\n",
                good.replace("SELFTEST 2", "SELFTEST 3"), good.replace("SELFTEST DONE\n", "")})
            assertThrows(IllegalStateException.class, () -> PortableParityHarness.validateLog(script, bad, true));
    }

    /** Барьер разрешает только завершённый префикс перед последней signal-командой. */
    @Test
    void barrierRequiresEveryPreviousCommand() {
        var script = SelfTestScript.parse("fixture", "dump observed\nsignal committed\n");
        assertDoesNotThrow(() -> PortableParityHarness.validateLog(script, "SELFTEST 1 OK dump observed\n", false));
        assertThrows(IllegalStateException.class, () -> PortableParityHarness.validateLog(script, "", false));
        assertThrows(IllegalStateException.class, () -> PortableParityHarness.validateLog(script,
                "SELFTEST 1 OK dump observed\nSELFTEST DONE\n", false));
    }

    /** Похожий текстовый префикс не означает принадлежность процессной копии. */
    @Test
    void siblingPrefixIsNotOwned() {
        Path copy = Path.of("portable-copy").toAbsolutePath();
        assertDoesNotThrow(() -> PortableExeProcess.requireOwnedCopy(copy, copy.resolve("CashPrediction.exe")));
        assertThrows(IllegalStateException.class, () -> PortableExeProcess.requireOwnedCopy(copy,
                copy.resolveSibling("portable-copy-other").resolve("CashPrediction.exe")));
        assertThrows(IllegalStateException.class, () -> PortableExeProcess.requireOwnedCopy(copy, copy));
    }
}
