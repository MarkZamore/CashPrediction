package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.time.Duration;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.ui.command.*;
import ru.cashprediction.core.ui.selftest.*;

/** Проверяет сохранение настоящего фокуса таблицы при обновлениях пропуска и истории. */
class SwingTableFocusTest {
    @TempDir Path directory;
    @Test void redrawDoesNotMoveTableFocusToFilterAndRedoRunsOnce() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUiPort[] ports = new SwingUiPort[1]; AppController[] apps = new AppController[1];
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            var env = AppEnvironment.from(LaunchOptions.parse("--home", directory.toString(), "--registry", "memory", "--today", "2026-09-13", "--selftest", "s13-undo-redo"));
            ports[0] = new SwingUiPort(env); apps[0] = new AppController(ports[0], env); ports[0].bind(apps[0]); apps[0].start();
        });
        try {
            var driver = new SwingUiDriver(ports[0], apps[0], "focus-test");
            for (SelfTestCommand command : java.util.List.of(new SelfTestCommand.Key(KeyChord.parse("Esc")),
                    new SelfTestCommand.Sample(), new SelfTestCommand.Select("r1@2026-10-05"),
                    new SelfTestCommand.Menu("edit.skip"), new SelfTestCommand.Key(KeyChord.parse("Ctrl+Z")),
                    new SelfTestCommand.Key(KeyChord.parse("Ctrl+Y")))) {
                driver.execute(command); driver.awaitIdle(Duration.ofSeconds(5));
            }
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(ports[0].frame.table.table.isFocusOwner());
                assertEquals("r1@2026-10-05", apps[0].state().selectedRowId());
                assertEquals(-1, ports[0].frame.table.table.getSelectedRow());
                assertEquals(1, apps[0].executedCount(CommandId.EDIT_UNDO));
                assertEquals(1, apps[0].executedCount(CommandId.EDIT_REDO));
                assertTrue(((javax.swing.JMenuItem) SwingUiDriver.find(ports[0].frame.menus, "edit.reset")).isEnabled());
            });
        } finally { SwingUtilities.invokeAndWait(() -> ports[0].exit(ExitKind.CLEAN, 0)); }
    }
}
