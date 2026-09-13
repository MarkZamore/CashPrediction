package ru.cashprediction.fx;

import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Separator;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.document.ViewMode;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.fx.dialog.FxDialogHost;
import ru.cashprediction.fx.menu.AddSplitMenuButton;
import ru.cashprediction.fx.menu.AppMenuBar;
import ru.cashprediction.fx.menu.PeriodMenuButton;
import ru.cashprediction.fx.menu.ViewControls;
import ru.cashprediction.fx.menu.WhatIfMenuButton;
import ru.cashprediction.fx.view.BalanceChartView;
import ru.cashprediction.fx.view.ForecastTableView;
import ru.cashprediction.fx.view.StatusBar;
import ru.cashprediction.fx.view.SummaryBar;

import java.net.URL;
import java.util.Objects;

/**
 * Главное окно: строка меню, панель инструментов, панель сводки, таблица или график, строка состояния.
 *
 * <p>Окно только собирает компоненты и перерисовывает их по документу ({@link #refreshAll()}); вся логика
 * запуска, восстановления, настроек и выхода — в {@link AppController}. Размер по умолчанию 1200×800,
 * при восстановлении сессии — геометрия из снимка, если она попадает на один из экранов.</p>
 *
 * <p>Только FX Application Thread.</p>
 */
public final class MainWindow {

    /** Ширина окна по умолчанию. */
    public static final double DEFAULT_WIDTH = 1200;
    /** Высота окна по умолчанию. */
    public static final double DEFAULT_HEIGHT = 800;

    private final Stage stage;
    private final ShellContext shell;
    private final ViewControls controls;
    private final AppMenuBar menuBar;
    private final PeriodMenuButton periodButton;
    private final WhatIfMenuButton whatIfButton;
    private final SummaryBar summary;
    private final ForecastTableView table;
    private final BalanceChartView chart;
    private final StatusBar status = new StatusBar();
    private final ToolBar toolBar;
    private final Button undoButton = new Button("↶");
    private final Button redoButton = new Button("↷");
    private boolean boundsRestored;

    /**
     * Собирает окно (не показывает его).
     *
     * @param stage главная сцена JavaFX
     * @param shell оболочка приложения
     */
    public MainWindow(Stage stage, ShellContext shell) {
        this.stage = Objects.requireNonNull(stage, "stage");
        this.shell = Objects.requireNonNull(shell, "shell");
        this.controls = new ViewControls(shell);
        this.menuBar = new AppMenuBar(shell, controls);
        this.periodButton = new PeriodMenuButton(controls);
        this.whatIfButton = new WhatIfMenuButton(controls);
        this.summary = new SummaryBar(shell);
        this.table = new ForecastTableView(shell);
        this.chart = new BalanceChartView(shell);

        AddSplitMenuButton addButton = new AddSplitMenuButton(shell);
        Button saveButton = new Button("Сохранить");
        saveButton.setOnAction(e -> shell.actions().save(ok -> { }));
        // JavaFX: Tooltip → Swing: setToolTipText → Web: title
        saveButton.setTooltip(new Tooltip("Сохранить план в файл .md (Ctrl+S)"));
        undoButton.setOnAction(e -> shell.actions().undo());
        redoButton.setOnAction(e -> shell.actions().redo());
        // JavaFX: Tooltip → Swing: setToolTipText → Web: title
        undoButton.setTooltip(new Tooltip("Отменить (Ctrl+Z)"));
        // JavaFX: Tooltip → Swing: setToolTipText → Web: title
        redoButton.setTooltip(new Tooltip("Повторить (Ctrl+Y)"));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        this.toolBar = new ToolBar(addButton, new Separator(), controls.tableButton(), controls.chartButton(),
                periodButton, whatIfButton, new Separator(), controls.filterField(), spacer, undoButton, redoButton, saveButton);

        StackPane center = new StackPane(table, chart);
        VBox.setVgrow(center, Priority.ALWAYS);
        VBox middle = new VBox(summary, center);
        BorderPane root = new BorderPane(middle);
        root.setTop(new VBox(menuBar, toolBar));
        root.setBottom(status);

        Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        URL css = MainWindow.class.getResource("styles.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        stage.setScene(scene);
        // 800: при меньшей ширине карточки панели сводки перестают помещаться даже в минимальном размере.
        stage.setMinWidth(800);
        stage.setMinHeight(480);
        Image icon = FxDialogHost.appIcon();
        if (icon != null) {
            stage.getIcons().add(icon);
        }
    }

    /** Перерисовывает все компоненты по документу и синхронизирует меню с видом. */
    public void refreshAll() {
        PlanDocument document = shell.document();
        ViewState view = document.viewState();
        controls.sync(view, document.plan());
        periodButton.update(view);
        whatIfButton.update(view);
        menuBar.refresh(document);
        undoButton.setDisable(!document.canUndo());
        redoButton.setDisable(!document.canRedo());
        table.refresh();
        summary.refresh();
        // Скрытый график только помечает себя устаревшим и перестраивается при показе.
        chart.refresh();
        boolean chartMode = view.mode() == ViewMode.CHART;
        table.setVisible(!chartMode);
        chart.setVisible(chartMode);
        status.update(document, table.eventRowCount());
        updateTitle();
    }

    /** Обновляет заголовок: «CashPrediction — имя плана», звёздочка при несохранённых изменениях. */
    public void updateTitle() {
        PlanDocument document = shell.document();
        stage.setTitle("CashPrediction — " + document.plan().name() + (document.isDirty() ? "*" : ""));
    }

    /**
     * Синхронизирует пункты меню, зависящие от настроек.
     *
     * @param settings настройки
     */
    public void syncSettings(AppSettings settings) {
        menuBar.syncSettings(settings);
    }

    /**
     * Применяет геометрию из снимка, если она попадает на один из экранов (монитор могли отключить).
     *
     * @param bounds    геометрия или {@code null}
     * @param maximized развёрнуто ли окно
     */
    public void applyBounds(WindowBounds bounds, boolean maximized) {
        if (bounds != null && bounds.width() >= 400 && bounds.height() >= 300
                && !Screen.getScreensForRectangle(bounds.x(), bounds.y(), bounds.width(), bounds.height()).isEmpty()) {
            Rectangle2D visual = Screen.getScreensForRectangle(bounds.x(), bounds.y(), bounds.width(), bounds.height())
                    .getFirst().getVisualBounds();
            // Прижимаем к видимой области экрана: заголовок окна не должен оказаться за краем.
            double width = Math.min(bounds.width(), visual.getWidth());
            double height = Math.min(bounds.height(), visual.getHeight());
            stage.setWidth(width);
            stage.setHeight(height);
            stage.setX(Math.clamp(bounds.x(), visual.getMinX(), Math.max(visual.getMinX(), visual.getMaxX() - width)));
            stage.setY(Math.clamp(bounds.y(), visual.getMinY(), Math.max(visual.getMinY(), visual.getMaxY() - height)));
            boundsRestored = true;
        }
        stage.setMaximized(maximized);
    }

    /**
     * Геометрия окна для снимка.
     *
     * @return геометрия или {@code null}, если окно ещё не показано
     */
    public WindowBounds captureBounds() {
        double x = stage.getX();
        double y = stage.getY();
        double w = stage.getWidth();
        double h = stage.getHeight();
        if (!stage.isShowing() || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(w) || !Double.isFinite(h)
                || w < 0 || h < 0) {
            return null;
        }
        return new WindowBounds(x, y, w, h);
    }

    /** Показывает окно; без восстановленной геометрии — 1200×800 по центру экрана. */
    public void show() {
        if (stage.isShowing()) {
            return;
        }
        if (!boundsRestored) {
            stage.setWidth(DEFAULT_WIDTH);
            stage.setHeight(DEFAULT_HEIGHT);
            stage.centerOnScreen();
        }
        stage.show();
    }

    /** Переводит фокус в поле фильтра и выделяет его текст. */
    public void focusFilter() {
        controls.filterField().requestFocus();
        controls.filterField().selectAll();
    }

    /** @return сцена JavaFX главного окна */
    public Stage stage() {
        return stage;
    }

    /** @return таблица прогноза */
    public ForecastTableView table() {
        return table;
    }

    /** @return график баланса */
    public BalanceChartView chart() {
        return chart;
    }

    /** @return строка состояния */
    public StatusBar status() {
        return status;
    }

    /** @return элементы управления видом (для самотеста) */
    public ViewControls controls() {
        return controls;
    }

    /** @return строка меню (отчёт самотеста о меню) */
    public AppMenuBar menuBar() {
        return menuBar;
    }

    /** @return панель инструментов (отчёт самотеста о кнопках-меню и подсказках) */
    public ToolBar toolBar() {
        return toolBar;
    }

    /** @return панель сводки (отчёт самотеста о контекстных меню карточек) */
    public SummaryBar summary() {
        return summary;
    }
}
