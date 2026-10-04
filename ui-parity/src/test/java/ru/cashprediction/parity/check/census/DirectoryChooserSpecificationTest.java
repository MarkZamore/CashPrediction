package ru.cashprediction.parity.check.census;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.selftest.SelfTestScript;
import ru.cashprediction.core.ui.text.UiText;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет происхождение спецификации выбора папки без запуска клиентов. */
class DirectoryChooserSpecificationTest {
    /** Полная спецификация принимается; ownTemp дополнительно использует сохранённые реальные дампы. */
    @Test void completeSpecificationRemainsAccepted() throws Exception {
        DirectoryChooserProbe.verifyDirectoryEvidence(actual());
    }

    /** Положительный census не превращает пустой запрос в пользовательский сценарий. */
    @Test void positiveCensusCannotHideEmptySpecification() throws Exception {
        var changed = actual();
        changed.replaceAll((step, dump) -> withRequest(dump,
                new UiDump.ChooserRequest("directory", "", "", "", "", "")));
        assertThrows(AssertionError.class, () -> DirectoryChooserProbe.verifyDirectoryEvidence(changed));
    }

    /** Заголовок обязан соответствовать локализованному запросу FileFlow. */
    @Test void fileSpecificationCannotMasqueradeAsDirectory() throws Exception {
        var changed = actual();
        changed.replaceAll((step, dump) -> withRequest(dump,
                new UiDump.ChooserRequest("directory", "SAVE", "file", "md", "<CashMemory>", "plan.md")));
        assertThrows(AssertionError.class, () -> DirectoryChooserProbe.verifyDirectoryEvidence(changed));
    }

    /** Отмена не должна подменять запрос новым, хотя оба запроса отдельно корректны. */
    @Test void cancellationCannotSubstituteAnotherFolderRequest() throws Exception {
        var changed = actual();
        String folder = "<OtherFolder>";
        changed.computeIfPresent("directory-cancelled", (step, dump) -> withRequest(dump,
                new UiDump.ChooserRequest("directory", "", UiText.get("s2.file.folderTitle", folder), "", folder, "")));
        assertThrows(AssertionError.class, () -> DirectoryChooserProbe.verifyDirectoryEvidence(changed));
    }

    /** Читает копии конкретных завершённых дампов; не генерирует фиктивный native результат. */
    private static Map<String, UiDump> actual() throws Exception {
        String directory = System.getProperty("requirements.directory.dumps");
        if (directory != null) return new LinkedHashMap<>(FxCensus.readScenario(Path.of(directory),
                SelfTestScript.parse(DirectoryChooserProbe.SCENARIO, DirectoryChooserProbe.scriptText())));
        // Обычный JUnit не требует внешних артефактов: это явно синтетические данные guard, не native PASS.
        Map<String, UiDump> dumps = new LinkedHashMap<>();
        for (String step : List.of("directory-pending", "directory-cancelled")) {
            var request = new UiDump.ChooserRequest("directory", "", UiText.get("s2.file.folderTitle", "<CashMemory>"),
                    "", "<CashMemory>", "");
            dumps.put(step, new UiDump(UiDump.SCHEMA, "fx", DirectoryChooserProbe.SCENARIO, step,
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    List.of(request), Map.of("DirectoryChooser", 1), Map.of("file.cashMemory", 1)));
        }
        return dumps;
    }

    /** Меняет только спецификацию, сохраняя положительный реальный census и идентичность шага. */
    private static UiDump withRequest(UiDump d, UiDump.ChooserRequest request) {
        return new UiDump(d.schema(), d.client(), d.scenario(), d.step(), d.frame(), d.menuBar(), d.toolbar(),
                d.summary(), d.table(), d.chart(), d.status(), d.contextMenus(), d.windows(), d.alerts(),
                d.popups(), d.screens(), List.of(request), d.classCensus(), d.counters());
    }
}
