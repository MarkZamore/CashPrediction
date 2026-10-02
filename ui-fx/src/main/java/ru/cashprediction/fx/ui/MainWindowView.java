package ru.cashprediction.fx.ui;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;
import java.util.*;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.ui.command.*;
import ru.cashprediction.core.ui.menu.*;
import ru.cashprediction.core.ui.token.*;
import ru.cashprediction.core.ui.view.*;
import ru.cashprediction.core.ui.view.chart.ChartScene;
import ru.cashprediction.core.document.ViewMode;

/** Главное окно из независимых областей общих моделей. */
public final class MainWindowView {
    final BorderPane root = new BorderPane();
    final VBox top = new VBox();
    final FlowPane summary = new FlowPane(4, 4);
    final HBox status = new HBox(8);
    final StackPane chartPane = new StackPane();
    final Canvas chart = new Canvas();
    final Canvas hoverOverlay = new Canvas();
    final HBox legend = new HBox(12);
    final FxToolbar toolbar;
    final FxTable table;
    MenuBar bar;
    MainScreenModel model;
    ChartScene chartScene;
    private final FxUiPort port;
    private final List<PauseTransition> hoverTimers = new ArrayList<>();

    /** Создаёт области и пути событий, не открывая окно. */
    public MainWindowView(FxUiPort port) {
        this.port = port; FxStyles.root(root);
        toolbar = new FxToolbar(port.menus, port.intents, port.probe); table = new FxTable(port.intents, port.menus, port.probe);
        FxStyles.id(summary, "summary"); FxStyles.id(status, "status"); FxStyles.id(chartPane, "center");
        summary.getStyleClass().add("cp-summary"); status.getStyleClass().add("cp-status");
        summary.setPadding(new Insets(6, 8, 6, 8)); status.setPrefHeight(24); status.setMinHeight(24); status.setPadding(new Insets(2, 8, 2, 8));
        status.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        root.setTop(top); root.setBottom(status);
        hoverOverlay.setMouseTransparent(true); chartPane.getChildren().addAll(chart, hoverOverlay, legend);
        // Легенда занимает верхнее поле самой сцены (28 px), не добавляя отдельную строку над Canvas.
        StackPane.setAlignment(legend, javafx.geometry.Pos.TOP_LEFT); legend.setMaxHeight(Region.USE_PREF_SIZE);
        VBox chartBox = new VBox(chartPane); VBox.setVgrow(chartPane, Priority.ALWAYS);
        chartPane.widthProperty().addListener((o, a, b) -> redraw()); chartPane.heightProperty().addListener((o, a, b) -> redraw());
        chart.setOnMouseEntered(e -> traceHover("entered", e));
        chart.setOnMouseMoved(e -> { traceHover("moved", e); port.intents.chartHover(model.chart().revision(), e.getX(), e.getY(), chart.getWidth(), chart.getHeight()).ifPresentOrElse(h -> {
            hover(h); var anchor = chart.localToScreen(h.cardX(), h.cardY());
            if (anchor != null) port.day(h.card(), anchor.getX(), anchor.getY());
        }, this::clearHover); });
        chart.setOnMouseExited(e -> { traceHover("exited", e); clearHover(); });
        chart.setOnMouseClicked(e -> { if (e.getClickCount() == 2) port.intents.chartHover(model.chart().revision(), e.getX(), e.getY(), chart.getWidth(), chart.getHeight()).ifPresent(h -> port.intents.command(CommandId.CHART_SHOW_IN_TABLE, CommandArgs.date(h.date()), InvokeSource.MAIN)); });
        // JavaFX: ContextMenuEvent → Swing: MouseEvent.popupTrigger → Web: contextmenu
        chart.setOnContextMenuRequested(e -> { port.probe.created(e); port.showContextMenu(new ContextTarget.Chart(e.getX(), e.getY(), chart.getWidth(), chart.getHeight()), port.intents.contextMenu(new ContextTarget.Chart(e.getX(), e.getY(), chart.getWidth(), chart.getHeight()))); e.consume(); });
        chartBox.getProperties().put("cp.chart", true); chartPane.getProperties().put("cp.chartBox", chartBox);
        Scene scene = new Scene(root, 1200, 800); port.stage.setScene(scene); FxKeyBridge.install(scene, port.intents);
        // Щелчок/клавиша/действие закрывают hover и при нативном вводе, и при fire() настоящего виджета.
        scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> dismissHover());
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> dismissHover());
        scene.addEventFilter(javafx.event.ActionEvent.ACTION, e -> dismissHover());
        port.stage.setMinWidth(900); port.stage.setMinHeight(600);
        port.stage.setOnCloseRequest(e -> { e.consume(); port.intents.closeMainRequested(); });
        for (var property : List.of(port.stage.xProperty(), port.stage.yProperty(), port.stage.widthProperty(), port.stage.heightProperty())) property.addListener((o, a, b) -> { if (port.stage.isShowing()) port.intents.mainGeometry(FxUiPort.bounds(port.stage), port.stage.isMaximized()); });
        port.stage.maximizedProperty().addListener((o, a, b) -> port.intents.mainGeometry(FxUiPort.bounds(port.stage), b));
    }

    /** Обновляет только перечисленные ядром области. */
    public void render(MainScreenModel next, EnumSet<ScreenPart> parts) {
        model = next;
        if (parts.contains(ScreenPart.TITLE)) port.stage.setTitle(next.windowTitle());
        if (parts.contains(ScreenPart.MENU)) bar = port.menus.bar(next.menuBar());
        if (parts.contains(ScreenPart.TOOLBAR)) toolbar.render(next.toolbar());
        if (parts.contains(ScreenPart.SUMMARY)) summary(next);
        if (parts.contains(ScreenPart.TABLE)) table.render(next.table());
        if (parts.contains(ScreenPart.STATUS)) {
            status.getChildren().clear(); for (var s : next.status().segments()) {
                Label label = FxStyles.id(new Label(s.text()), s.id()); label.setVisible(s.visible()); label.setManaged(s.visible());
                // Сегмент файла не сжимается до многоточия при длинном сообщении или снимке.
                label.setMinWidth(s.grow() ? 0 : Region.USE_PREF_SIZE);
                FxStyles.text(label, s.color(), FontToken.SMALL); label.setTooltip(FxStyles.tip(s.tooltip(), port.probe)); if (s.grow()) HBox.setHgrow(label, Priority.ALWAYS);
                status.getChildren().add(label);
            }
        }
        // Не отсоединяем неизменённую сводку: это отнимает фокус у карточки и закрывает её окно.
        if (top.getChildren().isEmpty()) top.getChildren().setAll(bar, toolbar.root, summary);
        else if (top.getChildren().getFirst() != bar) top.getChildren().set(0, bar);
        root.setCenter(next.mode() == ViewMode.TABLE ? table.root : (javafx.scene.Node) chartPane.getProperties().get("cp.chartBox"));
        if (parts.contains(ScreenPart.CHART) || parts.contains(ScreenPart.MODE)) redraw();
    }

    private void summary(MainScreenModel next) {
        hoverTimers.forEach(PauseTransition::stop); hoverTimers.clear(); port.hideSpark();
        summary.getChildren().clear(); summary.setVisible(next.summary().visible()); summary.setManaged(next.summary().visible());
        if (!next.summary().unavailableText().isEmpty()) summary.getChildren().add(new Label(next.summary().unavailableText()));
        for (var card : next.summary().cards()) {
            Label title = new Label(card.title()), value = new Label(card.value()), caption = new Label(card.caption());
            FxStyles.text(title, ColorToken.TEXT_MUTED, FontToken.SMALL); FxStyles.text(value, card.valueColor(), FontToken.CARD); FxStyles.text(caption, card.captionColor(), FontToken.SMALL);
            cardLineHeight(title, DesignTokens.CARD_TITLE_LINE_HEIGHT);
            cardLineHeight(value, DesignTokens.CARD_VALUE_LINE_HEIGHT);
            cardLineHeight(caption, DesignTokens.CARD_CAPTION_LINE_HEIGHT);
            VBox box = FxStyles.id(new VBox(DesignTokens.CARD_CONTENT_GAP, title, value, caption), card.id()); box.setFocusTraversable(true);
            box.getProperties().put("cp.tooltip", card.explanation());
            javafx.scene.control.Tooltip.install(box, FxStyles.tip(card.explanation(), port.probe));
            box.getProperties().put("cp.scope", FocusScope.CARD); box.getProperties().put("cp.focusId", card.id());
            box.setMinWidth(118); box.setPadding(new Insets(6, 10, 6, 10)); box.setStyle("-fx-background-color: #FFFFFF; -fx-border-color: #D0D7DE; -fx-border-radius: 6; -fx-background-radius: 6;");
            box.prefWidthProperty().bind(javafx.beans.binding.Bindings.max(118, summary.widthProperty().subtract(16 + 8 * 4).divide(9)));
            box.maxWidthProperty().bind(box.prefWidthProperty());
            PauseTransition timer = new PauseTransition(Duration.millis(350)); timer.setOnFinished(e -> port.spark(box, card.id()));
            hoverTimers.add(timer);
            box.setOnMouseEntered(e -> { port.hideDay(); timer.playFromStart(); }); box.setOnMouseExited(e -> { timer.stop(); port.hideSpark(); });
            box.focusedProperty().addListener((o, a, b) -> { if (b) { port.hideDay(); timer.playFromStart(); } else { timer.stop(); port.hideSpark(); } });
            box.setOnMouseClicked(e -> { port.hideSpark(); if (e.getClickCount() == 2) port.intents.command(CommandId.CARD_SHOW_IN_TABLE, CommandArgs.card(card.id(), card.date()), InvokeSource.MAIN); });
            // JavaFX: ContextMenuEvent → Swing: MouseEvent.popupTrigger → Web: contextmenu
            box.setOnContextMenuRequested(e -> { port.probe.created(e); timer.stop(); port.hideSpark(); port.showContextMenu(new ContextTarget.Card(card.id()), port.intents.contextMenu(new ContextTarget.Card(card.id()))); e.consume(); });
            summary.getChildren().add(box);
        }
    }

    private void redraw() {
        if (model == null) return;
        if (Boolean.getBoolean("fx.hover.metrics")) System.out.println("HOVER_FRAME revision=" + model.revision() + " mode=" + model.mode());
        double width = chartPane.getWidth() > 0 ? chartPane.getWidth() : port.stage.getScene().getWidth();
        double height = chartPane.getHeight() > 0 ? chartPane.getHeight() : 500;
        chartScene = port.intents.chartScene(width, height); FxChartCanvas.paint(chart, chartScene); clearHover();
        legend.getChildren().clear(); for (var item : chartScene.legend()) {
            Label label = FxStyles.id(new Label(item.text()), item.id()); FxStyles.text(label, ColorToken.TEXT_MUTED, FontToken.LEGEND);
            if (item.swatch() != ru.cashprediction.core.ui.view.chart.LegendItem.Swatch.NONE) {
                label.setGraphic(FxChartCanvas.legendSample(item)); label.setGraphicTextGap(4);
            }
            label.setTooltip(FxStyles.tip(item.tooltip(), port.probe)); legend.getChildren().add(label);
        }
    }

    private void hover(ru.cashprediction.core.ui.view.chart.ChartHover hover) {
        hoverOverlay.setWidth(chart.getWidth()); hoverOverlay.setHeight(chart.getHeight());
        var g = hoverOverlay.getGraphicsContext2D(); g.clearRect(0, 0, hoverOverlay.getWidth(), hoverOverlay.getHeight());
        g.setStroke(javafx.scene.paint.Color.web(ColorToken.TEXT_MUTED.hex())); g.setLineWidth(1); g.setLineDashes(4, 4);
        g.strokeLine(hover.lineX(), chartScene.plot().plotY(), hover.lineX(), chartScene.plot().plotY() + chartScene.plot().plotHeight());
        g.setFill(javafx.scene.paint.Color.web(ColorToken.ACCENT.hex())); g.fillOval(hover.dot().x() - 4.5, hover.dot().y() - 4.5, 9, 9);
    }
    /** Одна строка карточки имеет общий бокс; полный текст подписи остаётся доступным для дампа. */
    private static void cardLineHeight(Label label, int height) {
        label.setMinHeight(height); label.setPrefHeight(height); label.setMaxHeight(height);
    }
    private void clearHover() { hoverOverlay.getGraphicsContext2D().clearRect(0, 0, hoverOverlay.getWidth(), hoverOverlay.getHeight()); port.hideDay(); }
    /** Снимает реальные всплывающие окна наведения при действии пользователя. */
    void dismissHover() { hoverTimers.forEach(PauseTransition::stop); clearHover(); port.hideSpark(); }
    private void traceHover(String event, javafx.scene.input.MouseEvent mouse) {
        if (!Boolean.getBoolean("fx.hover.metrics")) return;
        System.out.println("HOVER_EVENT " + event + " nanos=" + System.nanoTime() + " local=" + mouse.getX() + "," + mouse.getY()
                + " screen=" + mouse.getScreenX() + "," + mouse.getScreenY() + " synthesized=" + mouse.isSynthesized()
                + " mainFocused=" + port.stage.isFocused() + " windows=" + port.windows.values().stream().filter(WindowHandle::showing).count());
    }
}
