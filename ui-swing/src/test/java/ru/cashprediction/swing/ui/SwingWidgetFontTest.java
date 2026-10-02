package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import java.awt.*;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.forecast.*;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.forms.plan.GoalCalculatorForm;
import ru.cashprediction.core.ui.menu.*;
import ru.cashprediction.core.ui.token.*;
import ru.cashprediction.core.ui.view.status.StatusBuilder;
import ru.cashprediction.core.ui.view.summary.SummaryBuilder;

/** Проверяет реальные Font невидимых виджетов, а не строки стиля и не метаданные дампа. */
class SwingWidgetFontTest {
    @TempDir Path directory;

    @Test void genuineWidgetRejectsAlteredBaseFamilySizeAndWeightThenRestoresFont() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install(); var input = new JTextField();
            Font original = input.getFont();
            font(input, FontToken.BASE, false, "negative.original");
            try {
                input.setFont(new Font(Font.MONOSPACED, Font.PLAIN, original.getSize()));
                AssertionError family = assertThrows(AssertionError.class, () -> font(input, FontToken.BASE, false, "negative.family"));
                assertTrue(family.getMessage().contains("negative.family"));
                System.out.println("DETECTED family actual=" + input.getFont() + " failure=" + family.getMessage());
                input.setFont(original); font(input, FontToken.BASE, false, "negative.restored.family");

                input.setFont(original.deriveFont(original.getSize2D() + 1));
                AssertionError size = assertThrows(AssertionError.class, () -> font(input, FontToken.BASE, false, "negative.size"));
                assertTrue(size.getMessage().contains("negative.size"));
                System.out.println("DETECTED size actual=" + input.getFont() + " failure=" + size.getMessage());
                input.setFont(original); font(input, FontToken.BASE, false, "negative.restored.size");

                input.setFont(original.deriveFont(Font.BOLD));
                AssertionError weight = assertThrows(AssertionError.class, () -> font(input, FontToken.BASE, false, "negative.weight"));
                assertTrue(weight.getMessage().contains("negative.weight"));
                System.out.println("DETECTED weight actual=" + input.getFont() + " failure=" + weight.getMessage());
            } finally { input.setFont(original); }
            assertSame(original, input.getFont()); font(input, FontToken.BASE, false, "negative.restored.final");
        });
    }

    @Test void mainWidgetFontsMatchCoreTokensIncludingToolbarEmphasis() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Fixture fixture = fixture();
            try {
                var menus = new SwingMenus(fixture.port.intents()).bar(MenuModels.menuBar(fixture.state, ClientKind.SWING));
                menuFonts(menus);
                var toolbarModel = MenuModels.toolbar(fixture.state, ClientKind.SWING);
                var toolbar = new SwingToolbar(fixture.port.intents()); toolbar.render(toolbarModel);
                assertTrue(toolbarModel.items().stream().anyMatch(node -> node instanceof ToolbarNode.Button button && button.emphasis() != Emphasis.NONE));
                if (toolbar.filter() != null) font(toolbar.filter(), FontToken.BASE, false, "toolbar.filter");
                for (ToolbarNode node : toolbarModel.items()) {
                    var widget = SwingUiDriver.find(toolbar, node.id());
                    boolean bold = node instanceof ToolbarNode.Button button && button.emphasis() != Emphasis.NONE
                            || node instanceof ToolbarNode.MenuButton menu && menu.emphasis() != Emphasis.NONE;
                    if (widget instanceof AbstractButton || widget instanceof JTextField) font(widget, FontToken.BASE, bold, "toolbar." + node.id());
                    if (widget != null && widget.getClientProperty("cp.main") instanceof Component main) font(main, FontToken.BASE, false, "toolbar.main");
                }
                var status = new SwingStatusBar(); status.render(StatusBuilder.build(fixture.state, Instant.EPOCH));
                int segments = 0;
                for (Component child : status.getComponents()) if (child instanceof JLabel) { font(child, FontToken.SMALL, false, "status"); segments++; }
                assertTrue(segments > 0);
                var summary = new SwingSummaryPanel(fixture.port); summary.render(SummaryBuilder.build(fixture.state));
                int cards = 0;
                for (Component child : summary.getComponents()) if (child instanceof SwingSummaryPanel.Card card) {
                    font(card.title, FontToken.SMALL, false, "card.title"); font(card.caption, FontToken.SMALL, false, "card.caption");
                    font(card.value, FontToken.CARD, true, "card.value"); cards++;
                }
                assertEquals(9, cards);
                var table = new SwingTable(fixture.port);
                font(table.table.getTableHeader(), FontToken.BASE, false, "table.header");
                var renderer = table.table.getTableHeader().getDefaultRenderer();
                font(renderer.getTableCellRendererComponent(table.table, "", false, false, -1, 0), FontToken.BASE, false, "table.header.renderer");
            } finally { fixture.port.exit(ExitKind.CLEAN, 0); }
        });
    }

    @Test void actualDialogHeaderInputsAndResultLabelsMatchTokensWithoutShowingWindow() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            Fixture fixture = fixture();
            var host = (FormSession.Host) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{FormSession.Host.class}, (proxy, method, args) -> null);
            var session = new FormSession(WindowType.GOAL_CALCULATOR, false, new GoalCalculatorForm(), new FormContext("font-test", "main", Map.of(), fixture.state), host);
            // JavaFX: Dialog → Swing: SwingFormDialog → Web: dialog
            var form = new SwingFormDialog(fixture.port, session, session.spec(), session.view(), Placement.centered("main")); session.attach(form);
            try {
                assertFalse(form.dialog.isVisible());
                font(form.header, FontToken.HEADER, true, "dialog.header");
                form.fields.forEach((id, bindings) -> bindings.forEach(binding -> {
                    font(binding.label, FontToken.BASE, false, "dialog.label." + id);
                    if (!(binding.input instanceof JPanel)) font(binding.input, FontToken.BASE, false, "dialog.input." + id);
                }));
                assertEquals(2, form.results.size());
                form.results.forEach(label -> font(label, FontToken.BASE, false, "dialog.result"));
                form.buttons.forEach((id, button) -> font(button, FontToken.BASE, false, "dialog.button." + id));
            } finally { form.close(); fixture.port.exit(ExitKind.CLEAN, 0); }
        });
    }

    /** Проверяет размер, начертание и семейство фактического шрифта виджета. */
    private static void font(Component component, FontToken token, boolean bold, String area) {
        Font actual = component.getFont(), expected = SwingLook.font(token);
        assertNotNull(actual, area); assertEquals(token.sizePx(), actual.getSize(), area);
        assertEquals(bold ? Font.BOLD : Font.PLAIN, actual.getStyle(), area);
        assertEquals(expected.getFamily(), actual.getFamily(), area); assertEquals(expected.getName(), actual.getName(), area);
        System.out.println(area + " actualFont=" + actual + " lineHeight=" + component.getFontMetrics(actual).getHeight());
    }

    /** Обходит реальные пункты меню, включая ещё не открытые дочерние меню. */
    private static void menuFonts(Container root) {
        for (Component child : root instanceof JMenu menu ? menu.getMenuComponents() : root.getComponents()) {
            if (child instanceof JMenuItem) font(child, FontToken.BASE, false, "menu");
            if (child instanceof Container container) menuFonts(container);
        }
    }

    /** Создаёт модель примера без запуска приложения, записи на диск и видимого главного окна. */
    private Fixture fixture() {
        SwingLook.install();
        var env = AppEnvironment.from(LaunchOptions.parse("--home", directory.toString(), "--registry", "memory", "--today", "2026-09-13", "--selftest", "s05-forms-plan"));
        var port = new SwingUiPort(env); var app = new AppController(port, env); port.bind(app);
        var initial = app.state(); var plan = SamplePlan.create(initial.today());
        var document = new DocumentView(plan, null, true, false, "", false, "", ForecastEngine.forecast(plan, WhatIf.NONE, initial.today(), true), "", java.util.List.of());
        var state = new AppState(initial.revision(), initial.profile(), initial.today(), initial.cashMemory(), initial.plansFolder(), document,
                initial.view(), "", initial.pastExpanded(), initial.settings(), initial.recorder(), initial.stores(), initial.windows(), initial.status(), "");
        return new Fixture(port, state);
    }

    /** Ссылки только на невидимый порт и неизменяемую тестовую модель. */
    private record Fixture(SwingUiPort port, AppState state) { }
}
