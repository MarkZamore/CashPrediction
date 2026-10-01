package ru.cashprediction.core.app.view;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.io.AppInfo;
import ru.cashprediction.core.markdown.MarkdownFormat;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.ui.alert.AlertKind;
import ru.cashprediction.core.ui.command.HotkeyTable;
import ru.cashprediction.core.ui.text.UiText;

/** Проверяет содержание общей справки и отсутствие изменений документа для всех клиентов. */
class HelpFlowTest {
    @TempDir Path home;

    @Test void aboutReportsTheActualClientJavaVersionAndIsolatedCashMemory() {
        for (ClientProfile profile : List.of(ClientProfile.fx("25"), ClientProfile.swing(), ClientProfile.web())) {
            ViewHelpHarness h = new ViewHelpHarness(Plan.empty("Plan", ViewHelpHarness.TODAY), home, profile);
            h.help.about();
            var alert = h.port.alerts().getFirst();
            assertEquals("about", alert.purpose());
            assertEquals(AlertKind.INFORMATION, alert.kind());
            assertEquals(UiText.get("alert.about.content", AppInfo.displayVersion(), profile.clientTitle(),
                    System.getProperty("java.version"), h.environment.cashMemory()), alert.content());
            assertFalse(alert.restorable());
            assertFalse(alert.detailsExpanded());
            assertEquals("ok", alert.defaultButtonId());
            assertEquals(1, alert.buttons().size());
            assertFalse(h.document.isDirty());
            assertFalse(h.document.canUndo());
        }
    }

    @Test void hotkeysAndFormatHaveExactSharedDetailsExpandedAndAnOkButton() {
        ViewHelpHarness h = new ViewHelpHarness(Plan.empty("Plan", ViewHelpHarness.TODAY), home);
        h.help.hotkeys();
        h.help.format();
        var hotkeys = h.port.alerts().get(0);
        var format = h.port.alerts().get(1);
        assertEquals("hotkeys", hotkeys.purpose());
        assertEquals(HotkeyTable.text(), hotkeys.details());
        assertEquals("fileFormat", format.purpose());
        assertEquals(MarkdownFormat.userGuide(), format.details());
        for (var alert : h.port.alerts()) {
            assertEquals(AlertKind.INFORMATION, alert.kind());
            assertTrue(alert.detailsExpanded());
            assertFalse(alert.restorable());
            assertEquals("ok", alert.defaultButtonId());
            assertEquals(1, alert.buttons().size());
            assertFalse(alert.content().contains("!"));
        }
        h.port.pendingAlerts().get(0).press("ok");
        h.port.pendingAlerts().get(0).press("ok");
        assertFalse(h.document.isDirty());
        assertFalse(h.document.canUndo());
    }
}
