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
import ru.cashprediction.core.forecast.*;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.forms.plan.*;
import ru.cashprediction.core.ui.forms.ops.*;

/** Измеряет упакованные, но не показанные формы: без Robot, фокуса и повторения сценариев. */
class SwingFormMetricsTest {
    @TempDir Path directory;

    @Test void measuredRowsExplainContentHeightAndKeepSpecifiedGaps() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            var env = AppEnvironment.from(LaunchOptions.parse("--home", directory.toString(), "--registry", "memory", "--today", "2026-09-13", "--selftest", "s05-forms-plan"));
            var port = new SwingUiPort(env); var app = new AppController(port, env); port.bind(app);
            var initial = app.state(); var plan = SamplePlan.create(initial.today());
            var document = new DocumentView(plan, null, false, false, "", false, "", ForecastEngine.forecast(plan, WhatIf.NONE, initial.today(), true), "", java.util.List.of());
            var state = new AppState(initial.revision(), initial.profile(), initial.today(), initial.cashMemory(), initial.plansFolder(), document,
                    initial.view(), "r1@2026-10-05", initial.pastExpanded(), initial.settings(), initial.recorder(), initial.stores(), initial.windows(), initial.status(), "");
            try {
                measure(port, state, WindowType.PLAN_SETTINGS, new PlanSettingsForm(), Map.of());
                measure(port, state, WindowType.RULE_EDITOR, new RuleEditorForm(), Map.of(WindowType.CONTEXT_MODE, "create", "kind", "INCOME"));
                measure(port, state, WindowType.ADJUSTMENT_EDITOR, new AdjustmentForm(), Map.of(WindowType.CONTEXT_RULE_ID, "r1", WindowType.CONTEXT_ORIGINAL_DATE, "2026-10-05"));
                measure(port, state, WindowType.GOAL_CALCULATOR, new GoalCalculatorForm(), Map.of());
            } finally { port.exit(ExitKind.CLEAN, 0); }
        });
    }

    /** Печатает фактические прямоугольники и предпочтительные размеры, не создавая дамп из модели. */
    private void measure(SwingUiPort port, AppState state, WindowType type, FormLogic logic, Map<String, String> context) {
        var host = (FormSession.Host) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{FormSession.Host.class}, (proxy, method, args) -> null);
        var session = new FormSession(type, true, logic, new FormContext("metrics", "main", context, state), host);
        // JavaFX: Dialog → Swing: SwingFormDialog → Web: dialog
        var form = new SwingFormDialog(port, session, session.spec(), session.view(), Placement.centered("main"));
        session.attach(form);
        try {
            assertFalse(form.dialog.isVisible());
            var layout = (BorderLayout) form.content.getLayout();
            var top = layout.getLayoutComponent(BorderLayout.NORTH);
            var bottom = layout.getLayoutComponent(BorderLayout.SOUTH);
            System.out.println(type + " content=" + form.content.getSize() + " padding=" + form.content.getInsets()
                    + " header=" + top.getBounds() + " body=" + form.body.getBounds() + " footer=" + bottom.getBounds()
                    + " problem=" + form.problem.getBounds() + " buttons=" + form.buttonBar.getBounds());
            var grid = (JPanel) form.body.getClientProperty("cp.grid");
            var gridLayout = (GridBagLayout) grid.getLayout();
            for (JLabel section : form.sections) if (section.getParent() == grid) {
                assertNotNull(section.getBorder());
                assertEquals(1, section.getInsets().top);
                assertTrue(section.getFont().isBold());
            }
            if (type == WindowType.RULE_EDITOR) {
                var side = ((BorderLayout) form.body.getLayout()).getLayoutComponent(BorderLayout.EAST);
                assertTrue(((Container) side).getComponent(0) instanceof JSeparator);
                assertEquals(300, form.fields.get("dates").getFirst().root.getWidth());
            }
            System.out.println("grid=" + grid.getBounds() + " preferred=" + grid.getPreferredSize());
            for (Component child : grid.getComponents()) if (child.isVisible()) {
                var constraints = gridLayout.getConstraints(child);
                assertEquals(8, constraints.insets.top + constraints.insets.bottom);
                System.out.println("row=" + constraints.gridy + " column=" + constraints.gridx + " class=" + child.getClass().getSimpleName()
                        + " box=" + child.getBounds() + " preferred=" + child.getPreferredSize());
            }
            assertEquals(form.content.getHeight(), form.content.getInsets().top + top.getHeight()
                    + layout.getVgap() + form.body.getHeight() + layout.getVgap() + bottom.getHeight() + form.content.getInsets().bottom);
            form.fields.forEach((id, bindings) -> bindings.forEach(binding -> {
                if (binding.root.isVisible()) {
                    if (binding.input instanceof JTextField || binding.input instanceof JComboBox<?> || binding.input instanceof JSpinner || binding.input instanceof JCheckBox)
                        assertEquals(28, binding.input.getHeight(), id);
                    System.out.println("field=" + id + " kind=" + binding.spec.kind() + " root=" + binding.root.getSize() + " input=" + binding.input.getSize());
                }
            }));
        } finally { form.close(); }
    }
}
