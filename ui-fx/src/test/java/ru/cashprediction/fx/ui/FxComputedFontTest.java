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
    /** Все заголовки нативного скина используют обычный BASE; жирность баланса относится к ячейкам. */
    @Test void tableHeadersUseActualNormalBaseFont() throws Exception { onFx(() -> {
        var headers = port.main.table.root.lookupAll(".column-header .label"); assertFalse(headers.isEmpty());
        assertEquals(port.main.table.model.columns().size(), headers.size());
        for (Node node : headers) label("table.header", (Label) node, FontToken.BASE, false);
    }); }
    /** Вычисленное выравнивание каждого заголовка следует ColumnSpec, включая правые суммы. */
    @Test void tableHeadersFollowActualColumnAlignment() throws Exception { onFx(() -> {
        var table = port.main.table;
        for (var spec : table.model.columns()) {
            var header = table.root.lookupAll(".column-header").stream()
                    .filter(javafx.scene.control.skin.TableColumnHeader.class::isInstance)
                    .map(javafx.scene.control.skin.TableColumnHeader.class::cast)
                    .filter(h -> h.getTableColumn() == table.columns.get(spec.id())).findFirst().orElseThrow();
            var painted = assertInstanceOf(Label.class, header.lookup(".label"));
            assertEquals(switch (spec.align()) {
                case LEFT -> javafx.geometry.Pos.CENTER_LEFT;
                case CENTER -> javafx.geometry.Pos.CENTER;
                case RIGHT -> javafx.geometry.Pos.CENTER_RIGHT;
            }, painted.getAlignment(), spec.id());
            assertEquals(spec.title(), painted.getText(), spec.id());
        }
    }); }
    /** Настоящие ячейки продолжают применять ColumnSpec.bold независимо от обычного заголовка. */
    @Test void columnBoldStillAppliesToActualCells() throws Exception { onFx(() -> {
        var table = port.main.table;
        int index = java.util.stream.IntStream.range(0, table.model.rowCount())
                .filter(i -> !table.model.row(i).rowStyle().bold() && !table.model.row(i).rowStyle().italic()
                        && table.model.row(i).cellStyles().values().stream().noneMatch(s -> s.bold() || s.italic()))
                .findFirst().orElseThrow();
        for (var spec : table.model.columns()) {
            var column = table.columns.get(spec.id());
            var cell = column.getCellFactory().call(column);
            cell.updateTableView(table.root); cell.updateTableColumn(column); cell.updateIndex(index);
            var painted = assertInstanceOf(Text.class, cell.getProperties().get("cp.paintText"));
            font("table.cell." + spec.id(), painted.getFont(), FontToken.BASE, spec.bold());
        }
    }); }
    /** Скрытая сцена проверяет только исходный prompt; физический фокус проверяет FxPolishDesktopTest. */
    @Test void emptyFilterPromptUsesSharedTextAndColor() throws Exception { onFx(() -> {
        var field = assertInstanceOf(TextField.class, port.main.toolbar.widgets.get("tb.filter"));
        var model = port.main.model.toolbar().items().stream()
                .filter(ru.cashprediction.core.ui.menu.ToolbarNode.FilterField.class::isInstance)
                .map(ru.cashprediction.core.ui.menu.ToolbarNode.FilterField.class::cast).findFirst().orElseThrow();
        assertEquals(ru.cashprediction.core.ui.text.UiText.get("toolbar.tb.filter.prompt"), model.prompt());
        String original = field.getText();
        try {
            field.setText("");
            for (int viewport : List.of(1200, 900)) {
                css(port.main.root, viewport, 800);
                var prompt = field.lookupAll(".text").stream().filter(Text.class::isInstance).map(Text.class::cast)
                        .filter(t -> t.getText().equals(model.prompt())).findFirst().orElseThrow();
                assertEquals(model.prompt(), field.getPromptText()); assertTrue(prompt.isVisible());
                assertEquals(1, prompt.getOpacity());
                assertEquals(javafx.scene.paint.Color.web(ru.cashprediction.core.ui.token.ColorToken.TEXT_MUTED.hex()), prompt.getFill());
                font("filter.prompt", prompt.getFont(), FontToken.BASE, false);
                assertTrue(prompt.getBoundsInParent().getWidth() > 0);
                assertEquals(model.widthPx(), field.getWidth());
            }
            field.setText("filter-probe"); css(port.main.root, 1200, 800);
            assertTrue(field.lookupAll(".text").stream().filter(Text.class::isInstance).map(Text.class::cast)
                    .filter(t -> t.getText().equals(model.prompt())).noneMatch(Node::isVisible));
            var entered = field.lookupAll(".text").stream().filter(Text.class::isInstance).map(Text.class::cast)
                    .filter(t -> t.getText().equals(field.getText())).findFirst().orElseThrow();
            assertTrue(entered.isVisible());
            assertEquals(javafx.scene.paint.Color.web(ru.cashprediction.core.ui.token.ColorToken.TEXT_PRIMARY.hex()), entered.getFill());
        } finally {
            field.setText(original);
            css(port.main.root, 1200, 800);
        }
    }); }
    /** Общий PNG занимает ровно 16x16 в настоящих стрелках тулбара, сохраняя скин и обработчики. */
    @Test void toolbarDropdownsUseActualSixteenPixelSharedArt() throws Exception { onFx(() -> {
        for (String id : List.of("tb.add", "tb.period", "tb.whatIf")) {
            // JavaFX: MenuButton/ SplitMenuButton → Swing: JButton + JPopupMenu → Web: button + div[role=menu]
            var button = assertInstanceOf(MenuButton.class, port.main.toolbar.widgets.get(id));
            var skin = button.getSkin(); var action = button.getOnAction();
            var hit = assertInstanceOf(Region.class, button.lookup(".arrow-button"));
            var pressed = hit.getOnMousePressed(); var released = hit.getOnMouseReleased();
            FxIcons.skinGraphics(button); css(port.main.root, 1200, 800);
            var arrow = assertInstanceOf(Region.class, button.lookup(".arrow"));
            assertEquals(ru.cashprediction.core.ui.token.DesignTokens.INLINE_ICON_SIZE, arrow.getWidth(), id);
            assertEquals(ru.cashprediction.core.ui.token.DesignTokens.INLINE_ICON_SIZE, arrow.getHeight(), id);
            assertEquals("\u25be", arrow.getProperties().get("cp.icon"));
            assertEquals(1, arrow.getBackground().getImages().size());
            assertSharedPng(arrow.getBackground().getImages().getFirst().getImage(), "\u25be");
            FxPolishDesktopTest.paintedPng(arrow, "\u25be");
            FxPolishDesktopTest.contains(hit.localToScene(hit.getLayoutBounds()), arrow.localToScene(arrow.getLayoutBounds()));
            assertSame(skin, button.getSkin()); assertSame(action, button.getOnAction());
            assertSame(hit, button.lookup(".arrow-button"));
            assertSame(pressed, hit.getOnMousePressed()); assertSame(released, hit.getOnMouseReleased());
            assertTrue(hit.getWidth() >= arrow.getWidth());
            FxIcons.skinGraphics(button); css(port.main.root, 1200, 800);
            assertEquals(ru.cashprediction.core.ui.token.DesignTokens.INLINE_ICON_SIZE, arrow.getWidth(), id);
        }
    }); }
    /** Выпадающие поля и строка подменю используют тот же PNG и размер, что стрелки тулбара. */
    @Test void choiceAndSubmenuArrowsShareActualSixteenPixelGeometry() throws Exception { onFx(() -> {
        int choices = 0;
        for (var field : rule.fields.values()) if (field.root.isManaged() && field.control instanceof ComboBox<?> combo) {
            FxIcons.skinGraphics(combo); css(rule.dialog.getDialogPane(), rule.spec.width(), 700);
            var arrow = assertInstanceOf(Region.class, combo.lookup(".arrow"));
            assertEquals(ru.cashprediction.core.ui.token.DesignTokens.INLINE_ICON_SIZE, arrow.getWidth());
            assertEquals(ru.cashprediction.core.ui.token.DesignTokens.INLINE_ICON_SIZE, arrow.getHeight());
            assertSharedPng(arrow.getBackground().getImages().getFirst().getImage(), "\u25be");
            choices++;
            FxPolishDesktopTest.paintedPng(arrow, "\u25be");
            var hit = combo.lookup(".arrow-button"); assertNotNull(hit);
            FxPolishDesktopTest.contains(hit.localToScene(hit.getLayoutBounds()), arrow.localToScene(arrow.getLayoutBounds()));
        }
        assertTrue(choices > 0);
        var submenu = port.main.model.menuBar().menus().getFirst();
        // JavaFX: ContextMenu + ContextMenuSkin → Swing: JPopupMenu → Web: div[role=menu]
        var menu = port.menus.context(List.of(submenu), ru.cashprediction.core.ui.command.InvokeSource.MENU);
        menu.setSkin(new javafx.scene.control.skin.ContextMenuSkin(menu));
        var root = assertInstanceOf(Region.class, menu.getSkin().getNode());
        if (root.getScene() == null) new Scene(root);
        try {
            root.applyCss(); menu.getItems().forEach(port.menus::hooks);
            css(root, root.prefWidth(-1), root.prefHeight(root.prefWidth(-1)));
            var row = menu.getItems().getFirst().getStyleableNode(); assertNotNull(row);
            var arrow = assertInstanceOf(Region.class, row.lookup(".arrow"));
            assertEquals(ru.cashprediction.core.ui.token.DesignTokens.INLINE_ICON_SIZE, arrow.getWidth());
            assertEquals(ru.cashprediction.core.ui.token.DesignTokens.INLINE_ICON_SIZE, arrow.getHeight());
            assertEquals("\u25b8", arrow.getProperties().get("cp.icon"));
            assertSharedPng(arrow.getBackground().getImages().getFirst().getImage(), "\u25b8");
            FxPolishDesktopTest.paintedPng(arrow, "\u25b8");
            FxPolishDesktopTest.contains(row.localToScene(row.getLayoutBounds()), arrow.localToScene(arrow.getLayoutBounds()));
        } finally { menu.hide(); }
    }); }
    /** Одинаковые ширины и зазор общего токена измеряются в полном и неполном последнем ряду. */
    @Test void summaryCardsKeepEqualWidthAcrossEveryActualRow() throws Exception { onFx(() -> {
        var summary = port.main.summary;
        try {
            assertEquals(6, ru.cashprediction.core.ui.token.DesignTokens.CARD_GAP);
            assertEquals(ru.cashprediction.core.ui.token.DesignTokens.CARD_GAP, summary.getHgap());
            assertEquals(ru.cashprediction.core.ui.token.DesignTokens.CARD_GAP, summary.getVgap());
            assertEquals(9, summary.getChildren().size());
            for (int viewport : List.of(1200, 900, 1000, 1200)) {
                css(port.main.root, viewport, 800);
                var cards = summary.getChildren().stream().map(Region.class::cast).toList();
                var rows = new java.util.TreeMap<Double, List<Region>>();
                double width = cards.getFirst().getWidth();
                for (var card : cards) {
                    assertEquals(width, card.getWidth(), 0.001);
                    assertTrue(card.getWidth() >= ru.cashprediction.core.ui.token.DesignTokens.CARD_MIN_WIDTH);
                    assertTrue(card.getLayoutX() + card.getWidth() <= summary.getWidth() - summary.getInsets().getRight());
                    rows.computeIfAbsent(card.getLayoutY(), y -> new java.util.ArrayList<>()).add(card);
                }
                assertEquals(viewport == 1200 ? 1 : 2, rows.size());
                if (rows.size() > 1) assertTrue(rows.lastEntry().getValue().size() < rows.firstEntry().getValue().size());
                Region previousRow = null;
                for (var row : rows.values()) {
                    assertEquals(summary.getInsets().getLeft(), row.getFirst().getLayoutX(), 0.001);
                    if (previousRow != null) assertEquals(summary.getVgap(),
                            row.getFirst().getLayoutY() - previousRow.getLayoutY() - previousRow.getHeight(), 0.001);
                    for (int i = 1; i < row.size(); i++) assertEquals(summary.getHgap(),
                            row.get(i).getLayoutX() - row.get(i - 1).getLayoutX() - width, 0.001);
                    previousRow = row.getFirst();
                }
            }
        } finally { css(port.main.root, 1200, 800); }
    }); }

    /** Сравнивает все декодированные пиксели фактического фона с физическим PNG общего каталога. */
    private static void assertSharedPng(javafx.scene.image.Image actual, String key) {
        var expected = new javafx.scene.image.Image(new java.io.ByteArrayInputStream(
                ru.cashprediction.core.ui.token.UiIcons.png(key, ru.cashprediction.core.ui.token.ColorToken.TEXT_PRIMARY).orElseThrow()));
        assertFalse(actual.isError()); assertEquals(expected.getWidth(), actual.getWidth()); assertEquals(expected.getHeight(), actual.getHeight());
        for (int y = 0; y < expected.getHeight(); y++) for (int x = 0; x < expected.getWidth(); x++)
            assertEquals(expected.getPixelReader().getArgb(x, y), actual.getPixelReader().getArgb(x, y));
    }
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
