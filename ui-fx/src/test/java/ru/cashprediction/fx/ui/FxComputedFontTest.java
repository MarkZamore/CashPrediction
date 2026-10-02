package ru.cashprediction.fx.ui;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.forms.ops.RuleEditorForm;
import ru.cashprediction.core.ui.forms.plan.GoalCalculatorForm;
import ru.cashprediction.core.ui.token.FontToken;
import ru.cashprediction.core.ui.view.MainScreenModel;
import ru.cashprediction.core.ui.view.ScreenPart;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет вычисленные CSS-шрифты настоящих рендереров без показа Stage, Robot и вымышленных UiDump. */
class FxComputedFontTest {
    @TempDir static Path temporaryHome;
    static FxUiPort port;
    private static FxFormDialog rule, goal;

    @BeforeAll static void prepareNativeScenes() throws Exception {
        Platform.startup(() -> Platform.setImplicitExit(false));
        onFx(() -> {
            port = new FxUiPort(new Stage());
            var bridge = (UiPort) Proxy.newProxyInstance(UiPort.class.getClassLoader(), new Class<?>[]{UiPort.class}, (proxy, method, args) -> {
                if (method.getName().equals("showMain")) {
                    port.main = new MainWindowView(port);
                    port.main.render((MainScreenModel) args[0], EnumSet.allOf(ScreenPart.class));
                    return null;
                }
                try { return method.invoke(port, args); }
                catch (InvocationTargetException e) { throw e.getCause(); }
            });
            var environment = AppEnvironment.from(LaunchOptions.parse("--home", temporaryHome.toString(), "--registry", "memory", "--today", "2026-09-13"));
            var controller = new AppController(bridge, environment); port.bind(controller);
            controller.document().replace(SamplePlan.create(environment.clock().today()), null, true, List.of());
            // Модель строит настоящий контроллер. Только эффект показа Stage перехвачен стендом.
            controller.showMain(null); css(port.main.root, 1200, 800);
            rule = form(new RuleEditorForm(), WindowType.RULE_EDITOR, "font-rule", Map.of("mode", "create", "kind", "INCOME"), controller);
            goal = form(new GoalCalculatorForm(), WindowType.GOAL_CALCULATOR, "font-goal", Map.of(), controller);
            assertFalse(port.stage.isShowing()); assertTrue(Window.getWindows().stream().noneMatch(Window::isShowing));
        });
    }

    @AfterAll static void releaseNativeScenes() throws Exception {
        try { onFx(() -> { if (port != null) { port.scheduler.shutdown(); port.stage.close(); } }); }
        finally { Platform.exit(); }
    }

    static FxFormDialog form(FormLogic logic, WindowType type, String id, Map<String, String> contextValues, AppController controller) {
        var context = new FormContext(id, "main", contextValues, controller.state());
        var host = (FormSession.Host) Proxy.newProxyInstance(FormSession.Host.class.getClassLoader(), new Class<?>[]{FormSession.Host.class},
                (proxy, method, args) -> { throw new AssertionError("Unexpected form event: " + method.getName()); });
        var session = new FormSession(type, true, logic, context, host);
        var state = new FormState(0, logic.defaults(context));
        var form = new FxFormDialog(session, logic.spec(context), logic.evaluate(state, context), Placement.centered("main"), port);
        // Конструктор рендерера откладывает show; закрытие до очереди сохраняет все виджеты, запрещая показ.
        form.close(); new Scene(form.dialog.getDialogPane(), form.spec.width(), 700);
        css(form.dialog.getDialogPane(), form.spec.width(), 700);
        return form;
    }

    static void css(Region root, double width, double height) { root.resize(width, height); root.applyCss(); root.layout(); }
    static void onFx(Runnable action) throws Exception {
        var task = new FutureTask<Void>(() -> { action.run(); return null; }); Platform.runLater(task); task.get(15, TimeUnit.SECONDS);
    }
    static void font(String id, Font actual, FontToken token, boolean bold) {
        System.out.println("FONT_NATIVE " + id + " family=" + actual.getFamily() + " face=" + actual.getName()
                + " size=" + actual.getSize() + " style=" + actual.getStyle());
        assertEquals(token.primaryFamily(), actual.getFamily(), id);
        assertEquals(token.sizePx(), actual.getSize(), 0.001, id);
        String style = actual.getStyle().toLowerCase(Locale.ROOT);
        assertEquals(bold, style.contains("bold"), id); assertFalse(style.contains("italic"), id);
    }
    static void label(String id, Labeled label, FontToken token, boolean bold) {
        font(id, label.getFont(), token, bold);
        if (label.getText() != null && !label.getText().isEmpty()) for (Node node : label.lookupAll(".text")) if (node instanceof Text text && text.getText().equals(label.getText()))
            font(id + ".paintedText", text.getFont(), token, bold);
    }

    /** Скин MenuBar создаёт реальные подписи меню с BASE, без проверки одних логических токенов. */
    @Test void menuBarUsesActualBaseFont() throws Exception { onFx(() -> {
        var nodes = port.main.bar.lookupAll(".menu-button"); assertFalse(nodes.isEmpty());
        for (Node node : nodes) label("menuBar", (Labeled) node, FontToken.BASE, false);
    }); }
    /** Кнопки панели и настоящий редактор фильтра имеют BASE; сохранение сохраняет явную жирность. */
    @Test void toolbarUsesActualControlFonts() throws Exception { onFx(() -> {
        int count = 0;
        for (var entry : port.main.toolbar.widgets.entrySet()) {
            if (entry.getValue() instanceof Labeled labeled) { label(entry.getKey(), labeled, FontToken.BASE, entry.getKey().equals("tb.save")); count++; }
            else if (entry.getValue() instanceof TextInputControl text) { font(entry.getKey(), text.getFont(), FontToken.BASE, false); count++; }
        }
        assertTrue(count > 5);
    }); }
    /** Подписи и пояснения всех настоящих карточек используют SMALL. */
    @Test void cardCaptionsUseActualSmallFont() throws Exception { onFx(() -> {
        assertFalse(port.main.summary.getChildren().isEmpty());
        for (Node node : port.main.summary.getChildren()) if (node instanceof VBox card) {
            label("card.title", (Label) card.getChildren().getFirst(), FontToken.SMALL, false);
            label("card.caption", (Label) card.getChildren().get(2), FontToken.SMALL, false);
        }
    }); }
    /** Значения карточек рисуются настоящим CARD-шрифтом с жирным начертанием. */
    @Test void cardValuesUseActualBoldCardFont() throws Exception { onFx(() -> {
        for (Node node : port.main.summary.getChildren()) if (node instanceof VBox card)
            label("card.value", (Label) card.getChildren().get(1), FontToken.CARD, true);
    }); }
    /** Каждый реально созданный сегмент статуса использует SMALL. */
    @Test void statusUsesActualSmallFont() throws Exception { onFx(() -> {
        assertFalse(port.main.status.getChildren().isEmpty());
        for (Node node : port.main.status.getChildren()) label("status", (Label) node, FontToken.SMALL, false);
    }); }
    /** Заголовки нативного скина таблицы используют BASE с жирностью заголовка. */
    @Test void tableHeadersUseActualBoldBaseFont() throws Exception { onFx(() -> {
        var headers = port.main.table.root.lookupAll(".column-header .label"); assertFalse(headers.isEmpty());
        for (Node node : headers) label("table.header", (Label) node, FontToken.BASE, true);
    }); }
    /** Непустой заголовок настоящего редактора правила использует HEADER. */
    @Test void dialogHeaderUsesActualHeaderFont() throws Exception { onFx(() -> {
        assertFalse(rule.header.getText().isEmpty()); label("dialog.header", rule.header, FontToken.HEADER, true);
    }); }
    /** Поля, денежный редактор, спиннер и строки скина выбора проверяются после CSS. */
    @Test void dialogFieldsUseActualBaseFont() throws Exception { onFx(() -> {
        int count = 0;
        for (var entry : rule.fields.entrySet()) if (entry.getValue().root.isManaged()) {
            Node control = entry.getValue().control;
            if (control instanceof TextInputControl text) { font("field." + entry.getKey(), text.getFont(), FontToken.BASE, false); count++; }
            else if (control instanceof Spinner<?> spinner) { font("field." + entry.getKey(), spinner.getEditor().getFont(), FontToken.BASE, false); count++; }
            else if (control instanceof ComboBox<?> combo) for (Node node : combo.lookupAll(".list-cell"))
                if (node instanceof Labeled labeled) { label("field." + entry.getKey(), labeled, FontToken.BASE, false); count++; }
        }
        assertTrue(count >= 5);
    }); }
    /** Настоящие строки расчёта ядра используют BASE, а не заранее ожидаемый font dump. */
    @Test void resultLinesUseActualBaseFont() throws Exception { onFx(() -> {
        assertFalse(goal.results.getChildren().isEmpty());
        for (Node node : goal.results.getChildren()) label("result", (Label) node, FontToken.BASE, false);
        assertTrue(Window.getWindows().stream().noneMatch(Window::isShowing));
    }); }
}
