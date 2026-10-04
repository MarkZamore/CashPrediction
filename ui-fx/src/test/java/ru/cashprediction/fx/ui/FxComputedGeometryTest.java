package ru.cashprediction.fx.ui;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.alert.*;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.forms.ops.QuickEditForm;
import ru.cashprediction.core.ui.forms.plan.NewPlanWizardForm;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.token.DesignTokens;
import ru.cashprediction.core.ui.token.ColorToken;
import ru.cashprediction.core.ui.token.FontToken;
import static org.junit.jupiter.api.Assertions.*;

/** Изолированная проверка реальной раскладки CSS без видимых окон; наследует уже работающий нативный стенд. */
@EnabledIfSystemProperty(named = "fx.geometryProof", matches = "true")
class FxComputedGeometryTest extends FxComputedFontTest {
    /** Повторное использование настоящей ячейки удаляет старый текст доступности и графику. */
    @Test void recycledTableCellClearsActualAccessibility() throws Exception { onFx(() -> {
        var table = port.main.table;
        var column = table.columns.values().iterator().next();
        var cell = column.getCellFactory().call(column);
        cell.updateTableView(table.root); cell.updateTableColumn(column);
        cell.updateIndex(0);
        String logical = table.model.row(0).cells().getFirst();
        assertFalse(logical.isEmpty()); assertEquals(logical, cell.getAccessibleText());
        assertEquals(logical, cell.getProperties().get("cp.logicalText")); assertNotNull(cell.getGraphic());
        cell.updateIndex(-1);
        assertTrue(cell.isEmpty()); assertNull(cell.getAccessibleText()); assertNull(cell.getGraphic());
        assertNull(cell.getTooltip()); assertFalse(cell.getProperties().containsKey("cp.logicalText"));
        assertFalse(cell.getProperties().containsKey("cp.paintText"));
        cell.updateIndex(0);
        assertEquals(logical, cell.getAccessibleText()); assertNotNull(cell.getGraphic());
    }); }

    /** Каждый физический переключатель формы и его обновлённая отметка используют общий PNG. */
    @Test void formRadioButtonsUseSharedSkinWhenOptionsAreRebuilt() throws Exception { onFx(() -> {
        var controller = (AppController) port.intents;
        var wizard = form(new ru.cashprediction.core.ui.forms.plan.PlanSettingsForm(), WindowType.PLAN_SETTINGS,
                "icons-radio", Map.of(), controller);
        var field = wizard.physicalFields.stream().filter(f -> f.spec.kind() == FieldKind.RADIO).findFirst().orElseThrow();
        for (var radio : field.radioButtons()) {
            assertEquals(true, radio.getProperties().get("cp.iconSkin"));
            radio.setSelected(true); FxIcons.skinGraphics(radio); radio.applyCss();
            var dot = radio.lookup(".dot"); assertNotNull(dot);
            assertEquals("\u25cf", dot.getProperties().get("cp.icon"));
            assertTrue(dot.getStyle().contains(FxIcons.imageCss("\u25cf", ColorToken.ACCENT)));
            radio.setSelected(false); assertTrue(dot.getStyle().contains("-fx-background-image: none;"));
        }
        var replacement = List.of(Option.of("replacement", field.spec.options().getFirst().text()));
        field.update(new FieldView("replacement", true, true, false, null, replacement, null), null);
        css(wizard.dialog.getDialogPane(), wizard.spec.width(), 700);
        var rebuilt = field.radioButtons().getFirst();
        assertEquals(true, rebuilt.getProperties().get("cp.iconSkin")); assertTrue(rebuilt.isSelected());
        FxIcons.skinGraphics(rebuilt);
        assertEquals("\u25cf", rebuilt.lookup(".dot").getProperties().get("cp.icon"));
    }); }

    /** Цвет подписи меняет общий PNG, но сохраняет исходный текст и размеры по спецификации. */
    @Test void sharedInlineIconsFollowActualTextColorAndKeepLogicalText() throws Exception { onFx(() -> {
        Label label = new Label("\u26a0 Problem"); FxIcons.decorate(label);
        for (ColorToken token : List.of(ColorToken.ACCENT, ColorToken.WHATIF, ColorToken.EXPENSE,
                ColorToken.INCOME, ColorToken.TEXT_PRIMARY, ColorToken.TEXT_MUTED,
                ColorToken.WARN, ColorToken.TOOLTIP_TEXT, ColorToken.TEXT_PAST)) {
            FxStyles.text(label, token, FontToken.SMALL);
            var flow = assertInstanceOf(javafx.scene.text.TextFlow.class, label.getGraphic());
            var icon = assertInstanceOf(javafx.scene.image.ImageView.class, flow.getChildren().getFirst());
            assertSame(FxIcons.image("\u26a0", token).orElseThrow(), icon.getImage());
            assertEquals(DesignTokens.INLINE_ICON_SIZE, icon.getFitWidth());
            assertEquals(DesignTokens.INLINE_ICON_SIZE, icon.getFitHeight());
            assertEquals("\u26a0 Problem", label.getText()); assertEquals(label.getText(), label.getAccessibleText());
        }
        var wizard = form(new NewPlanWizardForm(), WindowType.NEW_PLAN_WIZARD, "icons-header", Map.of(), (AppController) port.intents);
        var header = assertInstanceOf(javafx.scene.image.ImageView.class, wizard.glyph.getGraphic());
        assertEquals(DesignTokens.DIALOG_ICON_SIZE, header.getFitWidth());
        assertSame(FxIcons.image(wizard.spec.glyph(), ColorToken.ACCENT).orElseThrow(), header.getImage());
        // JavaFX: Tooltip → Swing: JToolTip → Web: div.tooltip
        var tip = FxStyles.tip("\u26a0 Problem", port.probe);
        var painted = assertInstanceOf(Label.class, tip.getGraphic());
        var flow = assertInstanceOf(javafx.scene.text.TextFlow.class, painted.getGraphic());
        var icon = assertInstanceOf(javafx.scene.image.ImageView.class, flow.getChildren().getFirst());
        assertSame(FxIcons.image("\u26a0", ColorToken.TOOLTIP_TEXT).orElseThrow(), icon.getImage());
        assertEquals("\u26a0 Problem", tip.getText());
    }); }

    /** Настоящий Skin контекстного меню использует строки 28, разделитель 9 и отдельную CSS-сцену. */
    @Test void menuSkinMeasuresRowsAndSeparatorWithoutShowingWindow() throws Exception { onFx(() -> {
        var file = (ru.cashprediction.core.ui.menu.MenuNode.Submenu) port.main.model.menuBar().menus().getFirst();
        var menu = port.menus.context(file.children(), ru.cashprediction.core.ui.command.InvokeSource.MENU);
        menu.setSkin(new javafx.scene.control.skin.ContextMenuSkin(menu));
        var root = (Region) menu.getSkin().getNode();
        if (root.getScene() == null) new Scene(root);
        menu.getItems().forEach(port.menus::hooks);
        root.applyCss(); css(root, root.prefWidth(-1), root.prefHeight(root.prefWidth(-1)));
        for (var item : menu.getItems()) {
            if (item instanceof SeparatorMenuItem separator) {
                assertEquals(2 * DesignTokens.SPACING + 1, separator.getContent().getLayoutBounds().getHeight());
            } else if (!(item instanceof CustomMenuItem)) {
                assertNotNull(item.getStyleableNode());
                assertEquals(DesignTokens.CONTROL_HEIGHT, item.getStyleableNode().getLayoutBounds().getHeight());
                for (Node node : item.getStyleableNode().lookupAll(".label")) if (node instanceof Label label)
                    assertEquals(FontToken.BASE.sizePx(), label.getFont().getSize());
            }
        }
        assertTrue(javafx.stage.Window.getWindows().stream().noneMatch(javafx.stage.Window::isShowing));
        menu.hide();
    }); }
    /** Легенда располагается внутри верхнего поля сцены и не сдвигает начало Canvas. */
    @Test void chartLegendDoesNotCreateAnotherCanvasOrigin() throws Exception { onFx(() -> {
        var controller = (AppController) port.intents;
        controller.command(ru.cashprediction.core.ui.command.CommandId.VIEW_CHART,
                ru.cashprediction.core.ui.command.CommandArgs.NONE, ru.cashprediction.core.ui.command.InvokeSource.MAIN);
        css(port.main.root, 1200, 800);
        try {
            var chartBox = port.main.root.getCenter();
            var box = chartBox.localToScene(chartBox.getLayoutBounds());
            var canvas = port.main.chart.localToScene(port.main.chart.getLayoutBounds());
            assertEquals(box.getMinY(), canvas.getMinY(), 0.001);
            assertTrue(port.main.legend.getHeight() <= DesignTokens.CHART_MARGIN_TOP);
            assertEquals(port.main.chartScene.legend().size(), port.main.legend.getChildren().size());
            for (int i = 0; i < port.main.chartScene.legend().size(); i++) {
                var item = port.main.chartScene.legend().get(i);
                var label = (Label) port.main.legend.getChildren().get(i);
                assertEquals(item.text(), label.getText());
                assertEquals(FontToken.LEGEND.sizePx(), label.getFont().getSize());
                assertEquals(javafx.scene.paint.Color.web(ColorToken.TEXT_MUTED.hex()), label.getTextFill());
                var parameters = new javafx.scene.SnapshotParameters(); parameters.setFill(javafx.scene.paint.Color.TRANSPARENT);
                assertTrue(paintedPixels(label.snapshot(parameters, null), javafx.scene.paint.Color.web(ColorToken.TEXT_MUTED.hex())) > 5,
                        "actual legend text " + item.id());
                if (item.swatch() != ru.cashprediction.core.ui.view.chart.LegendItem.Swatch.NONE) {
                    var sample = assertInstanceOf(javafx.scene.canvas.Canvas.class, label.getGraphic());
                    assertTrue(paintedPixels(sample.snapshot(parameters, null), javafx.scene.paint.Color.web(item.color().hex())) > 5,
                            "actual legend swatch " + item.id());
                }
            }
            String legendImage = System.getProperty("fx.legendProofPng");
            if (legendImage != null) try {
                java.nio.file.Files.write(java.nio.file.Path.of(legendImage),
                        ru.cashprediction.fx.ui.PngEncoder.encode(port.main.chartPane.snapshot(null, null)));
            } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            System.out.println("GEOMETRY_NATIVE chart origin=" + canvas.getMinY() + " centerOrigin=" + box.getMinY()
                    + " legend=" + port.main.legend.getHeight());
        } finally {
            controller.command(ru.cashprediction.core.ui.command.CommandId.VIEW_TABLE,
                    ru.cashprediction.core.ui.command.CommandArgs.NONE, ru.cashprediction.core.ui.command.InvokeSource.MAIN);
            css(port.main.root, 1200, 800);
        }
    }); }

    /** Действие настоящего узла отменяет отложенный hover карточки до создания PopupControl. */
    @Test void actualActionCancelsPendingCardHover() throws Exception {
        var before = new java.util.concurrent.atomic.AtomicInteger();
        onFx(() -> {
            port.hideSpark(); before.set(port.probe.snapshot().getOrDefault("PopupControl", 0));
            var card = port.main.summary.getChildren().getFirst();
            card.fireEvent(new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_ENTERED,
                    1, 1, 1, 1, javafx.scene.input.MouseButton.NONE, 0, false, false, false, false,
                    false, false, false, false, false, false, null));
            card.fireEvent(new javafx.event.ActionEvent());
        });
        Thread.sleep(400);
        onFx(() -> { assertEquals(before.get(), port.probe.snapshot().getOrDefault("PopupControl", 0)); assertTrue(javafx.stage.Window.getWindows().stream().noneMatch(javafx.stage.Window::isShowing)); });
    }

    /** Пустая шапка одно-полевой модели не резервирует высоту пустого значка. */
    @Test void simpleModelsHaveNoPhantomHeader() throws Exception { onFx(() -> {
        var controller = (AppController) port.intents;
        var logic = ru.cashprediction.core.ui.forms.simple.ChoiceForms.currency();
        var choice = form(logic, WindowType.CHOICE, "geometry-currency", Map.of(), controller);
        var rename = form(ru.cashprediction.core.ui.forms.simple.TextInputForms.rename(), WindowType.TEXT_INPUT,
                "geometry-rename", Map.of(), controller);
        for (var dialog : List.of(choice, rename)) {
            var pane = dialog.dialog.getDialogPane(); double height = pane.prefHeight(dialog.spec.width());
            css(pane, dialog.spec.width(), height);
            assertNull(pane.getHeader()); assertEquals(DesignTokens.CONTROL_HEIGHT, dialog.fields.get("value").control.getLayoutBounds().getHeight());
            assertEquals(8 * DesignTokens.SPACING, dialog.problem.getHeight());
            String metrics = System.getProperty("fx.form.metrics");
            try { System.setProperty("fx.form.metrics", "true"); assertDoesNotThrow(() -> dialog.printLayoutMetrics("simple")); }
            finally { if (metrics == null) System.clearProperty("fx.form.metrics"); else System.setProperty("fx.form.metrics", metrics); }
            System.out.println("GEOMETRY_NATIVE simple " + dialog.spec.purpose() + " pane=" + pane.getWidth() + "x" + pane.getHeight());
        }
    }); }

    /** Спарклайн и карточка дня используют реальные шрифты и CSS, без модели фиктивного дампа. */
    @Test void hoverContentUsesSpecFontsAndMeasuredCanvas() throws Exception { onFx(() -> {
        var spark = FxUiPort.sparkContent(port.intents.sparkline("now")); new Scene(spark);
        spark.applyCss(); double width = spark.prefWidth(-1), height = spark.prefHeight(width); css(spark, width, height);
        label("spark.header", (Label) spark.getChildren().get(0), FontToken.HEADER, true);
        label("spark.explanation", (Label) spark.getChildren().get(1), FontToken.SMALL, false);
        var canvas = (javafx.scene.canvas.Canvas) spark.getChildren().get(2);
        assertEquals(DesignTokens.SPARK_WIDTH, canvas.getWidth()); assertEquals(DesignTokens.SPARK_HEIGHT, canvas.getHeight());
        var footer = (HBox) spark.getChildren().getLast();
        for (Node node : spark.getChildren()) System.out.println("SPARK_COMPONENT " + node.getClass().getSimpleName()
                + " box=" + node.getLayoutBounds() + " y=" + node.getLayoutY()
                + (node instanceof Label value ? " font=" + value.getFont() + " lineSpacing=" + value.getLineSpacing() : ""));
        System.out.println("SPARK_LAYOUT insets=" + spark.getInsets() + " gap=" + spark.getSpacing());
        for (Node node : footer.getChildren()) if (node instanceof Label label) assertEquals(10, label.getFont().getSize());
        assertEquals(DesignTokens.SPARK_CONTENT_GAP, spark.getSpacing());
        assertSparkLines((FxLineHeightLabel) spark.getChildren().get(0), DesignTokens.SPARK_HEADER_LINE_HEIGHT);
        assertSparkLines((FxLineHeightLabel) spark.getChildren().get(1), DesignTokens.SPARK_BODY_LINE_HEIGHT);
        for (Node node : footer.getChildren()) if (node instanceof FxLineHeightLabel label)
            assertSparkLines(label, DesignTokens.SPARK_FOOTER_LINE_HEIGHT);
        assertSparkRightEdge((FxLineHeightLabel) footer.getChildren().getLast());
        sparkProofImage(spark, "normal");
        assertEquals(javafx.scene.paint.Color.web(ColorToken.BORDER_STRONG.hex()), spark.getBorder().getStrokes().getFirst().getTopStroke());
        assertInstanceOf(javafx.scene.effect.DropShadow.class, spark.getEffect());
        // Настоящий PopupControl имеет служебную обёртку; измеряем тот узел Skin, который рисует карточку.
        port.spark(port.main.summary.getChildren().getFirst(), "now");
        javafx.scene.control.PopupControl actualPopup;
        try {
            var popupField = FxUiPort.class.getDeclaredField("sparkPopup"); popupField.setAccessible(true);
            actualPopup = (javafx.scene.control.PopupControl) popupField.get(port);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        assertSame(actualPopup.getSkin().getNode(), FxUiDumper.popupContent(actualPopup));
        var tip = FxStyles.tip(UiText.get("quick.hint"), port.probe);
        assertSame(tip.getScene().getRoot(), FxUiDumper.popupContent(tip));
        System.out.println("GEOMETRY_NATIVE spark=" + width + "x" + height + " explanation=" + spark.getChildren().get(1).getLayoutBounds().getHeight());
        var hover = port.intents.chartHover(port.main.model.chart().revision(), 300, 200, 1200, 628).orElseThrow();
        var day = FxUiPort.dayContent(hover.card()); new Scene(day); day.applyCss(); css(day, day.prefWidth(-1), day.prefHeight(day.prefWidth(-1)));
        label("day.header", (Label) day.getChildren().getFirst(), FontToken.HEADER, true);
        assertTrue(day.getWidth() >= DesignTokens.DAY_CARD_MIN_WIDTH); assertTrue(day.getWidth() <= DesignTokens.DAY_CARD_MAX_WIDTH);
        assertTrue(javafx.stage.Window.getWindows().stream().noneMatch(javafx.stage.Window::isShowing));
    }); }

    /** Длинные и многострочные подписи растут по настоящему переносу; отсутствие данных заменяет Canvas текстом. */
    @Test void sparkLineBoxesGrowForWrappedLabelsAndNoData() throws Exception { onFx(() -> {
        var base = port.intents.sparkline("now");
        var longModel = new ru.cashprediction.core.ui.view.popup.SparklineModel(base.cardId(),
                base.header() + "\n" + base.header().repeat(4), base.explanation().repeat(3), base.points(),
                base.zeroY(), base.marker(), base.minText().repeat(5), base.maxText().repeat(5), "");
        var longSpark = FxUiPort.sparkContent(longModel); new Scene(longSpark); longSpark.applyCss();
        css(longSpark, longSpark.prefWidth(-1), longSpark.prefHeight(longSpark.prefWidth(-1)));
        int headerLines = assertSparkLines((FxLineHeightLabel) longSpark.getChildren().get(0), DesignTokens.SPARK_HEADER_LINE_HEIGHT);
        int bodyLines = assertSparkLines((FxLineHeightLabel) longSpark.getChildren().get(1), DesignTokens.SPARK_BODY_LINE_HEIGHT);
        assertTrue(headerLines >= 3); assertTrue(bodyLines > 2);
        var footer = (HBox) longSpark.getChildren().getLast();
        for (Node node : footer.getChildren()) if (node instanceof FxLineHeightLabel label) {
            assertTrue(assertSparkLines(label, DesignTokens.SPARK_FOOTER_LINE_HEIGHT) > 1);
            assertTrue(label.getWidth() <= (DesignTokens.SPARK_WIDTH - 2 * footer.getSpacing()) / 2);
        }
        assertSparkRightEdge((FxLineHeightLabel) footer.getChildren().getLast());
        assertEquals(DesignTokens.SPARK_WIDTH + longSpark.getInsets().getLeft() + longSpark.getInsets().getRight(), longSpark.getWidth());
        double childrenHeight = longSpark.getChildren().stream().mapToDouble(node -> node.getLayoutBounds().getHeight()).sum();
        assertEquals(childrenHeight + longSpark.getInsets().getTop() + longSpark.getInsets().getBottom(), longSpark.getHeight());
        var noDataModel = new ru.cashprediction.core.ui.view.popup.SparklineModel(base.cardId(), base.header(), base.explanation(),
                List.of(), null, null, "", "", UiText.get("spark.noData"));
        var noData = FxUiPort.sparkContent(noDataModel); new Scene(noData); noData.applyCss();
        css(noData, noData.prefWidth(-1), noData.prefHeight(noData.prefWidth(-1)));
        assertEquals(3, noData.getChildren().size());
        assertTrue(noData.getChildren().stream().noneMatch(node -> node instanceof javafx.scene.canvas.Canvas || node instanceof HBox));
        var message = (FxLineHeightLabel) noData.getChildren().getLast(); assertEquals(UiText.get("spark.noData"), message.getText());
        label("spark.noData", message, FontToken.SMALL, false);
        assertEquals(1, assertSparkLines(message, DesignTokens.SPARK_BODY_LINE_HEIGHT));
        var onePointModel = new ru.cashprediction.core.ui.view.popup.SparklineModel(base.cardId(), base.header(), base.explanation(),
                List.of(base.points().getFirst()), null, null, "", "", UiText.get("spark.noData"));
        var onePoint = FxUiPort.sparkContent(onePointModel); new Scene(onePoint); onePoint.applyCss();
        css(onePoint, onePoint.prefWidth(-1), onePoint.prefHeight(onePoint.prefWidth(-1)));
        assertEquals(noData.getWidth(), onePoint.getWidth()); assertEquals(noData.getHeight(), onePoint.getHeight());
        assertTrue(onePoint.getChildren().stream().noneMatch(node -> node instanceof javafx.scene.canvas.Canvas));
        var onePointMessage = (FxLineHeightLabel) onePoint.getChildren().getLast();
        label("spark.onePoint.noData", onePointMessage, FontToken.SMALL, false);
        assertEquals(1, assertSparkLines(onePointMessage, DesignTokens.SPARK_BODY_LINE_HEIGHT));
        onePointMessage.setText((UiText.get("spark.noData") + " ").repeat(30).stripTrailing());
        css(onePoint, onePoint.prefWidth(-1), onePoint.prefHeight(onePoint.prefWidth(-1)));
        int noDataLines = assertSparkLines(onePointMessage, DesignTokens.SPARK_BODY_LINE_HEIGHT);
        assertTrue(noDataLines > 1);
        assertEquals(noData.getHeight() + (noDataLines - 1) * DesignTokens.SPARK_BODY_LINE_HEIGHT, onePoint.getHeight());
        sparkProofImage(longSpark, "wrapped"); sparkProofImage(noData, "no-data");
        sparkProofImage(onePoint, "one-point-wrapped-no-data");
        System.out.println("SPARK_SHARED_LINES long=" + longSpark.getWidth() + "x" + longSpark.getHeight()
                + " headerLines=" + headerLines + " bodyLines=" + bodyLines + " noData=" + noData.getWidth() + "x" + noData.getHeight()
                + " onePointWrapped=" + onePoint.getWidth() + "x" + onePoint.getHeight() + " noDataLines=" + noDataLines);
        assertTrue(javafx.stage.Window.getWindows().stream().noneMatch(javafx.stage.Window::isShowing));
    }); }

    /** Смена ширины и длинное слово заново переносят настоящую краску, не оставляя высоту прежней строки. */
    @Test void sparkTextReflowsAfterWidthAndContentChange() throws Exception { onFx(() -> {
        var sample = port.intents.sparkline("now");
        var label = new FxLineHeightLabel(sample.explanation(), DesignTokens.SPARK_BODY_LINE_HEIGHT);
        FxStyles.text(label, ColorToken.TEXT_MUTED, FontToken.SMALL);
        var root = new VBox(label); FxStyles.root(root); new Scene(root);
        label.setPrefWidth(DesignTokens.SPARK_WIDTH); label.setMaxWidth(DesignTokens.SPARK_WIDTH);
        css(root, DesignTokens.SPARK_WIDTH, root.prefHeight(DesignTokens.SPARK_WIDTH));
        int originalLines = assertSparkLines(label, DesignTokens.SPARK_BODY_LINE_HEIGHT);
        label.setPrefWidth(DesignTokens.SPARK_WIDTH / 2); label.setMaxWidth(DesignTokens.SPARK_WIDTH / 2);
        css(root, DesignTokens.SPARK_WIDTH / 2, root.prefHeight(DesignTokens.SPARK_WIDTH / 2));
        assertTrue(assertSparkLines(label, DesignTokens.SPARK_BODY_LINE_HEIGHT) > originalLines);
        label.setText(sample.explanation().replace(" ", "").repeat(2));
        css(root, DesignTokens.SPARK_WIDTH / 2, root.prefHeight(DesignTokens.SPARK_WIDTH / 2));
        assertTrue(assertSparkLines(label, DesignTokens.SPARK_BODY_LINE_HEIGHT) > 2);
        label.setText(sample.header());
        css(root, DesignTokens.SPARK_WIDTH / 2, root.prefHeight(DesignTokens.SPARK_WIDTH / 2));
        assertSparkLines(label, DesignTokens.SPARK_BODY_LINE_HEIGHT);
        assertEquals(sample.header(), ((javafx.scene.text.Text) label.lookup(".text")).getText());
    }); }

    /** Сохраняет краску настоящего скрытого узла; файл явно не является захватом видимого окна. */
    private static void sparkProofImage(VBox content, String name) {
        String directory = System.getProperty("fx.sparkProofDir"); if (directory == null) return;
        try {
            java.nio.file.Files.write(java.nio.file.Path.of(directory, "hidden-spark-" + name + ".png"),
                    ru.cashprediction.fx.ui.PngEncoder.encode(content.snapshot(null, null)));
        } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
    }

    /** Считает строки независимо по геометрии каретки реально нарисованного Text, а не по ожидаемой высоте окна. */
    private static int assertSparkLines(FxLineHeightLabel label, int lineHeight) {
        var text = (javafx.scene.text.Text) label.lookup(".text"); assertNotNull(text);
        assertEquals(label.getFont(), text.getFont());
        var tops = new java.util.TreeSet<Double>();
        for (int i = 0; i <= text.getText().length(); i++) {
            var caret = text.caretShape(i, true);
            if (caret.length > 0) tops.add(((javafx.scene.shape.MoveTo) caret[0]).getY());
        }
        if (text.getText().isEmpty()) tops.clear();
        Double previous = null;
        for (double top : tops) { if (previous != null) assertEquals(lineHeight, top - previous, 0.001); previous = top; }
        assertEquals(tops.size() * lineHeight, label.getHeight(), 0.001);
        var painted = text.getBoundsInParent();
        assertTrue(painted.getMinY() >= -0.001); assertTrue(painted.getMaxY() <= label.getHeight() + 0.001);
        assertTrue(painted.getMaxX() <= label.getWidth() + 0.001);
        return tops.size();
    }

    /** Последний нарисованный глиф max-footer действительно заканчивается у правого края, даже после переноса. */
    private static void assertSparkRightEdge(FxLineHeightLabel label) {
        assertEquals(javafx.geometry.Pos.TOP_RIGHT, label.getAlignment());
        var text = (javafx.scene.text.Text) label.lookup(".text");
        assertEquals(javafx.scene.text.TextAlignment.RIGHT, text.getTextAlignment());
        var caret = text.caretShape(text.getText().length(), true);
        double right = ((javafx.scene.shape.MoveTo) caret[0]).getX() + text.getLayoutX();
        assertEquals(label.getWidth(), right, 0.001);
    }

    /** Считает реальные непрозрачные пиксели заданной краски, допуская только сглаживание на краях. */
    private static int paintedPixels(javafx.scene.image.Image image, javafx.scene.paint.Color expected) {
        int count = 0; var reader = image.getPixelReader();
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            var actual = reader.getColor(x, y);
            if (actual.getOpacity() > 0.8 && Math.abs(actual.getRed() - expected.getRed()) < 0.025
                    && Math.abs(actual.getGreen() - expected.getGreen()) < 0.025
                    && Math.abs(actual.getBlue() - expected.getBlue()) < 0.025) count++;
        }
        return count;
    }

    /** Ближайшая прокрутка оставляет видимую строку на месте и подводит дальнюю к нижнему краю viewport. */
    @Test void nearestRevealKeepsVisibleRowsAndUsesActualViewport() throws Exception { onFx(() -> {
        css(port.main.root, 1200, 800);
        var table = port.main.table;
        var flow = (javafx.scene.control.skin.VirtualFlow<?>) table.root.lookup(".virtual-flow");
        assertNotNull(flow); var first = flow.getFirstVisibleCell(); assertNotNull(first);
        int initialFirstIndex = first.getIndex(), visibleIndex = initialFirstIndex + 2;
        var visibleCell = flow.getVisibleCell(visibleIndex); assertNotNull(visibleCell);
        double before = visibleCell.localToScene(visibleCell.getLayoutBounds()).getMinY();
        table.ensureVisible(visibleIndex);
        assertEquals(before, flow.getVisibleCell(visibleIndex).localToScene(visibleCell.getLayoutBounds()).getMinY(), 0.001);
        int distantIndex = Math.min(table.model.rowCount() - 1, visibleIndex + 40);
        table.ensureVisible(distantIndex); css(port.main.root, 1200, 800);
        var distant = flow.getVisibleCell(distantIndex); assertNotNull(distant);
        var viewport = flow.lookup(".clipped-container");
        var viewportBox = viewport.localToScene(viewport.getLayoutBounds());
        var rowBox = distant.localToScene(distant.getLayoutBounds());
        assertEquals(viewportBox.getMaxY(), rowBox.getMaxY(), 1);
        table.render(table.model); css(port.main.root, 1200, 800);
        var afterRefresh = flow.getVisibleCell(distantIndex); assertNotNull(afterRefresh);
        assertEquals(rowBox.getMinY(), afterRefresh.localToScene(afterRefresh.getLayoutBounds()).getMinY(), 1);
        table.ensureVisible(initialFirstIndex); css(port.main.root, 1200, 800);
        var above = flow.getVisibleCell(initialFirstIndex); assertNotNull(above);
        assertEquals(viewportBox.getMinY(), above.localToScene(above.getLayoutBounds()).getMinY(), 1);
        int selected = table.root.getSelectionModel().getSelectedIndex();
        table.reveal(table.model.row(visibleIndex).rowId(), RevealMode.SCROLL_TO_TOP); css(port.main.root, 1200, 800);
        assertEquals(selected, table.root.getSelectionModel().getSelectedIndex());
        assertEquals(visibleIndex, flow.getFirstVisibleCell().getIndex());
        var topRow = flow.getVisibleCell(visibleIndex);
        assertEquals(viewportBox.getMinY(), topRow.localToScene(topRow.getLayoutBounds()).getMinY(), 0.001);
        System.out.println("GEOMETRY_NATIVE nearest visibleY=" + before + " distantBottom=" + rowBox.getMaxY()
                + " viewport=" + viewportBox + " explicitTop=" + flow.getFirstVisibleCell().getIndex());
    }); }

    /** Явный TOP устраняет частично обрезанную строку, сохраняя выделение до и после обновления данных. */
    @Test void explicitTopAlignsActualRowEdgeAfterFractionalScrollAndRefresh() throws Exception { onFx(() -> {
        css(port.main.root, 1200, 800);
        var table = port.main.table;
        var flow = (javafx.scene.control.skin.VirtualFlow<?>) table.root.lookup(".virtual-flow");
        table.reveal(table.model.row(8).rowId(), RevealMode.SCROLL_TO_TOP); css(port.main.root, 1200, 800);
        flow.scrollPixels(7); css(port.main.root, 1200, 800);
        var viewport = flow.lookup(".clipped-container");
        double viewportTop = viewport.localToScene(viewport.getLayoutBounds()).getMinY();
        var before = flow.getVisibleCell(8); assertNotNull(before);
        double clippedTop = before.localToScene(before.getLayoutBounds()).getMinY();
        assertTrue(clippedTop < viewportTop);
        int selected = table.root.getSelectionModel().getSelectedIndex();
        table.render(table.model); css(port.main.root, 1200, 800);
        table.reveal(table.model.row(8).rowId(), RevealMode.SCROLL_TO_TOP); css(port.main.root, 1200, 800);
        var after = flow.getVisibleCell(8); assertNotNull(after);
        double alignedTop = after.localToScene(after.getLayoutBounds()).getMinY();
        assertEquals(viewportTop, alignedTop, 0.001);
        assertEquals(selected, table.root.getSelectionModel().getSelectedIndex());
        System.out.println("GEOMETRY_NATIVE explicitTop before=" + clippedTop + " after=" + alignedTop + " viewportTop=" + viewportTop);
    }); }

    /** Кнопки пустого плана идут горизонтально и сохраняют порядок модели. */
    @Test void emptyPlanActionsShareOneActualRow() throws Exception { onFx(() -> {
        var controller = (AppController) port.intents;
        var previous = controller.document().plan();
        try {
            controller.document().replace(Plan.empty(previous.name(), LocalDate.of(2026, 9, 13)), null, true, List.of());
            controller.refresh(); css(port.main.root, 1200, 800);
            var placeholder = (VBox) port.main.table.root.getPlaceholder();
            var row = (HBox) placeholder.getChildren().get(1); css(placeholder, 1200, 300);
            assertEquals(3, row.getChildren().size());
            double previousRight = -1, firstY = row.getChildren().getFirst().getLayoutY();
            for (Node node : row.getChildren()) {
                assertEquals(firstY, node.getLayoutY()); assertTrue(node.getLayoutX() > previousRight);
                previousRight = node.getLayoutX() + node.getLayoutBounds().getWidth();
                assertEquals(DesignTokens.CONTROL_HEIGHT, node.getLayoutBounds().getHeight());
            }
            System.out.println("GEOMETRY_NATIVE placeholder actions=" + row.getWidth() + "x" + row.getHeight());
        } finally { controller.document().replace(previous, null, true, List.of()); controller.refresh(); css(port.main.root, 1200, 800); }
    }); }

    /** Общая ширина POPUP и ширина его редактора приходят из двух независимых контрактов ядра. */
    @Test void popupAndFieldUseIndependentContractWidths() throws Exception { onFx(() -> {
        var controller = (AppController) port.intents; var logic = new QuickEditForm();
        var rule = controller.document().plan().rules().getFirst();
        var context = new FormContext("geometry-quick", "main", Map.of("ruleId", rule.id().value(), "originalDate", "2026-10-05"), controller.state());
        var host = (FormSession.Host) Proxy.newProxyInstance(FormSession.Host.class.getClassLoader(), new Class<?>[]{FormSession.Host.class},
                (proxy, method, args) -> { throw new AssertionError(method.getName()); });
        var session = new FormSession(WindowType.QUICK_EDIT_POPUP, false, logic, context, host);
        var view = logic.evaluate(new FormState(0, Map.of("amount", "0")), context);
        var popup = new FxQuickEditPopup(session, logic.spec(context), view, Placement.centered("main"), port); popup.close();
        popup.popup.getScene().getRoot().applyCss(); popup.content.applyCss();
        double width = popup.content.prefWidth(-1), height = popup.content.prefHeight(width); css(popup.content, width, height);
        assertEquals(javafx.stage.PopupWindow.AnchorLocation.CONTENT_TOP_LEFT, popup.popup.getAnchorLocation());
        popup.popup.setAnchorX(700); popup.popup.setAnchorY(300);
        var realContentOrigin = popup.content.localToScene(0, 0);
        assertEquals(700, popup.popup.getX() + realContentOrigin.getX(), 0.001);
        assertEquals(300, popup.popup.getY() + realContentOrigin.getY(), 0.001);
        // Проверяем результат CSS именно сцены Popup, а не строку стиля или модель дампа.
        assertFalse(popup.content.getStylesheets().isEmpty());
        var background = popup.content.getBackground().getFills().getFirst();
        assertEquals(javafx.scene.paint.Color.web(ColorToken.BG_SURFACE.hex()), background.getFill());
        assertEquals(DesignTokens.QUICK_EDIT_RADIUS, background.getRadii().getTopLeftHorizontalRadius());
        var border = popup.content.getBorder().getStrokes().getFirst();
        assertEquals(javafx.scene.paint.Color.web(ColorToken.BORDER_STRONG.hex()), border.getTopStroke());
        assertEquals(1, border.getWidths().getTop());
        assertEquals(DesignTokens.QUICK_EDIT_RADIUS, border.getRadii().getTopLeftHorizontalRadius());
        var shadow = assertInstanceOf(javafx.scene.effect.DropShadow.class, popup.content.getEffect());
        assertEquals(DesignTokens.SHADOW_BLUR, shadow.getRadius());
        assertEquals(0, shadow.getOffsetX()); assertEquals(DesignTokens.SHADOW_OFFSET_Y, shadow.getOffsetY());
        assertEquals((double) (float) ColorToken.SHADOW.opacity(), shadow.getColor().getOpacity());
        System.out.println("POPUP_COMPUTED_STYLE background=" + background + " border=" + border
                + " shadowRadius=" + shadow.getRadius() + " shadowY=" + shadow.getOffsetY() + " shadowColor=" + shadow.getColor());
        assertEquals(DesignTokens.QUICK_EDIT_FIELD_WIDTH, popup.fields.get("amount").control.getLayoutBounds().getWidth());
        assertEquals(logic.spec(context).width(), width);
        assertTrue(popup.header.getWidth() <= width - popup.content.getPadding().getLeft() - popup.content.getPadding().getRight());
        assertTrue(popup.header.isWrapText()); assertTrue(popup.problem.isWrapText());
        assertEquals(13, popup.header.getFont().getSize());
        label("quick.caption", popup.header, FontToken.BASE, true);
        label("quick.error", popup.problem, FontToken.SMALL, false);
        assertEquals(1, popup.hints.size()); assertEquals(UiText.get("quick.hint"), popup.hints.getFirst().getText());
        assertEquals(11, popup.hints.getFirst().getFont().getSize());
        label("quick.hint", popup.hints.getFirst(), FontToken.SMALL, false);
        var field = popup.fields.get("amount");
        assertTrue(field.root.getLayoutY() + field.root.getHeight() <= popup.problem.getLayoutY());
        assertTrue(popup.problem.getLayoutY() + popup.problem.getHeight() <= popup.hints.getFirst().getLayoutY());
        assertEquals(List.of(popup.header, field.root, popup.problem, popup.hints.getFirst()), popup.content.getChildren());
        var editor = (TextField) field.control;
        var sample = new javafx.scene.text.Text(editor.getText()); sample.setFont(editor.getFont());
        assertTrue(sample.getLayoutBounds().getWidth() + editor.getInsets().getLeft() + editor.getInsets().getRight() < editor.getWidth());
        var editorBox = FxUiDumper.box(editor); var contentBox = FxUiDumper.box(popup.content);
        assertTrue(editorBox.x() >= contentBox.x() + popup.content.getPadding().getLeft());
        assertTrue(editorBox.x() + editorBox.width() <= contentBox.x() + contentBox.width() - popup.content.getPadding().getRight());
        for (var entry : Map.of("header", popup.header, "error", popup.problem, "hint", popup.hints.getFirst()).entrySet()) {
            var widget = entry.getValue();
            var line = new javafx.scene.text.Text("Ag"); line.setFont(widget.getFont());
            System.out.println("POPUP_TEXT_METRICS " + entry.getKey() + " allocated=" + widget.getWidth() + "x" + widget.getHeight()
                    + " fontLineHeight=" + line.getLayoutBounds().getHeight() + " baseline=" + widget.getBaselineOffset()
                    + " prefHeight=" + widget.prefHeight(widget.getWidth()));
            for (Node node : widget.lookupAll(".text")) if (node instanceof javafx.scene.text.Text text)
                System.out.println("POPUP_SKIN_METRICS " + entry.getKey() + " bounds=" + text.getLayoutBounds()
                        + " baseline=" + text.getBaselineOffset() + " boundsType=" + text.getBoundsType());
        }
        double contentHeight = popup.content.getChildren().stream().filter(Node::isManaged)
                .mapToDouble(node -> node.getLayoutBounds().getHeight()).sum();
        long managed = popup.content.getChildren().stream().filter(Node::isManaged).count();
        assertEquals(height, contentHeight + popup.content.getInsets().getTop() + popup.content.getInsets().getBottom()
                + Math.max(0, managed - 1) * popup.content.getSpacing(), 0.001);
        System.out.println("POPUP_LAYOUT_METRICS padding=" + popup.content.getPadding() + " gap=" + popup.content.getSpacing()
                + " childrenHeight=" + contentHeight + " managed=" + managed + " field=" + editor.getWidth() + "x" + editor.getHeight());
        System.out.println("GEOMETRY_NATIVE quickEdit=" + width + "x" + height + " field=" + popup.fields.get("amount").control.getLayoutBounds().getWidth());
        String imagePath = System.getProperty("fx.popupProofPng");
        if (imagePath != null) {
            // Снимок настоящего узла включает вычисленную тень, но не требует показа Popup или Stage.
            var parameters = new javafx.scene.SnapshotParameters(); parameters.setFill(javafx.scene.paint.Color.TRANSPARENT);
            var image = popup.content.snapshot(parameters, null);
            try { java.nio.file.Files.write(java.nio.file.Path.of(imagePath), ru.cashprediction.fx.ui.PngEncoder.encode(image)); }
            catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            System.out.println("POPUP_HIDDEN_SCENE_PNG " + imagePath + " " + image.getWidth() + "x" + image.getHeight());
        }
        assertTrue(javafx.stage.Window.getWindows().stream().noneMatch(javafx.stage.Window::isShowing));
        var restored = new FxQuickEditPopup(session, logic.spec(context), view,
                new Placement("main", new ru.cashprediction.core.session.WindowBounds(50, 60, 320, 113), null), port);
        restored.close();
        assertEquals(javafx.stage.PopupWindow.AnchorLocation.WINDOW_TOP_LEFT, restored.popup.getAnchorLocation());
    }); }

    /** Явная ширина работает для обычного текстового поля, а нулевая оставляет рост по сетке. */
    @Test void widthPxIsGenericAndZeroKeepsLayoutGrowth() throws Exception { onFx(() -> {
        var controller = (AppController) port.intents; var logic = new NewPlanWizardForm();
        var context = new FormContext("width-contract", "main", Map.of(), controller.state());
        var host = (FormSession.Host) Proxy.newProxyInstance(FormSession.Host.class.getClassLoader(), new Class<?>[]{FormSession.Host.class},
                (proxy, method, args) -> { throw new AssertionError(method.getName()); });
        var session = new FormSession(WindowType.NEW_PLAN_WIZARD, true, logic, context, host);
        var normal = FieldSpecs.text("width-test", "", "");
        var fixed = new FxFieldWidgets(FieldSpecs.withWidthPx(normal, 137), session, port);
        var growing = new FxFieldWidgets(normal, session, port);
        var root = new VBox(fixed.root, growing.root); FxStyles.root(root); new Scene(root); css(root, 500, 80);
        assertEquals(137, fixed.control.getLayoutBounds().getWidth());
        assertTrue(growing.control.getLayoutBounds().getWidth() > 137);
        assertEquals(Priority.NEVER, HBox.getHgrow(fixed.control)); assertEquals(Priority.ALWAYS, HBox.getHgrow(growing.control));
    }); }

    /** ButtonBar не растягивает короткую Отмену до ширины длинного действия. */
    @Test void alertButtonsKeepOwnMeasuredWidthsAndSharedGap() throws Exception { onFx(() -> {
        var spec = AlertCatalog.externalChange("Budget");
        var host = (AlertSession.Host) Proxy.newProxyInstance(AlertSession.Host.class.getClassLoader(), new Class<?>[]{AlertSession.Host.class},
                (proxy, method, args) -> { throw new AssertionError(method.getName()); });
        var session = new AlertSession("geometry-alert", "main", spec, host);
        var alert = new FxAlerts(spec, session, id -> { throw new AssertionError(id); }, port); alert.close();
        var pane = alert.alert.getDialogPane(); new Scene(pane); css(pane, spec.minWidth(), pane.prefHeight(spec.minWidth()));
        var glyph = assertInstanceOf(javafx.scene.image.ImageView.class, alert.glyph.getGraphic());
        assertEquals(DesignTokens.ALERT_ICON_SIZE, glyph.getFitWidth());
        assertEquals(DesignTokens.ALERT_ICON_SIZE, glyph.getFitHeight());
        assertSame(FxIcons.image(spec.kind().webGlyph(), ColorToken.WARN).orElseThrow(), glyph.getImage());
        var overwrite = alert.buttons.get("overwrite"); var reload = alert.buttons.get("reload"); var cancel = alert.buttons.get("cancel");
        assertFalse(ButtonBar.isButtonUniformSize(overwrite)); assertFalse(ButtonBar.isButtonUniformSize(cancel));
        assertEquals(DesignTokens.BUTTON_MIN_WIDTH, cancel.getWidth()); assertTrue(overwrite.getWidth() > cancel.getWidth());
        var overwriteBox = FxUiDumper.box(overwrite); var reloadBox = FxUiDumper.box(reload); var cancelBox = FxUiDumper.box(cancel);
        assertEquals(DesignTokens.FORM_VGAP, reloadBox.x() - overwriteBox.x() - overwriteBox.width(), 0.01);
        assertEquals(DesignTokens.FORM_VGAP, cancelBox.x() - reloadBox.x() - reloadBox.width(), 0.01);
        System.out.println("GEOMETRY_NATIVE alert overwrite=" + overwriteBox + " reload=" + reloadBox + " cancel=" + cancelBox);
    }); }

    /** После CSS высота страниц действительно растёт; это входные измерения для повторного центрирования. */
    @Test void wizardMeasuresEachRealPageInsteadOfOldHeight() throws Exception { onFx(() -> {
        var controller = (AppController) port.intents; var logic = new NewPlanWizardForm();
        var context = new FormContext("geometry-wizard", "main", Map.of(), controller.state());
        var wizard = form(logic, WindowType.NEW_PLAN_WIZARD, context.windowId(), Map.of(), controller);
        double previousHeight = 0;
        for (int page = 0; page < 3; page++) {
            wizard.update(logic.evaluate(new FormState(page, logic.defaults(context)), context));
            var pane = wizard.dialog.getDialogPane(); pane.applyCss(); double height = pane.prefHeight(wizard.spec.width()); css(pane, wizard.spec.width(), height);
            assertEquals(page, wizard.page); assertTrue(height > previousHeight); previousHeight = height;
            System.out.println("GEOMETRY_NATIVE wizard page=" + page + " height=" + height);
        }
        assertFalse(wizard.showing());
    }); }

    /** Длинные соседние сегменты не сжимают настоящий первый текст состояния до единственного глифа. */
    @Test void statusFileKeepsFullPaintedTextUnderPressure() throws Exception { onFx(() -> {
        Label file = (Label) port.main.status.getChildren().getFirst(); String previous = file.getText();
        var message = new Label(UiText.get("status.file", "Budget.md").repeat(30));
        port.main.status.getChildren().add(message);
        try {
            file.setText(UiText.get("status.file", "Budget.md")); css(port.main.status, 300, 24);
            assertEquals(Region.USE_PREF_SIZE, file.getMinWidth()); assertEquals(file.prefWidth(-1), file.getWidth(), 1);
            var baseline = FxUiDumper.baseline(port.main.status).orElseThrow(); assertTrue(baseline.width() > 60);
            System.out.println("GEOMETRY_NATIVE statusFile=" + file.getWidth() + " baseline=" + baseline);
        } finally { file.setText(previous); port.main.status.getChildren().remove(message); css(port.main.root, 1200, 800); }
        assertTrue(javafx.stage.Window.getWindows().stream().noneMatch(javafx.stage.Window::isShowing));
    }); }
}
