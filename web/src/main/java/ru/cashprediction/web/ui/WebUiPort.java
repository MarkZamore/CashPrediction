package ru.cashprediction.web.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.ui.alert.*;
import ru.cashprediction.core.ui.command.HotkeyTable;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.json.*;
import ru.cashprediction.core.ui.menu.*;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.view.*;
import ru.cashprediction.core.ui.view.chart.ChartScene;

/** Порт ядра, публикующий эффекты и сохраняющий живые окна для повторного подключения вкладки. */
public final class WebUiPort implements UiPort {
    private final ControllerThread thread;
    private final EffectLog log;
    private final InteractionRegistry interactions = new InteractionRegistry();
    private final Map<String, Handle> windows = new LinkedHashMap<>();
    private final boolean testApi;
    private AppController controller;
    private MainScreenModel screen;
    private MainGeometry geometry = new MainGeometry(null, false);
    private String tab = "";
    private String reloadTab;
    private String echoWindow = "";
    private long echoRev;
    private String overlay;
    private java.util.function.BiConsumer<ExitKind, Integer> onExit = (kind, code) -> { };
    // Завершение читают поток остановки сервера и shutdown hook, не только поток контроллера.
    private volatile ExitKind exitKind;
    private volatile int exitCode;
    private Runnable mainReady = () -> { };

    /** Подключает готовность основной модели до запуска контроллера. */
    public void onMainReady(Runnable callback) { mainReady = java.util.Objects.requireNonNull(callback); }

    /** Создаёт порт; все его операции выполняются на переданном потоке. */
    public WebUiPort(ControllerThread thread, EffectLog log, boolean testApi) {
        this.thread = thread; this.log = log; this.testApi = testApi;
    }
    /** Привязывает единственный контроллер перед запуском. */
    public void bind(AppController controller) { thread.check(); this.controller = controller; }
    /** Возвращает последнюю показанную модель, не строя собственную модель интерфейса. */
    public MainScreenModel screen() { thread.check(); return screen; }
    /** Отмечает вкладку на время синхронного намерения и очищает контекст в finally вызывающего кода. */
    public void intentContext(String tab, String window, long rev) {
        thread.check(); this.tab = tab; echoWindow = window; echoRev = rev;
    }
    /** Собирает полный слепок для reload из последних моделей живых окон. */
    public WebBootstrap bootstrap() {
        thread.check();
        // Модель до showMain нужна только для страницы под диалогом восстановления; рендеринг не вызывается.
        MainScreenModel model = screen == null ? controller.bootstrapPlaceholder() : screen;
        Map<String, String> texts = new LinkedHashMap<>();
        UiText.keys().stream().filter(key -> key.startsWith(WebBootstrap.OFFLINE_PREFIX)
                || WebBootstrap.CHROME_TEXT_KEYS.contains(key)).sorted().forEach(key -> texts.put(key, UiText.get(key)));
        return new WebBootstrap(log.sequence(), "web", testApi, profile(), model, HotkeyTable.bindings(ClientKind.WEB),
                windows.values().stream().map(Handle::openEffect).toList(), overlay == null && screen == null ? "RECOVERY_PENDING" : overlay, texts);
    }
    /** Возвращает живую форму, не создавая закрытое окно заново. */
    public Optional<FormSession> form(String id) {
        thread.check(); Handle handle = windows.get(id);
        return handle == null ? Optional.empty() : Optional.ofNullable(handle.form);
    }
    /** Передаёт подтверждение реального показа формы. */
    public void formShown(String id) {
        thread.check(); Handle handle = windows.get(id);
        if (handle != null && handle.form != null) { handle.visible = true; handle.form.shown(); }
    }
    /** Передаёт подтверждение реального показа сообщения, включая восстановленное. */
    public void alertShown(String id) {
        thread.check(); Handle handle = windows.get(id);
        if (handle != null && handle.alert != null) { handle.visible = true; if (handle.alertSession != null) handle.alertSession.shown(); }
    }
    /** Принимает первый ответ точной кнопкой сообщения. */
    public void answer(String id, String button) { thread.check(); interactions.answer(id, button); }
    /** Обновляет живые границы ручки окна перед снимком формы. */
    public void bounds(String id, WindowBounds value) {
        thread.check(); Handle handle = windows.get(id);
        if (handle != null) { handle.bounds = value; if (handle.form != null) handle.form.boundsChanged(value); }
    }
    /** Сохраняет принятый текст виджета для полного bootstrap, когда ядро оставляет поле без обновления. */
    public void typed(String id, String field, String raw) {
        thread.check(); Handle handle = windows.get(id);
        if (handle != null && handle.form != null) handle.typed.put(field, raw);
    }
    /** Запоминает геометрию главного окна, присланную вкладкой. */
    public void geometry(WindowBounds bounds, boolean maximized) { thread.check(); geometry = new MainGeometry(bounds, maximized); }
    /** Проверяет остановку; после неё намерения не изменяют состояние. */
    public boolean stopped() { thread.check(); return overlay != null; }
    /** Привязывает остановку владельца сервера после публикации последнего эффекта. */
    public void onExit(java.util.function.BiConsumer<ExitKind, Integer> callback) { onExit = callback; }
    /** Возвращает вид завершения для освобождения инфраструктуры без чистой записи после сбоя. */
    public ExitKind exitKind() { return exitKind; }
    /** Возвращает код завершения процесса. */
    public int exitCode() { return exitCode; }
    /** {@inheritDoc} */
    @Override public ClientProfile profile() { return ClientProfile.web(); }
    /** {@inheritDoc} */
    @Override public UiExecutor executor() { return thread; }
    /** {@inheritDoc} */
    @Override public Scheduler scheduler() { return thread; }
    /** {@inheritDoc} */
    @Override public void showMain(MainScreenModel model, MainWindowState restored) {
        thread.check(); if (screen != null) throw new IllegalStateException("showMain twice");
        if (restored != null) geometry = new MainGeometry(restored.bounds(), restored.maximized());
        screen = model; log.append(new WebEffect.Inert(false)); log.append(new WebEffect.Screen(model, EnumSet.allOf(ScreenPart.class)));
        mainReady.run();
    }
    /** {@inheritDoc} */
    @Override public void render(MainScreenModel model, EnumSet<ScreenPart> changed) {
        thread.check(); screen = model; log.append(new WebEffect.Screen(model, changed));
    }
    /** {@inheritDoc} */
    @Override public MainGeometry mainGeometry() { thread.check(); return geometry; }
    /** {@inheritDoc} */
    @Override public WindowHandle openForm(FormSession session, FormSpec spec, FormView initial, Placement placement) {
        thread.check();
        // JavaFX: Dialog → Swing: JDialog → Web: dialog
        Handle handle = new Handle(session.windowId(), placement, session, spec, initial, null, null);
        windows.put(handle.id, handle); log.append(handle.openEffect());
        return handle;
    }
    /** {@inheritDoc} */
    @Override public WindowHandle showAlert(AlertSpec spec, AlertSession session, Consumer<String> onButton) {
        thread.check();
        String id = session != null ? session.windowId() : controller.state().windows().windows().getLast().windowId();
        String owner = session != null ? session.ownerId() : controller.state().windows().windows().getLast().ownerId();
        WindowBounds bounds = session == null ? null : session.captureState().bounds();
        // JavaFX: Alert → Swing: SwingAlerts (JDialog) → Web: dialog
        Handle handle = new Handle(id, Placement.restored(owner, bounds), null, null, null, spec, session);
        windows.put(id, handle);
        String originTab = tab;
        interactions.open(id, spec, button -> {
            handle.close();
            String previous = reloadTab;
            reloadTab = originTab;
            try { onButton.accept(button); } finally { reloadTab = previous; }
        });
        if (screen == null) log.append(new WebEffect.Inert(true));
        log.append(handle.openEffect()); return handle;
    }
    /** {@inheritDoc} */
    @Override public void showContextMenu(ContextTarget target, List<MenuNode> items) {
        thread.check();
        // JavaFX: ContextMenu → Swing: JPopupMenu → Web: div[role=menu]
        log.append(new WebEffect.ContextMenu(tab, target, items));
    }
    /** {@inheritDoc} */
    @Override public void chooseFile(FileChooserSpec spec, Consumer<Optional<Path>> result) {
        // JavaFX: FileChooser → Swing: JFileChooser → Web: FileBrowserForm
        throw new IllegalStateException("SERVER_BROWSER");
    }
    /** {@inheritDoc} */
    @Override public void chooseDirectory(DirectoryChooserSpec spec, Consumer<Optional<Path>> result) {
        // JavaFX: DirectoryChooser → Swing: JFileChooser → Web: FileBrowserForm
        throw new IllegalStateException("SERVER_BROWSER");
    }
    /** {@inheritDoc} */
    @Override public byte[] renderChartPng(ChartScene scene) throws IOException { thread.check(); return WebChartPng.render(scene); }
    /** {@inheritDoc} */
    @Override public void focus(FocusTarget target) { thread.check(); log.append(new WebEffect.Focus(target)); }
    /** {@inheritDoc} */
    @Override public void revealRow(String rowId, RevealMode mode) { thread.check(); log.append(new WebEffect.Reveal(rowId, mode)); }
    /** {@inheritDoc} */
    @Override public void copyToClipboard(String text) { thread.check(); log.append(new WebEffect.Clipboard(text)); }
    /** {@inheritDoc} */
    @Override public void reloadPage() {
        thread.check(); if (!stopped()) log.append(new WebEffect.Reload(reloadTab == null ? tab : reloadTab));
    }
    /** {@inheritDoc} */
    @Override public void exit(ExitKind kind, int code) {
        thread.check(); if (overlay != null) return;
        exitKind = kind; exitCode = code;
        overlay = kind == ExitKind.WEB_STOPPED || kind == ExitKind.CLEAN ? "STOPPED" : "CRASHED";
        ExitKind web = overlay.equals("STOPPED") ? ExitKind.WEB_STOPPED : ExitKind.WEB_CRASHED;
        try {
            String title = web == ExitKind.WEB_STOPPED ? UiText.get("offline.stopped.title") : UiText.get("offline.crashed.title");
            String text = web == ExitKind.WEB_STOPPED ? UiText.get("offline.stopped.text") : UiText.get("offline.crashed.text");
            log.append(new WebEffect.Exit(web, title, text));
        } finally { thread.shutdown(); onExit.accept(kind, code); }
    }

    /** Живая ручка браузерного окна; публикация эффекта не означает реальный показ. */
    private final class Handle implements WindowHandle {
        private final String id;
        private final Placement placement;
        private final FormSession form;
        private final FormSpec spec;
        private final AlertSession alertSession;
        private FormView view;
        private final Map<String, String> typed = new LinkedHashMap<>();
        private AlertSpec alert;
        private WindowBounds bounds;
        private boolean visible;
        private boolean closed;
        private Handle(String id, Placement placement, FormSession form, FormSpec spec, FormView view,
                AlertSpec alert, AlertSession alertSession) {
            this.id = id; this.placement = placement; this.form = form; this.spec = spec; this.view = view;
            this.alert = alert; this.alertSession = alertSession; bounds = placement.bounds();
        }
        private WebEffect openEffect() {
            return form == null ? new WebEffect.AlertOpen(id, alert, new Placement(placement.ownerId(), bounds, placement.anchor()))
                    : new WebEffect.FormOpen(id, placement.ownerId(),
                    form.modal(), new Placement(placement.ownerId(), bounds, placement.anchor()), spec, view, chooserRequest());
        }
        /** Возвращает фактический запрос выбора из логики формы, не реконструируя его из подписей. */
        private ru.cashprediction.core.ui.dump.UiDump.ChooserRequest chooserRequest() {
            if (!(form.logic() instanceof ru.cashprediction.core.ui.forms.simple.FileBrowserForm browser)) return null;
            var file = browser.fileSpec();
            if (file != null) return new ru.cashprediction.core.ui.dump.UiDump.ChooserRequest("file", file.mode().name(),
                    file.title(), file.filterDescription(), file.initialFolder().toString(), file.initialName());
            var directory = browser.directorySpec();
            return new ru.cashprediction.core.ui.dump.UiDump.ChooserRequest("directory", "", directory.title(), "",
                    directory.initialFolder().toString(), "");
        }
        /** {@inheritDoc} */
        @Override public void update(FormView next) {
            thread.check(); if (closed || next.revision() < view.revision()) return;
            Map<String, FieldView> fields = new LinkedHashMap<>();
            next.fields().forEach((key, field) -> {
                String value = field.value();
                if (value == null) value = typed.getOrDefault(key, view.fields().containsKey(key) ? view.fields().get(key).value() : null);
                else typed.put(key, value);
                fields.put(key, new FieldView(value, field.visible(), field.enabled(), field.readOnly(), field.label(),
                        field.options(), field.tooltip(), field.min(), field.max()));
            });
            view = new FormView(next.revision(), next.page(), next.header(), fields, next.problem(), next.buttons(),
                    next.results(), next.preview(), next.details(), next.detailsExpanded());
            log.append(new WebEffect.FormViewUpdate(id, view, id.equals(echoWindow) ? tab : "", id.equals(echoWindow) ? echoRev : 0));
        }
        /** {@inheritDoc} */
        @Override public void updateAlert(AlertSpec next) {
            thread.check(); if (closed) return; alert = next; interactions.update(id, next); log.append(new WebEffect.AlertUpdate(id, next));
        }
        /** {@inheritDoc} */
        @Override public void close() {
            thread.check(); if (closed) return; closed = true; visible = false; windows.remove(id); interactions.close(id);
            log.append(form == null ? new WebEffect.AlertClose(id) : new WebEffect.FormClose(id));
            if (alertSession != null) alertSession.closed();
        }
        /** {@inheritDoc} */
        @Override public void toFront() { thread.check(); if (!closed && form != null) log.append(new WebEffect.FormFront(id)); }
        /** {@inheritDoc} */
        @Override public WindowBounds bounds() { thread.check(); return bounds; }
        /** {@inheritDoc} */
        @Override public boolean showing() { thread.check(); return visible && !closed; }
    }
}
