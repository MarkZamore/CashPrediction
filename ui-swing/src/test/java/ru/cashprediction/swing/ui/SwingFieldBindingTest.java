package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import java.awt.GraphicsEnvironment;
import java.awt.Robot;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Map;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.forms.plan.GoalCalculatorForm;
import ru.cashprediction.core.ui.selftest.SelfTestCommand;

/** Регрессии отложенных правок двух полей и настоящего завершения ввода в Swing. */
class SwingFieldBindingTest {
    @TempDir Path directory;

    @Test void quickEnterFromContentCommitsFieldAndKeepsInvalidPopupOpen() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            Fixture fixture = create();
            try {
                fixture.app.command(ru.cashprediction.core.ui.command.CommandId.FILE_SAMPLE,
                        ru.cashprediction.core.ui.command.CommandArgs.NONE, ru.cashprediction.core.ui.command.InvokeSource.MAIN);
                var host = (FormSession.Host) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{FormSession.Host.class}, (proxy, method, args) -> null);
                var session = new FormSession(WindowType.QUICK_EDIT_POPUP, false,
                        new ru.cashprediction.core.ui.forms.ops.QuickEditForm(),
                        new FormContext("quick1", "main", Map.of(WindowType.CONTEXT_RULE_ID, "r1", WindowType.CONTEXT_ORIGINAL_DATE, "2026-10-05"), fixture.app.state()), host);
                // JavaFX: Popup → Swing: SwingQuickEditPopup → Web: div быстрой правки
                var popup = new SwingQuickEditPopup(fixture.port, session, session.spec(), session.view(), Placement.centered("main"));
                session.attach(popup); fixture.port.forms.put("quick1", popup);
                ((JTextField) popup.fields.get("amount").getFirst().input).setText("0");
                assertTrue(SwingUtilities.isDescendingFrom(popup.fields.get("amount").getFirst().input, popup.content));
                var invalid = new java.awt.event.KeyEvent(popup.content, java.awt.event.KeyEvent.KEY_PRESSED, 1, 0, java.awt.event.KeyEvent.VK_ENTER, '\n');
                assertTrue(fixture.port.keys.dispatchKeyEvent(invalid));
                assertFalse(session.isClosed()); assertFalse(session.view().problem().display().isEmpty());
                ((JTextField) popup.fields.get("amount").getFirst().input).setText("81000");
                var valid = new java.awt.event.KeyEvent(popup.content, java.awt.event.KeyEvent.KEY_PRESSED, 2, 0, java.awt.event.KeyEvent.VK_ENTER, '\n');
                assertTrue(fixture.port.keys.dispatchKeyEvent(valid));
                assertTrue(session.isClosed()); assertTrue(valid.isConsumed());
            } finally { fixture.port.exit(ExitKind.CLEAN, 0); }
        });
    }

    @Test void buttonCommitCancelsQueuedEditAndAcceptsImmediateCoreReset() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Fixture[] fixture = new Fixture[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                fixture[0] = create();
                var extra = fixture[0].form.fields.get("extraSaving").getFirst();
                ((JTextField) fixture[0].form.fields.get("target").getFirst().input).setText("400000");
                ((JTextField) extra.input).setText("5000");
                fixture[0].form.commit();
                assertEquals("5 000,00", extra.raw());
                assertTrue(fixture[0].form.buttons.get("showExtra").isEnabled());
                fixture[0].session.apply(new FormOutcome.SetFields(Map.of("extraSaving", "")));
                assertEquals("", extra.raw());
            });
            SwingUtilities.invokeAndWait(() -> assertEquals("", fixture[0].session.state().value("extraSaving")));
        } finally { dispose(fixture[0]); }
    }

    @Test void queuedEditOfAnotherFieldDoesNotOverwriteUnsentText() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Fixture[] fixture = new Fixture[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                fixture[0] = create();
                ((JTextField) fixture[0].form.fields.get("target").getFirst().input).setText("400000");
                ((JTextField) fixture[0].form.fields.get("extraSaving").getFirst().input).setText("5000");
            });
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(FieldCodec.canonical(FieldKind.MONEY, "400000"), fixture[0].session.state().value("target"));
                assertEquals(FieldCodec.canonical(FieldKind.MONEY, "5000"), fixture[0].session.state().value("extraSaving"));
                assertTrue(fixture[0].form.buttons.get("showExtra").isEnabled());
                assertEquals("5000", fixture[0].form.fields.get("extraSaving").getFirst().raw());
            });
        } finally { dispose(fixture[0]); }
    }

    @Test void fillFormatsMoneyThroughActualFocusLossAndAcceptsLaterCoreUpdate() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Fixture[] fixture = new Fixture[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                fixture[0] = create(); fixture[0].form.dialog.setVisible(true);
            });
            new Robot().waitForIdle();
            SwingUiDriver driver = new SwingUiDriver(fixture[0].port, fixture[0].app, "binding-test");
            driver.execute(new SelfTestCommand.Fill("w1", Map.of("target", "400000", "extraSaving", "5000")));
            SwingUtilities.invokeAndWait(() -> {
                var extra = fixture[0].form.fields.get("extraSaving").getFirst();
                assertFalse(extra.input.isFocusOwner());
                assertEquals("5 000,00", extra.raw());
                assertTrue(fixture[0].form.buttons.get("showExtra").isEnabled());
                fixture[0].session.apply(new FormOutcome.SetFields(Map.of("extraSaving", "")));
                assertEquals("", extra.raw());
                assertEquals(640, fixture[0].form.content.getWidth());
                var dump = new SwingUiDumper(fixture[0].port, fixture[0].app, "binding-test").dump("filled");
                assertTrue(dump.windows().getFirst().buttons().stream().allMatch(button -> button.x() >= 12));
            });
        } finally { dispose(fixture[0]); }
    }

    /** Создаёт только форму; хранилища и запуск приложения этому тесту не требуются. */
    private Fixture create() {
        SwingLook.install();
        var environment = AppEnvironment.from(LaunchOptions.parse(new String[]{"--home", directory.toString(), "--registry", "memory", "--today", "2026-09-13", "--selftest", "s05-forms-plan"}));
        var port = new SwingUiPort(environment); var app = new AppController(port, environment); port.bind(app);
        app.showMain(null);
        var host = (FormSession.Host) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{FormSession.Host.class}, (proxy, method, args) -> null);
        var session = new FormSession(WindowType.GOAL_CALCULATOR, false, new GoalCalculatorForm(), new FormContext("w1", "main", Map.of(), app.state()), host);
        // JavaFX: Dialog → Swing: SwingFormDialog → Web: dialog
        var form = new SwingFormDialog(port, session, session.spec(), session.view(), Placement.centered("main"));
        session.attach(form); port.forms.put("w1", form);
        return new Fixture(port, app, session, form);
    }

    /** Освобождает окно, диспетчер клавиш и планировщик даже при ошибке утверждения. */
    private static void dispose(Fixture fixture) throws Exception {
        if (fixture != null) SwingUtilities.invokeAndWait(() -> fixture.port.exit(ExitKind.CLEAN, 0));
    }

    /** Ссылки на настоящую форму и её сеанс для проверки наблюдаемого результата. */
    private record Fixture(SwingUiPort port, AppController app, FormSession session, SwingFormDialog form) { }
}
