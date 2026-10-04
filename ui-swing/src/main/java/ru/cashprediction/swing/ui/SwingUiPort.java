package ru.cashprediction.swing.ui;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.ui.alert.*;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.menu.*;
import ru.cashprediction.core.ui.command.InvokeSource;
import ru.cashprediction.core.ui.view.*;
import ru.cashprediction.core.ui.view.chart.ChartScene;
import ru.cashprediction.core.ui.dump.UiDump;

/** Тонкий порт Swing: реальные окна, EDT, события и общие UI-модели. */
public final class SwingUiPort implements UiPort {
    private Runnable updateReady = () -> { };
    private Runnable updateClose = () -> { };

    /** Подключает только события готовности и выхода к общему обновлятору. */
    public void updateCallbacks(Runnable ready, Runnable close) {
        updateReady = java.util.Objects.requireNonNull(ready);
        updateClose = java.util.Objects.requireNonNull(close);
    }
    final AppEnvironment environment;
    private UiIntents intents;
    private final UiExecutor executor = new SwingUiExecutor();
    private final Scheduler scheduler = new ExecutorScheduler("swing-core-scheduler");
    final SwingPopups popups = new SwingPopups();
    final Map<String, SwingFormDialog> forms = new LinkedHashMap<>();
    final Map<String, SwingAlerts> alerts = new LinkedHashMap<>();
    final List<UiDump.ContextMenu> contexts = new ArrayList<>();
    final List<UiDump.ChooserRequest> chooserRequests = new ArrayList<>();
    private Consumer<Optional<Path>> pendingChooser;
    private Optional<Path> queuedChooser;
    MainFrameView frame;
    SwingKeyBridge keys;
    JPopupMenu lastContext;
    boolean exited;

    /** Создаёт порт без чтения бизнес-данных и без открытия окон. */
    public SwingUiPort(AppEnvironment environment) { this.environment = environment; }
    /** Связывает порт с UiIntents до запуска контроллера. */
    public void bind(UiIntents intents) { this.intents = intents; keys = new SwingKeyBridge(this); keys.install(); }
    /** Возвращает общий интерфейс намерений. */
    public UiIntents intents() { return intents; }
    /** Возвращает профиль Swing, определённый ядром. */
    @Override public ClientProfile profile() { return ClientProfile.swing(); }
    /** Возвращает исполнитель EDT. */
    @Override public UiExecutor executor() { return executor; }
    /** Возвращает фоновый планировщик; задачи контроллера сами возвращаются в EDT. */
    @Override public Scheduler scheduler() { return scheduler; }
    /** Показывает главное окно впервые. */
    @Override public void showMain(MainScreenModel model, MainWindowState restored) { if (frame != null) throw new IllegalStateException("Main shown twice"); frame = new MainFrameView(this); frame.show(model, restored); updateReady.run(); }
    /** Обновляет указанные части показанного экрана. */
    @Override public void render(MainScreenModel model, EnumSet<ScreenPart> changed) { if (frame == null) throw new IllegalStateException("Main hidden"); frame.render(model, changed); }
    /** Возвращает живую геометрию главного окна. */
    @Override public MainGeometry mainGeometry() { return frame == null ? MainGeometry.UNKNOWN : new MainGeometry(bounds(frame), (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0); }
    /** Создаёт форму соответствующего представления. */
    @Override public WindowHandle openForm(FormSession session, FormSpec spec, FormView initial, Placement placement) {
        dismissContext();
        popups.hide();
        ToolTipManager.sharedInstance().setEnabled(false);
        // JavaFX: Dialog<R> / TextInputDialog / ChoiceDialog<T> / Popup → Swing: специализированные SwingFormDialog → Web: dialog / div
        SwingFormDialog form = switch (spec.presentation()) {
            case TEXT_INPUT -> new SwingTextInput(this, session, spec, initial, placement);
            case CHOICE -> new SwingChoice(this, session, spec, initial, placement);
            case POPUP -> new SwingQuickEditPopup(this, session, spec, initial, placement);
            default -> new SwingFormDialog(this, session, spec, initial, placement);
        };
        forms.put(session.windowId(), form); refreshTooltips(); form.showLater(); return form;
    }
    /** Создаёт сообщение с живой ручкой и однократным ответом. */
    @Override public WindowHandle showAlert(AlertSpec spec, AlertSession session, Consumer<String> onButton) {
        dismissContext();
        popups.hide(); ToolTipManager.sharedInstance().setEnabled(false); String id = alertId(session);
        // JavaFX: Alert → Swing: SwingAlerts → Web: dialog
        SwingAlerts alert = new SwingAlerts(this, id, spec, session, onButton); alerts.put(id, alert); refreshTooltips(); alert.showLater(); return alert;
    }
    /** Использует id уже зарегистрированного окна ядра, сохраняя id восстановленного сеанса. */
    String alertId(AlertSession session) {
        if (session != null) return session.windowId();
        if (!(intents instanceof AppController controller)) throw new IllegalStateException("Alert requires bound AppController");
        return controller.state().windows().windows().getLast().windowId();
    }
    /** Показывает контекстное меню, открытое ядром с клавиатуры. */
    @Override public void showContextMenu(ContextTarget target, List<MenuNode> items) {
        Component owner = frame.table.table; int x = 8, y = 28;
        if (target instanceof ContextTarget.Card card) {
            for (Component component : frame.summary.getComponents()) if (component instanceof JComponent c && card.cardId().equals(c.getClientProperty("cp.id"))) { owner = c; y = c.getHeight(); break; }
        } else if (target instanceof ContextTarget.Row row) {
            frame.table.reveal(row.rowId(), RevealMode.SELECT_AND_SCROLL); int index = frame.model.table().indexOf(row.rowId());
            if (index >= 0) { Rectangle cell = frame.table.table.getCellRect(index, 2, true); x = cell.x; y = cell.y + cell.height; }
        }
        showContext(target, items, owner, x, y, null);
    }
    void context(ContextTarget target, Component owner, int x, int y) { showContext(target, intents.contextMenu(target), owner, x, y, null); }
    private void showContext(ContextTarget target, List<MenuNode> items, Component owner, int x, int y, String requestedTarget) {
        if (items.isEmpty()) return;
        // JavaFX: ContextMenu → Swing: JPopupMenu → Web: div[role=menu]
        JPopupMenu popup = new SwingMenus(intents).popup(items, target instanceof ContextTarget.Preview ? InvokeSource.FORM : InvokeSource.CONTEXT_MENU);
        lastContext = popup; contexts.clear();
        popup.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            /** Открытие наблюдается после фактического показа и заполнения меню. */
            @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent event) { }
            /** Закрытое меню больше не является открытым контекстом дампа. */
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent event) { contexts.clear(); }
            /** Отмена также удаляет наблюдаемый контекст. */
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent event) { contexts.clear(); }
        });
        popup.show(owner, x, y);
        String targetId = contextId(target, requestedTarget);
        popup.putClientProperty("cp.target", targetId);
        contexts.add(new UiDump.ContextMenu(targetId, SwingUiDumper.menuItems(popup.getComponents())));
    }
    /** Канонический id строится из фактических данных цели, включая координаты события графика. */
    static String contextId(ContextTarget target, String requestedTarget) {
        return requestedTarget != null ? requestedTarget : switch (target) {
            case ContextTarget.Row row -> "row:" + row.rowId();
            case ContextTarget.Total total -> "total:" + total.rowId();
            case ContextTarget.PastHeader past -> "pastHeader";
            case ContextTarget.Card card -> "card:" + card.cardId();
            case ContextTarget.Chart chart -> "chart:" + (int) chart.x() + "," + (int) chart.y();
            case ContextTarget.Preview preview -> "preview:" + preview.windowId() + ":" + preview.index();
        };
    }

    /** Штатный ToolTipManager скрывает окно и не показывает подсказки блокированного владельца из отложенного таймера. */
    void refreshTooltips() {
        boolean modal = forms.values().stream().anyMatch(form -> form.spec.modal() && !form.closed)
                || !alerts.isEmpty();
        ToolTipManager.sharedInstance().setEnabled(!modal);
    }
    /** Выбирает файл через JFileChooser, оставляя расширение и перезапись ядру. */
    @Override public void chooseFile(FileChooserSpec spec, Consumer<Optional<Path>> onResult) {
        chooserRequests.add(new UiDump.ChooserRequest("file", spec.mode().name(), spec.title(), spec.filterDescription(), spec.initialFolder().toString(), spec.initialName()));
        if (environment.options().isSelftest()) { requestChooser(once(onResult)); return; }
        SwingUtilities.invokeLater(() -> {
            // JavaFX: FileChooser → Swing: JFileChooser → Web: FILE_BROWSER
            JFileChooser chooser = new JFileChooser(spec.initialFolder().toFile()); SwingIcons.chooser(chooser); chooser.setDialogTitle(spec.title());
            chooser.setFileFilter(new FileNameExtensionFilter(spec.filterDescription(), spec.extensions().toArray(String[]::new)));
            if (!spec.initialName().isEmpty()) chooser.setSelectedFile(spec.initialFolder().resolve(spec.initialName()).toFile());
            int result = spec.mode() == FileChooserSpec.Mode.OPEN ? chooser.showOpenDialog(visibleOwner()) : chooser.showSaveDialog(visibleOwner());
            onResult.accept(result == JFileChooser.APPROVE_OPTION ? Optional.of(chooser.getSelectedFile().toPath()) : Optional.empty());
        });
    }
    /** Выбирает папку через штатный Swing-диалог. */
    @Override public void chooseDirectory(DirectoryChooserSpec spec, Consumer<Optional<Path>> onResult) {
        chooserRequests.add(new UiDump.ChooserRequest("directory", "DIRECTORY", spec.title(), "", spec.initialFolder().toString(), ""));
        if (environment.options().isSelftest()) { requestChooser(once(onResult)); return; }
        SwingUtilities.invokeLater(() -> {
            // JavaFX: DirectoryChooser → Swing: JFileChooser(DIRECTORIES_ONLY) → Web: FILE_BROWSER
            JFileChooser chooser = new JFileChooser(spec.initialFolder().toFile()); SwingIcons.chooser(chooser); chooser.setDialogTitle(spec.title()); chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            int result = chooser.showOpenDialog(visibleOwner()); onResult.accept(result == JFileChooser.APPROVE_OPTION ? Optional.of(chooser.getSelectedFile().toPath()) : Optional.empty());
        });
    }
    private Consumer<Optional<Path>> once(Consumer<Optional<Path>> callback) {
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(); return result -> { if (done.compareAndSet(false, true)) callback.accept(result); };
    }
    /** Задаёт ответ текущему либо следующему настоящему запросу выбора в самотесте. */
    void answerChooser(Path path) {
        if (pendingChooser == null) {
            if (queuedChooser != null) throw new IllegalStateException("Chooser answer already queued");
            queuedChooser = Optional.ofNullable(path);
        } else {
            Consumer<Optional<Path>> callback = pendingChooser; pendingChooser = null;
            callback.accept(Optional.ofNullable(path));
        }
    }
    /** Ответ потребляется только после реального запроса порта, записанного в chooserRequests. */
    private void requestChooser(Consumer<Optional<Path>> callback) {
        if (pendingChooser != null) throw new IllegalStateException("Chooser request already pending");
        if (queuedChooser != null) {
            Optional<Path> answer = queuedChooser; queuedChooser = null; callback.accept(answer);
        } else pendingChooser = callback;
    }
    /** Закрывает физическое контекстное меню при переходе к другому окну. */
    private void dismissContext() {
        if (lastContext != null) lastContext.setVisible(false);
        contexts.clear();
    }
    /** Рисует PNG из готовой сцены. */
    @Override public byte[] renderChartPng(ChartScene scene) throws IOException { return SwingChart.png(scene); }
    /** Передаёт фокус указанному ядром виджету. */
    @Override public void focus(FocusTarget target) {
        if (frame == null) return;
        switch (target) {
            case TABLE -> frame.table.table.requestFocusInWindow();
            case FILTER -> { frame.toolbar.filter().requestFocusInWindow(); frame.toolbar.filter().selectAll(); }
            case CHART -> frame.chart.requestFocusInWindow();
            case MENU_BAR -> { JMenu menu = frame.menus.getMenu(0); MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[]{frame.menus, menu, menu.getPopupMenu()}); }
        }
    }
    /** Прокручивает реальную таблицу к строке. */
    @Override public void revealRow(String rowId, RevealMode mode) { if (frame != null) frame.table.reveal(rowId, mode); }
    /** Копирует готовый текст ядра. */
    @Override public void copyToClipboard(String text) { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null); }
    /** Освобождает окна и диспетчер, затем завершает процесс по запросу ядра. */
    @Override public void exit(ExitKind kind, int code) {
        if (exited) return; exited = true; updateClose.run(); scheduler.shutdown(); if (keys != null) keys.close(); popups.hide();
        new ArrayList<>(forms.values()).forEach(SwingFormDialog::close); new ArrayList<>(alerts.values()).forEach(SwingAlerts::close);
        if (frame != null) frame.dispose();
        if (kind == ExitKind.HALT) Runtime.getRuntime().halt(code); else if (!environment.options().isSelftest()) System.exit(code);
    }
    Window owner(String id) { SwingFormDialog form = forms.get(id); if (form != null) return form.dialog; SwingAlerts alert = alerts.get(id); if (alert != null) return alert.dialog; return frame; }
    Window visibleOwner() { if (!forms.isEmpty()) return new ArrayList<>(forms.values()).getLast().dialog; return frame != null && frame.isShowing() ? frame : null; }
    /** Читает реальные границы физического окна. */
    public static WindowBounds bounds(Window window) { Rectangle r = window.getBounds(); return new WindowBounds(r.x, r.y, r.width, r.height); }
    /** Применяет восстановленные границы, только если они видны на подключённом экране. */
    public static void place(Window window, WindowBounds restored, Window owner) {
        if (restored != null && restored.width() >= 100 && restored.height() >= 60) {
            Rectangle r = new Rectangle((int) restored.x(), (int) restored.y(), (int) restored.width(), (int) restored.height());
            for (GraphicsDevice screen : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) if (screen.getDefaultConfiguration().getBounds().intersects(r)) { window.setBounds(r); return; }
        }
        if (owner instanceof RootPaneContainer ownerPane && window instanceof RootPaneContainer childPane
                && ownerPane.getContentPane().isShowing()) {
            Container ownerContent = ownerPane.getContentPane(), childContent = childPane.getContentPane();
            Point origin = ownerContent.getLocationOnScreen();
            WindowBounds centered = Placement.centerContent(new WindowBounds(origin.x, origin.y, ownerContent.getWidth(), ownerContent.getHeight()),
                    childContent.getWidth(), childContent.getHeight());
            Point offset = SwingUtilities.convertPoint(childContent, 0, 0, window);
            int x = (int) Math.round(centered.x()) - offset.x, y = (int) Math.round(centered.y()) - offset.y;
            Rectangle screen = owner.getGraphicsConfiguration().getBounds();
            Insets taskbar = Toolkit.getDefaultToolkit().getScreenInsets(owner.getGraphicsConfiguration());
            x = Math.max(screen.x + taskbar.left, Math.min(x, screen.x + screen.width - taskbar.right - window.getWidth()));
            y = Math.max(screen.y + taskbar.top, Math.min(y, screen.y + screen.height - taskbar.bottom - window.getHeight()));
            window.setLocation(x, y);
        } else window.setLocationRelativeTo(owner);
    }
}
