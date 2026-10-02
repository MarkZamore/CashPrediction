package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Map;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.forecast.*;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.forms.plan.GoalCalculatorForm;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.token.*;

/** Регрессии общего резерва результата: две строки, пустая ошибка и рост настоящего текста. */
class SwingResultsPanelTest {
    @TempDir Path directory;

    @Test void actualUnshownGoalKeepsContentHeightForValidTwoAndInvalidZeroResults() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            var env = AppEnvironment.from(LaunchOptions.parse("--home", directory.toString(), "--registry", "memory", "--today", "2026-09-13", "--selftest", "s05-forms-plan"));
            var port = new SwingUiPort(env); var app = new AppController(port, env); port.bind(app);
            var initial = app.state(); var plan = SamplePlan.create(initial.today());
            var document = new DocumentView(plan, null, false, false, "", false, "", ForecastEngine.forecast(plan, WhatIf.NONE, initial.today(), true), "", java.util.List.of());
            var state = new AppState(initial.revision(), initial.profile(), initial.today(), initial.cashMemory(), initial.plansFolder(), document,
                    initial.view(), "", initial.pastExpanded(), initial.settings(), initial.recorder(), initial.stores(), initial.windows(), initial.status(), "");
            var host = (FormSession.Host) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{FormSession.Host.class}, (proxy, method, args) -> null);
            var session = new FormSession(WindowType.GOAL_CALCULATOR, false, new GoalCalculatorForm(), new FormContext("results-test", "main", Map.of(), state), host);
            // JavaFX: Dialog → Swing: SwingFormDialog → Web: dialog
            var form = new SwingFormDialog(port, session, session.spec(), session.view(), Placement.centered("main")); session.attach(form);
            try {
                session.fieldChanged("target", "300000", true, 1); form.dialog.pack();
                assertEquals(2, session.view().results().size()); assertEquals(2, form.results.size());
                int validHeight = form.content.getHeight();
                session.fieldChanged("target", "bad", true, 2); form.dialog.pack();
                assertEquals(0, session.view().results().size()); assertEquals(0, form.results.size());
                assertEquals(validHeight, form.content.getHeight()); assertFalse(form.dialog.isVisible());
                System.out.println("actual unshown goal valid2Height=" + validHeight + " invalid0Height=" + form.content.getHeight());
            } finally { form.close(); port.exit(ExitKind.CLEAN, 0); }
        });
    }

    @Test void validTwoAndInvalidZeroKeepSameMeasuredMinimumWithoutFakeRows() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install(); var panel = new SwingResultsPanel("results", 3);
            int lineHeight = panel.getFontMetrics(panel.getFont()).getHeight();
            panel.add(line(UiText.get("button.save"), 560)); panel.add(line(UiText.get("button.cancel"), 560));
            int validHeight = panel.getPreferredSize().height;
            assertEquals(3 * lineHeight, validHeight); assertEquals(2, panel.getComponentCount());
            panel.removeAll();
            assertEquals(validHeight, panel.getPreferredSize().height);
            assertEquals(validHeight, panel.getMinimumSize().height); assertEquals(0, panel.getComponentCount());
            System.out.println("BASE lineHeight=" + lineHeight + " valid2=" + validHeight + " invalid0=" + panel.getPreferredSize().height);
        });
    }

    @Test void additionalAndWrappedRealLinesGrowBeyondReserve() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install(); var panel = new SwingResultsPanel("results", 3);
            int reserved = panel.getPreferredSize().height;
            for (int i = 0; i < 4; i++) panel.add(line(UiText.get("button.save"), 560));
            assertTrue(panel.getPreferredSize().height > reserved);
            int fourHeight = panel.getPreferredSize().height;
            panel.removeAll(); panel.add(line((UiText.get("button.save") + " ").repeat(30), 80));
            assertTrue(panel.getPreferredSize().height > reserved);
            System.out.println("reserve=" + reserved + " actual4=" + fourHeight + " actualWrapped=" + panel.getPreferredSize().height);
        });
    }

    @Test void zeroMinimumDoesNotIntroduceAnyEmptyHeight() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install(); var panel = new SwingResultsPanel("results", 0);
            assertEquals(0, panel.getPreferredSize().height); assertEquals(0, panel.getMinimumSize().height);
        });
    }

    /** Создаёт тот же реальный HTML-виджет, которым форма рисует строку результата. */
    private static JLabel line(String text, int width) { return SwingLook.label(SwingLook.html(text, width), ColorToken.TEXT_PRIMARY, FontToken.BASE); }
}
