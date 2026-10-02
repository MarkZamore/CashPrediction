package ru.cashprediction.core.ui.selftest;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.ui.command.KeyChord;
import static org.junit.jupiter.api.Assertions.*;

/** Отличает логический выбор события от видимого выделения и проверяет клавиатурное меню. */
final class ModelSelectionTest {
    @TempDir Path root;

    /** Контекстный щелчок по итогу или группе выделяет именно её; карточка не меняет выбор таблицы. */
    @Test void contextSelectsSpecialTableRowsBeforeOpeningMenu() throws Exception {
        var driver = driver();
        try {
            driver.execute(new SelfTestCommand.Context("row:start"));
            assertEquals("start", driver.dump("start").table().selectedRowId());
            driver.execute(new SelfTestCommand.Context("total:total@2026-09"));
            assertEquals("total@2026-09", driver.dump("total").table().selectedRowId());
            driver.execute(new SelfTestCommand.Context("pastHeader"));
            assertEquals("past@group", driver.dump("past").table().selectedRowId());
            driver.execute(new SelfTestCommand.Context("card:now"));
            assertEquals("past@group", driver.dump("card").table().selectedRowId());
        } finally { driver.stopTimers(); }
    }

    /** Скрытая строка не выделена; отмена пропуска снова делает сохранённый выбор видимым. */
    @Test void hiddenSelectionBecomesVisibleAfterUndo() throws Exception {
        var driver = driver();
        try {
            driver.execute(new SelfTestCommand.Select("r1@2026-10-05"));
            var selected = driver.dump("selected").table();
            assertEquals("r1@2026-10-05", selected.selectedRowId());
            assertEquals("accent.weak", selected.rows().stream()
                    .filter(row -> row.rowId().equals(selected.selectedRowId())).findFirst().orElseThrow().background());
            driver.execute(new SelfTestCommand.Menu("edit.skip"));
            assertEquals("", driver.dump("skipped").table().selectedRowId());
            driver.execute(new SelfTestCommand.Key(KeyChord.parse("Ctrl+Z")));
            assertEquals("r1@2026-10-05", driver.dump("undo").table().selectedRowId());
        } finally { driver.stopTimers(); }
    }

    /** Shift+F10 передаёт идентификатор выбранной строки, а не один тип цели. */
    @Test void keyboardContextIncludesRowIdentity() throws Exception {
        var driver = driver();
        try {
            driver.execute(new SelfTestCommand.Select("r1@2026-10-05"));
            driver.execute(new SelfTestCommand.Key(KeyChord.parse("Shift+F10")));
            assertEquals("row:r1@2026-10-05", driver.dump("context").contextMenus().getFirst().target());
        } finally { driver.stopTimers(); }
    }

    /** Доступная команда сброса действительно возвращает скрытое событие, а отмена снова скрывает его. */
    @Test void hiddenSelectionCanResetSkippedEvent() throws Exception {
        var driver = driver();
        try {
            driver.execute(new SelfTestCommand.Select("r1@2026-10-05"));
            driver.execute(new SelfTestCommand.Menu("edit.skip"));
            assertEquals("", driver.dump("hidden").table().selectedRowId());
            driver.execute(new SelfTestCommand.Menu("edit.reset"));
            assertEquals("r1@2026-10-05", driver.dump("reset").table().selectedRowId());
            driver.execute(new SelfTestCommand.Key(KeyChord.parse("Ctrl+Z")));
            assertEquals("", driver.dump("undo").table().selectedRowId());
        } finally { driver.stopTimers(); }
    }

    /** Создаёт изолированный контроллер и открывает пример обычным пользовательским действием. */
    private ModelUiDriver driver() throws Exception {
        var env = AppEnvironment.from(LaunchOptions.parse("--home", root.toString(), "--registry", "memory", "--today", "2026-09-13"));
        var driver = new ModelUiDriver(env, ClientProfile.fx("25"));
        var report = new SelfTestRunner(driver, root.resolve("out"))
                .run(SelfTestScript.parse("selection", "key Esc\nsample\n"));
        assertTrue(report.ok(), report.toString());
        return driver;
    }
}
