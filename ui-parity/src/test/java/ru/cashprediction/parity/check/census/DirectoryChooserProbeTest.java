package ru.cashprediction.parity.check.census;

import java.util.*;
import java.nio.file.Files;
import java.time.Duration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.command.CommandId;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.selftest.SelfTestCommand;
import ru.cashprediction.core.ui.selftest.SelfTestScript;
import ru.cashprediction.parity.launch.ReactorLayout;
import ru.cashprediction.parity.pipeline.ParityPipeline;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет строгий журнал и доказательства дополнительного сценария на явно тестовых фикстурах. */
class DirectoryChooserProbeTest {
    /** Отдельный ограниченный реальный прогон позволяет проверить выбор папки без всей матрицы 23 классов. */
    @Test void realDirectoryChooserInstantiation() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("parity.realClients"), "Enable -Dparity.realClients=true");
        Assumptions.assumeTrue(ParityPipeline.clients(System.getProperty("parity.clients", "fx,swing,web")).contains("fx"),
                "Class census applies to fx only");
        var layout = ReactorLayout.fromSystemProperties();
        Files.createDirectories(layout.parityRoot());
        var output = Files.createTempDirectory(layout.parityRoot(), "directory-census-");
        var dumps = DirectoryChooserProbe.collect(layout, output, Duration.ofSeconds(45));
        var census = new FxCensus(FxCensus.toolkitClasses(layout.javafxJars()));
        census.addScenario(DirectoryChooserProbe.SCENARIO, dumps);
        Files.writeString(output.resolve("class-census.txt"), census.report());
        assertTrue(census.report().contains("javafx.stage.DirectoryChooser: " + DirectoryChooserProbe.SCENARIO + "/"));
    }

    /** Подтверждает настоящий id команды, отмену выбора и оба идентифицированных шага дампа. */
    @Test void scriptUsesActualFolderCommandAndCancellation() {
        var script = script();
        var menu = script.lines().stream().map(SelfTestScript.Line::command)
                .filter(c -> c instanceof SelfTestCommand.Menu).map(c -> (SelfTestCommand.Menu) c).findFirst().orElseThrow();
        assertEquals("file.cashMemory", menu.idOrPath());
        assertEquals(CommandId.FILE_CASH_MEMORY, CommandId.byId(menu.idOrPath()).orElseThrow());
        assertTrue(script.lines().stream().anyMatch(l -> l.command() instanceof SelfTestCommand.Chooser c && c.path() == null));
        assertEquals(List.of("directory-pending", "directory-cancelled"), script.lines().stream()
                .filter(l -> l.command() instanceof SelfTestCommand.Dump)
                .map(l -> ((SelfTestCommand.Dump) l.command()).step()).toList());
    }

    /** Принимает полный журнал и отвергает FAIL, пропуск, дубль, перестановку, чужой текст и лишнюю строку. */
    @Test void doneCannotHideFailedOrMissingSteps() {
        var script = script();
        String valid = script.lines().stream().map(l -> "SELFTEST " + l.number() + " OK " + l.text() + "\n")
                .reduce("", String::concat) + "SELFTEST DONE\n";
        DirectoryChooserProbe.validateLog(script, valid);
        var lines = new ArrayList<>(valid.lines().toList());
        List<String> corrupt = new ArrayList<>();
        corrupt.add(valid.replace(" OK chooser cancel", " FAIL chooser cancel: chooser absent"));
        corrupt.add(String.join("\n", lines.subList(1, lines.size())));
        corrupt.add(lines.getFirst() + "\n" + valid);
        Collections.swap(lines, 1, 2); corrupt.add(String.join("\n", lines));
        corrupt.add(valid.replace("menu file.cashMemory", "menu recovery.chooseFolder"));
        corrupt.add(valid + "extra\n"); corrupt.add(valid.replace("SELFTEST DONE", ""));
        for (String contents : corrupt) assertThrows(IllegalStateException.class,
                () -> DirectoryChooserProbe.validateLog(script, contents));
    }

    /** Требует оба реальных запроса и положительные экземпляры с правильным происхождением. */
    @Test void directoryEvidenceRejectsMissingZeroNegativeAndInjectedMetadata() {
        var valid = dumps("fx", 1, "directory", 1);
        DirectoryChooserProbe.verifyDirectoryEvidence(valid);
        for (int count : List.of(0, -1)) assertThrows(AssertionError.class,
                () -> DirectoryChooserProbe.verifyDirectoryEvidence(dumps("fx", count, "directory", 1)));
        for (String client : List.of("model", "swing", "web")) assertThrows(AssertionError.class,
                () -> DirectoryChooserProbe.verifyDirectoryEvidence(dumps(client, 1, "directory", 1)));
        assertThrows(AssertionError.class, () -> DirectoryChooserProbe.verifyDirectoryEvidence(dumps("fx", 1, "file", 1)));
        for (int fires : List.of(0, 2)) assertThrows(AssertionError.class,
                () -> DirectoryChooserProbe.verifyDirectoryEvidence(dumps("fx", 1, "directory", fires)));
        assertThrows(AssertionError.class, () -> DirectoryChooserProbe.verifyDirectoryEvidence(Map.of()));
    }

    /** Отклоняет чужую версию, сценарий и шаг даже при наличии положительного счётчика выбора папки. */
    @Test void directoryIdentityCannotBeRelabelled() {
        var valid = dumps("fx", 1, "directory", 1);
        UiDump original = valid.get("directory-pending");
        for (int mutation = 0; mutation < 3; mutation++) {
            var changed = new UiDump(mutation == 0 ? 2 : UiDump.SCHEMA, "fx",
                    mutation == 1 ? "old-scenario" : DirectoryChooserProbe.SCENARIO,
                    mutation == 2 ? "other-step" : original.step(), null, null, null, null,
                    null, null, null, null, null, null, null, null, original.chooserRequests(),
                    original.classCensus(), original.counters());
            var actual = new LinkedHashMap<>(valid); actual.put("directory-pending", changed);
            assertThrows(AssertionError.class, () -> DirectoryChooserProbe.verifyDirectoryEvidence(actual));
        }
    }

    /** Разбирает генерируемый сценарий тем же парсером, что использует настоящий клиент. */
    private static SelfTestScript script() { return SelfTestScript.parse(DirectoryChooserProbe.SCENARIO, DirectoryChooserProbe.scriptText()); }

    /** Создаёт только тестовые данные валидатора; настоящий запуск никогда не получает эту карту. */
    private static Map<String, UiDump> dumps(String client, int count, String kind, int fires) {
        Map<String, UiDump> result = new LinkedHashMap<>();
        for (String step : List.of("directory-pending", "directory-cancelled")) result.put(step,
                new UiDump(UiDump.SCHEMA, client, DirectoryChooserProbe.SCENARIO, step, null, null, null, null,
                        null, null, null, null, null, null, null, null,
                        List.of(new UiDump.ChooserRequest(kind, "", ru.cashprediction.core.ui.text.UiText.get(
                                "s2.file.folderTitle", "<CashMemory>"), "", "<CashMemory>", "")),
                        Map.of("DirectoryChooser", count), Map.of(CommandId.FILE_CASH_MEMORY.id(), fires)));
        return result;
    }
}
