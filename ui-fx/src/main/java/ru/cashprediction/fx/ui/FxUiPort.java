package ru.cashprediction.fx.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.ui.alert.*;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.menu.*;
import ru.cashprediction.core.ui.token.*;
import ru.cashprediction.core.ui.view.*;
import ru.cashprediction.core.ui.view.chart.*;
import ru.cashprediction.core.ui.view.popup.*;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.text.UiText;

/** JavaFX-порт общих моделей, окон, буфера обмена и нативного выбора файлов. */
public final class FxUiPort implements UiPort {
    final Stage stage;
    final FxClassUsageProbe probe = new FxClassUsageProbe();
    final Map<String, WindowHandle> windows = new LinkedHashMap<>();
    final List<UiDump.ChooserRequest> chooserRequests = new ArrayList<>();
    List<ContextMenu> contexts = new ArrayList<>();
    final Scheduler scheduler = new ExecutorScheduler("cp-fx-core-timer");
    UiIntents intents;
    FxMenus menus;
    MainWindowView main;
    long clientRevision;
    private Popup dayPopup;
    private PopupControl sparkPopup;
    private Consumer<Optional<Path>> pendingChooser;
    private Optional<Path> queuedChooser;
    boolean selftest;
    volatile boolean exited;
    private Runnable updateReady = () -> { };
    private Runnable updateClose = () -> { };

    /** Подключает только события готовности и выхода к общему обновлятору. */
    public void updateCallbacks(Runnable ready, Runnable close) {
        updateReady = java.util.Objects.requireNonNull(ready);
        updateClose = java.util.Objects.requireNonNull(close);
    }
    private final java.util.function.Supplier<LocalDate> today;

    /** Создаёт порт без запуска ядра. */
    public FxUiPort(Stage stage) { this(stage, LocalDate::now); }
    /** Использует общие часы окружения для начального месяца календаря пустого поля. */
    public FxUiPort(Stage stage, java.util.function.Supplier<LocalDate> today) {
        this.stage = stage; this.today = today;
        FxIcons.application(stage);
        // Нативный Dialog требует сцену владельца даже до показа главного окна при восстановлении.
        stage.setScene(new javafx.scene.Scene(new StackPane(), 1200, 800)); stage.setMinWidth(900); stage.setMinHeight(600);
    }
    /** Присоединяет получателя событий перед запуском контроллера. */
    public void bind(UiIntents intents) { this.intents = intents; menus = new FxMenus(intents, probe); contexts = menus.contexts; }
    /** Возвращает профиль нативного клиента. */
    @Override public ClientProfile profile() { return ClientProfile.fx(System.getProperty("javafx.version", "25").split("\\.")[0]); }
    /** Выполняет задачи в потоке JavaFX. */
    @Override public UiExecutor executor() {
        return new UiExecutor() {
            /** Передаёт задачу в поток контроллера. */
            @Override public void execute(Runnable action) { if (Platform.isFxApplicationThread()) action.run(); else Platform.runLater(action); }
            /** Проверяет текущий поток. */
            @Override public boolean isUiThread() { return Platform.isFxApplicationThread(); }
        };
    }
    /** Возвращает планировщик ядра. */
    @Override public Scheduler scheduler() { return scheduler; }
    /** Создаёт и показывает главное окно ровно один раз. */
    @Override public void showMain(MainScreenModel model, MainWindowState restored) {
        if (main != null) throw new IllegalStateException("showMain twice");
        main = new MainWindowView(this); main.render(model, EnumSet.allOf(ScreenPart.class));
        if (restored != null && restored.bounds() != null) FxFormDialog.applyBounds(stage, restored.bounds());
        if (restored != null) stage.setMaximized(restored.maximized()); stage.show();
        updateReady.run();
    }
    /** Перерисовывает изменившиеся области. */
    @Override public void render(MainScreenModel model, EnumSet<ScreenPart> changed) { if (main != null) main.render(model, changed); }
    /** Читает границы настоящего окна. */
    @Override public MainGeometry mainGeometry() { return new MainGeometry(bounds(stage), stage.isMaximized()); }
    /** Открывает общий диалог или быструю правку. */
    @Override public WindowHandle openForm(FormSession session, FormSpec spec, FormView initial, Placement placement) {
        contexts.forEach(ContextMenu::hide);
        WindowHandle handle = spec.presentation() == Presentation.POPUP ? new FxQuickEditPopup(session, spec, initial, placement, this) : new FxFormDialog(session, spec, initial, placement, this);
        windows.put(session.windowId(), handle); return handle;
    }
    /** Открывает сообщение с однократным ответом. */
    @Override public WindowHandle showAlert(AlertSpec spec, AlertSession session, Consumer<String> onButton) {
        contexts.forEach(ContextMenu::hide);
        FxAlerts alert = new FxAlerts(spec, session, onButton, this); windows.put(alert.id, alert); return alert;
    }
    /** Открывает контекстное меню из готовых пунктов. */
    @Override public void showContextMenu(ContextTarget target, List<MenuNode> items) {
        // JavaFX: ContextMenu → Swing: JPopupMenu → Web: div[role=menu]
        ContextMenu context = menus.context(items, ru.cashprediction.core.ui.command.InvokeSource.CONTEXT_MENU);
        context.getProperties().put("cp.target", contextId(target));
        // JavaFX: ContextMenu → Swing: JPopupMenu → Web: div[role=menu]
        context.show(stage, stage.getX() + 100, stage.getY() + 200);
    }
    /** Показывает нативный выбор файла; в самотесте записывает только запрос. */
    @Override public void chooseFile(FileChooserSpec spec, Consumer<Optional<Path>> onResult) {
        chooserRequests.add(new UiDump.ChooserRequest("file", spec.mode().name(), spec.title(), spec.filterDescription(), spec.initialFolder().toString(), spec.initialName()));
        // JavaFX: FileChooser → Swing: JFileChooser → Web: dialog.file-browser
        FileChooser chooser = probe.created(new FileChooser()); chooser.setTitle(spec.title()); chooser.setInitialFileName(spec.initialName());
        if (java.nio.file.Files.isDirectory(spec.initialFolder())) chooser.setInitialDirectory(spec.initialFolder().toFile());
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(spec.filterDescription(), spec.extensions().stream().map(e -> "*." + e).toList()));
        if (selftest) { requestChooser(onResult); return; }
        java.io.File selected = spec.mode() == FileChooserSpec.Mode.OPEN ? chooser.showOpenDialog(stage) : chooser.showSaveDialog(stage);
        onResult.accept(Optional.ofNullable(selected).map(java.io.File::toPath));
    }
    /** Показывает нативный выбор папки. */
    @Override public void chooseDirectory(DirectoryChooserSpec spec, Consumer<Optional<Path>> onResult) {
        chooserRequests.add(new UiDump.ChooserRequest("directory", "", spec.title(), "", spec.initialFolder().toString(), ""));
        // JavaFX: DirectoryChooser → Swing: JFileChooser.DIRECTORIES_ONLY → Web: dialog.file-browser
        DirectoryChooser chooser = probe.created(new DirectoryChooser()); chooser.setTitle(spec.title());
        if (java.nio.file.Files.isDirectory(spec.initialFolder())) chooser.setInitialDirectory(spec.initialFolder().toFile());
        if (selftest) { requestChooser(onResult); return; }
        onResult.accept(Optional.ofNullable(chooser.showDialog(stage)).map(java.io.File::toPath));
    }
    /** Отвечает на зарегистрированный запрос выбора в изолированном самотесте. */
    public void answerChooser(Path path) {
        if (pendingChooser == null) { if (queuedChooser != null) throw new IllegalStateException("chooser queued"); queuedChooser = Optional.ofNullable(path); }
        else { var callback = pendingChooser; pendingChooser = null; callback.accept(Optional.ofNullable(path)); }
    }
    private void requestChooser(Consumer<Optional<Path>> callback) {
        if (pendingChooser != null) throw new IllegalStateException("chooser pending");
        if (queuedChooser != null) { var answer = queuedChooser; queuedChooser = null; callback.accept(answer); }
        else pendingChooser = callback;
    }
    /** Рисует PNG из актуальной сцены на отдельном холсте. */
    @Override public byte[] renderChartPng(ChartScene scene) { Canvas canvas = new Canvas(); FxChartCanvas.paint(canvas, scene); return ru.cashprediction.fx.ui.PngEncoder.encode(canvas.snapshot(null, null)); }
    /** Переводит фокус по указанию ядра. */
    @Override public void focus(FocusTarget target) {
        if (main == null) return;
        switch (target) {
            case TABLE -> main.table.root.requestFocus();
            case FILTER -> { Node n = main.toolbar.widgets.get("tb.filter"); if (n != null) { n.requestFocus(); if (n instanceof TextField f) f.selectAll(); } }
            case MENU_BAR -> { main.bar.requestFocus(); if (!main.bar.getMenus().isEmpty()) main.bar.getMenus().getFirst().show(); }
            case CHART -> main.chartPane.requestFocus();
        }
    }
    /** Прокручивает таблицу без вычислений дат в клиенте. */
    @Override public void revealRow(String rowId, RevealMode mode) { if (main != null) main.table.reveal(rowId, mode); }
    /** Помещает уже готовый текст в системный буфер. */
    @Override public void copyToClipboard(String text) { ClipboardContent content = new ClipboardContent(); content.putString(text); Clipboard.getSystemClipboard().setContent(content); }
    /** Завершает приложение и фоновые таймеры. */
    @Override public void exit(ExitKind kind, int code) {
        if (kind == ExitKind.CLEAN) ru.cashprediction.fx.FxMain.recordCleanExit(code);
        exited = true;
        updateClose.run();
        scheduler.shutdown(); windows.values().forEach(WindowHandle::close); hideDay(); hideSpark(); contexts.forEach(ContextMenu::hide);
        if (kind == ExitKind.HALT) Runtime.getRuntime().halt(code); else { stage.hide(); if (!selftest) Platform.exit(); }
    }
    /** Возвращает живое окно владельца. */
    public Window owner(String id) {
        WindowHandle handle = windows.get(id);
        if (handle instanceof FxFormDialog f && f.dialog.getDialogPane().getScene() != null) return f.dialog.getDialogPane().getScene().getWindow();
        if (handle instanceof FxAlerts a && a.alert.getDialogPane().getScene() != null) return a.alert.getDialogPane().getScene().getWindow();
        return stage;
    }
    /** Считывает геометрию JavaFX в контракт ядра. */
    public static WindowBounds bounds(Window window) {
        if (!Double.isFinite(window.getX()) || !Double.isFinite(window.getY()) || !Double.isFinite(window.getWidth()) || !Double.isFinite(window.getHeight()) || window.getWidth() <= 0 || window.getHeight() <= 0) return null;
        return new WindowBounds(window.getX(), window.getY(), window.getWidth(), window.getHeight());
    }

    /** Сохраняет идентичность настоящей области, породившей контекстное меню. */
    static String contextId(ContextTarget target) {
        return switch (target) {
            case ContextTarget.Row t -> "row:" + t.rowId();
            case ContextTarget.Total t -> "total:" + t.rowId();
            case ContextTarget.PastHeader _ -> "pastHeader";
            case ContextTarget.Card t -> "card:" + t.cardId();
            case ContextTarget.Chart t -> "chart:" + coordinate(t.x()) + "," + coordinate(t.y());
            case ContextTarget.Preview t -> "preview:" + t.windowId() + ":" + t.index();
        };
    }
    private static String coordinate(double value) { return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value); }

    void day(DayCardModel model, double x, double y) {
        hideDay();
        // JavaFX: PopupWindow → Swing: JWindow → Web: div.day-card
        // JavaFX: Popup → Swing: SwingPopups.day (JWindow) → Web: div.dayCard
        dayPopup = probe.created(new Popup()); dayPopup.getProperties().put("cp.popupKind", "dayCard");
        dayPopup.setAnchorLocation(PopupWindow.AnchorLocation.CONTENT_TOP_LEFT);
        VBox content = dayContent(model);
        content.setMouseTransparent(true); dayPopup.getContent().add(content); dayPopup.show(stage, x, y);
    }
    /** Создаёт настоящие строки карточки дня для показа и проверки вычисленного CSS. */
    static VBox dayContent(DayCardModel model) {
        VBox content = new VBox(3); content.setPadding(new javafx.geometry.Insets(8));
        Label header = new Label(model.header()), balance = new Label(model.balanceLine());
        FxStyles.text(header, ColorToken.TEXT_PRIMARY, FontToken.HEADER); FxStyles.text(balance, model.balanceColor(), FontToken.BASE);
        content.getChildren().addAll(header, balance);
        for (var line : model.lines()) { Label label = new Label(line.text()); FxStyles.text(label, line.color(), FontToken.BASE); content.getChildren().add(label); }
        if (!model.moreText().isEmpty()) content.getChildren().add(new Label(model.moreText())); if (!model.noneText().isEmpty()) content.getChildren().add(new Label(model.noneText()));
        FxStyles.root(content); content.getStyleClass().add("cp-popover"); content.setMinWidth(DesignTokens.DAY_CARD_MIN_WIDTH); content.setMaxWidth(DesignTokens.DAY_CARD_MAX_WIDTH);
        for (Node node : content.getChildren()) if (node instanceof Label label) { label.setWrapText(true); label.setMinWidth(0); label.setMaxWidth(Double.MAX_VALUE); }
        return content;
    }
    void hideDay() { if (dayPopup != null) dayPopup.hide(); }
    void spark(Node owner, String cardId) {
        hideSpark(); SparklineModel model = intents.sparkline(cardId);
        // JavaFX: PopupControl → Swing: SwingPopups.spark (JWindow) → Web: div.sparkline
        sparkPopup = probe.created(new PopupControl()); sparkPopup.getProperties().put("cp.popupKind", "sparkline");
        sparkPopup.setAnchorLocation(PopupWindow.AnchorLocation.CONTENT_TOP_LEFT);
        VBox content = sparkContent(model);
        final PopupControl popup = sparkPopup;
        popup.setSkin(new Skin<>() {
            /** Возвращает всплывающий контрол. */
            @Override public PopupControl getSkinnable() { return popup; }
            /** Возвращает настоящий корень содержимого. */
            @Override public Node getNode() { return content; }
            /** Освобождение не требует дополнительных ресурсов. */
            @Override public void dispose() { }
        });
        var b = owner.localToScreen(owner.getLayoutBounds()); if (b != null) popup.show(owner, b.getMinX(), b.getMaxY() + DesignTokens.CARD_POPUP_GAP);
    }
    /** Создаёт содержимое спарклайна с настоящими шрифтами и переносом внутри ширины графика. */
    static VBox sparkContent(SparklineModel model) {
        VBox content = new VBox(DesignTokens.SPARK_CONTENT_GAP); content.setPadding(new javafx.geometry.Insets(8)); FxStyles.root(content);
        content.getStyleClass().add("cp-popover"); content.setMouseTransparent(true);
        Label header = new FxLineHeightLabel(model.header(), DesignTokens.SPARK_HEADER_LINE_HEIGHT); FxStyles.text(header, ColorToken.TEXT_PRIMARY, FontToken.HEADER);
        header.setPrefWidth(DesignTokens.SPARK_WIDTH); header.setMaxWidth(DesignTokens.SPARK_WIDTH);
        Label explanation = new FxLineHeightLabel(model.explanation(), DesignTokens.SPARK_BODY_LINE_HEIGHT);
        explanation.setPrefWidth(DesignTokens.SPARK_WIDTH); explanation.setMaxWidth(DesignTokens.SPARK_WIDTH);
        FxStyles.text(explanation, ColorToken.TEXT_MUTED, FontToken.SMALL);
        Canvas canvas = new Canvas(240, 60); var g = canvas.getGraphicsContext2D(); g.setStroke(javafx.scene.paint.Color.web(ColorToken.ACCENT.hex())); g.setLineWidth(1.5);
        for (int i = 1; i < model.points().size(); i++) { var a = model.points().get(i - 1); var b = model.points().get(i); g.strokeLine(a.x() * 240, a.y() * 60, b.x() * 240, b.y() * 60); }
        if (model.zeroY() != null) { g.setStroke(javafx.scene.paint.Color.web(ColorToken.EXPENSE.hex())); g.setLineDashes(3, 3); g.strokeLine(0, model.zeroY() * 60, 240, model.zeroY() * 60); }
        if (model.marker() != null) { g.setFill(javafx.scene.paint.Color.web(ColorToken.LINE_TODAY.hex())); g.fillOval(model.marker().x() * 240 - 3, model.marker().y() * 60 - 3, 6, 6); }
        content.getChildren().addAll(header, explanation);
        if (model.points().size() >= 2) content.getChildren().add(canvas);
        else if (!model.noDataText().isEmpty()) {
            Label noData = new FxLineHeightLabel(model.noDataText(), DesignTokens.SPARK_BODY_LINE_HEIGHT);
            FxStyles.text(noData, ColorToken.TEXT_MUTED, FontToken.SMALL);
            noData.setPrefWidth(DesignTokens.SPARK_WIDTH); noData.setMaxWidth(DesignTokens.SPARK_WIDTH); content.getChildren().add(noData);
        }
        HBox footer = new HBox(8); Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        double footerWidth = (DesignTokens.SPARK_WIDTH - 2 * footer.getSpacing()) / 2;
        if (!model.minText().isEmpty()) { Label min = new FxLineHeightLabel(model.minText(), DesignTokens.SPARK_FOOTER_LINE_HEIGHT); FxStyles.text(min, ColorToken.TEXT_MUTED, FontToken.MICRO); min.setMaxWidth(footerWidth); footer.getChildren().add(min); }
        footer.getChildren().add(spacer);
        if (!model.maxText().isEmpty()) { Label max = new FxLineHeightLabel(model.maxText(), DesignTokens.SPARK_FOOTER_LINE_HEIGHT); FxStyles.text(max, ColorToken.TEXT_MUTED, FontToken.MICRO); max.setAlignment(javafx.geometry.Pos.TOP_RIGHT); max.setTextAlignment(javafx.scene.text.TextAlignment.RIGHT); max.setMaxWidth(footerWidth); footer.getChildren().add(max); }
        if (!model.minText().isEmpty() || !model.maxText().isEmpty()) content.getChildren().add(footer);
        return content;
    }
    void hideSpark() { if (sparkPopup != null) sparkPopup.hide(); }

    void calendar(Node owner, String value, Consumer<String> picked) {
        LocalDate selected = ru.cashprediction.core.ui.form.FieldCodec.parseDate(value).orElse(null);
        YearMonth month = YearMonth.from(selected == null ? today.get() : selected);
        // JavaFX: Popup → Swing: JPopupMenu → Web: div.calendar
        Popup popup = probe.created(new Popup()); popup.getProperties().put("cp.popupKind", "calendar"); popup.setAutoHide(true); popup.setHideOnEscape(true);
        VBox content = new VBox(4); FxStyles.root(content); popup.getContent().add(content);
        Consumer<YearMonth> render = new Consumer<>() {
            /** Рисует календарь по готовой модели месяца. */
            @Override public void accept(YearMonth m) {
                CalendarModel model = intents.calendar(m, selected); content.getChildren().clear();
                Button prev = new Button("\u25c0"), next = new Button("\u25b6");
                FxIcons.icon(prev, prev.getText(), DesignTokens.INLINE_ICON_SIZE); FxIcons.icon(next, next.getText(), DesignTokens.INLINE_ICON_SIZE);
                prev.setTooltip(FxStyles.tip(model.prevTooltip(), probe)); next.setTooltip(FxStyles.tip(model.nextTooltip(), probe));
                prev.setOnAction(e -> accept(m.minusMonths(1))); next.setOnAction(e -> accept(m.plusMonths(1)));
                content.getChildren().add(new HBox(4, prev, new Label(model.title()), next)); GridPane grid = new GridPane();
                for (int i = 0; i < model.weekdays().size(); i++) grid.add(new Label(model.weekdays().get(i)), i, 0);
                for (int i = 0; i < model.days().size(); i++) {
                    var day = model.days().get(i); Button b = new Button(day.text()); FxStyles.text(b, day.textColor(), FontToken.BASE);
                    b.setOnAction(e -> { picked.accept(FieldCodec.display(FieldKind.DATE, day.date().toString())); popup.hide(); }); grid.add(b, i % 7, i / 7 + 1);
                }
                content.getChildren().add(grid);
            }
        };
        render.accept(month); var b = owner.localToScreen(owner.getBoundsInLocal()); if (b != null) popup.show(owner, b.getMinX(), b.getMaxY());
    }
}
