package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import java.awt.*;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Map;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.forms.plan.GoalCalculatorForm;
import ru.cashprediction.core.ui.forms.ops.QuickEditForm;
import ru.cashprediction.core.ui.text.UiText;

/** Ограниченные проверки кнопок и выбора цели фокуса без Robot и захвата нативного фокуса. */
class SwingDriverLayoutTest {
    @TempDir Path directory;

    /** Быстрая правка без кнопок сохраняет настоящую доступную цель переноса фокуса. */
    @Test void quickEditWithoutButtonsCommitsByMovingFocusInsideItsContent() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            var env = AppEnvironment.from(LaunchOptions.parse("--home", directory.toString(), "--registry", "memory", "--selftest", "s12-quick-edit"));
            var port = new SwingUiPort(env); var app = new AppController(port, env); port.bind(app);
            var host = (FormSession.Host) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{FormSession.Host.class}, (proxy, method, args) -> null);
            var session = new FormSession(WindowType.QUICK_EDIT_POPUP, false, new QuickEditForm(), new FormContext("quick-focus", "main", Map.of(), app.state()), host);
            // JavaFX: Popup → Swing: PopupFactory → Web: div быстрой правки
            var popup = new SwingQuickEditPopup(port, session, session.spec(), session.view(), Placement.centered("main"));
            session.attach(popup);
            try {
                assertTrue(popup.buttons.isEmpty());
                assertTrue(popup.content.isFocusable());
                assertSame(popup.content, SwingUiDriver.commitFocusTarget(popup));
                assertFalse(popup.dialog.isVisible());
            } finally { popup.close(); port.exit(ExitKind.CLEAN, 0); }
        });
    }

    @Test void leftCaptionWidthIncludesActualBaseFontAndHorizontalPadding() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            var first = new JButton(UiText.get("button.saveGoal"));
            var second = new JButton(UiText.get("button.showWithExtra"));
            SwingLook.dialogButton(first); SwingLook.dialogButton(second);
            int captionWidth = first.getFontMetrics(first.getFont()).stringWidth(first.getText());
            Insets insets = first.getInsets();
            assertEquals(22, insets.left + insets.right);
            assertEquals(Math.max(88, captionWidth + insets.left + insets.right), first.getPreferredSize().width);
            JPanel bar = new JPanel(); bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
            bar.add(first); bar.add(Box.createHorizontalStrut(8)); bar.add(second); bar.add(Box.createHorizontalGlue());
            bar.setSize(608, 28); bar.doLayout();
            assertEquals(8, second.getX() - first.getX() - first.getWidth());
            assertEquals(first.getPreferredSize().width, first.getWidth());
            System.out.println("LEFT captionWidth=" + captionWidth + " insets=" + insets + " actualWidth=" + first.getWidth()
                    + " nextContentX=" + (16 + second.getX()));
        });
    }

    @Test void metalMarginsDoNotInflateSaveButtonAndRealBarKeepsEightPixelGap() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            var save = new JButton(UiText.get("button.save")); var cancel = new JButton(UiText.get("button.cancel"));
            int nativeWidth = save.getPreferredSize().width;
            SwingLook.dialogButton(save); SwingLook.dialogButton(cancel);
            assertEquals(88, save.getPreferredSize().width); assertEquals(88, cancel.getPreferredSize().width);
            JPanel bar = new JPanel(); bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
            bar.add(Box.createHorizontalGlue()); bar.add(save); bar.add(Box.createHorizontalStrut(8)); bar.add(cancel);
            bar.setSize(528, 28); bar.doLayout();
            assertEquals(344, save.getX()); assertEquals(440, cancel.getX());
            assertEquals(8, cancel.getX() - save.getX() - save.getWidth());
            System.out.println("save nativeWidth=" + nativeWidth + " actualWidth=" + save.getWidth() + " contentX=" + (16 + save.getX()));
        });
    }

    @Test void commitTargetIsRealEnabledButtonAndRestoredRawBoundsStayUnchanged() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            var env = AppEnvironment.from(LaunchOptions.parse("--home", directory.toString(), "--registry", "memory", "--selftest", "s05-forms-plan"));
            var port = new SwingUiPort(env); var app = new AppController(port, env); port.bind(app);
            var host = (FormSession.Host) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{FormSession.Host.class}, (proxy, method, args) -> null);
            var session = new FormSession(WindowType.GOAL_CALCULATOR, false, new GoalCalculatorForm(), new FormContext("focus", "main", Map.of(), app.state()), host);
            // JavaFX: Dialog → Swing: SwingFormDialog → Web: dialog
            var form = new SwingFormDialog(port, session, session.spec(), session.view(), Placement.centered("main"));
            session.attach(form);
            try {
                assertFalse(form.dialog.isVisible());
                assertSame(form.buttons.get(form.spec.buttons().getLast().id()), SwingUiDriver.commitFocusTarget(form));
                form.buttons.values().forEach(button -> button.setEnabled(false));
                assertThrows(IllegalStateException.class, () -> SwingUiDriver.commitFocusTarget(form));
                Rectangle screen = form.dialog.getGraphicsConfiguration().getBounds();
                var restored = new WindowBounds(screen.x + 30, screen.y + 40, 750, 400);
                SwingUiPort.place(form.dialog, restored, null);
                assertEquals(restored, form.bounds());
            } finally { form.close(); port.exit(ExitKind.CLEAN, 0); }
        });
    }
}
