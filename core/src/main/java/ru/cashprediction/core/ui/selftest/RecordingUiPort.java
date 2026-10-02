package ru.cashprediction.core.ui.selftest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.ui.alert.*;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.menu.*;
import ru.cashprediction.core.ui.view.*;
import ru.cashprediction.core.ui.view.chart.ChartScene;
import ru.cashprediction.core.ui.dump.UiDump;

/** Записывающий порт моделей: сохраняет порядок окон и настоящие обратные вызовы. */
final class RecordingUiPort implements UiPort {
    final ClientProfile profile;
    final VirtualScheduler scheduler = new VirtualScheduler();
    final List<Handle> windows = new ArrayList<>();
    final List<UiDump.ChooserRequest> choosers = new ArrayList<>();
    MainScreenModel screen;
    MainGeometry geometry = new MainGeometry(new WindowBounds(0, 0, 1200, 800), false);
    FocusTarget focus = FocusTarget.TABLE;
    List<MenuNode> context = List.of();
    String contextTarget = "", clipboard = "";
    Consumer<Optional<Path>> chooser;
    Optional<Path> queuedChooser;
    boolean exited;
    int exitCode;
    java.util.function.Supplier<String> alertIdentity = () -> "alert" + windows.size();
    RecordingUiPort(ClientProfile profile) { this.profile = profile; }

    /** Модель открытого окна с ручкой, которую получает сеанс ядра. */
    final class Handle implements WindowHandle {
        FormSession form;
        FormSpec spec;
        FormView view;
        AlertSpec alert;
        AlertSession alertSession;
        Consumer<String> answer;
        Placement placement = Placement.centered(WindowState.MAIN_OWNER);
        boolean open = true;
        String id;
        /** Обновляет вид формы. */
        @Override public void update(FormView value) { view = value; }
        /** Обновляет сообщение. */
        @Override public void updateAlert(AlertSpec value) { alert = value; }
        /** Закрывает ручку без повторного события пользователя. */
        @Override public void close() { open = false; }
        /** Переносит окно на вершину порядка фокуса. */
        @Override public void toFront() { windows.remove(this); windows.add(this); }
        /** Возвращает границы, переданные ядром. */
        @Override public WindowBounds bounds() { return placement.bounds(); }
        /** Возвращает состояние показа. */
        @Override public boolean showing() { return open; }
    }
    Handle top() { return windows.stream().filter(h -> h.open).reduce((a, b) -> b).orElse(null); }
    Handle modal() { return windows.stream().filter(h -> h.open && (h.alert != null || h.spec.modal())).reduce((a, b) -> b).orElse(null); }

    /** Возвращает профиль клиента. */
    @Override public ClientProfile profile() { return profile; }
    /** Выполняет события в потоке сценария. */
    @Override public UiExecutor executor() { return UiExecutor.direct(); }
    /** Возвращает виртуальный планировщик. */
    @Override public Scheduler scheduler() { return scheduler; }
    /** Записывает первое главное окно. */
    @Override public void showMain(MainScreenModel model, MainWindowState restored) {
        screen = model;
        if (restored != null && restored.bounds() != null && restored.bounds().width() >= 400 && restored.bounds().height() >= 300)
            geometry = new MainGeometry(restored.bounds(), restored.maximized());
    }
    /** Записывает актуальную модель экрана. */
    @Override public void render(MainScreenModel model, EnumSet<ScreenPart> changed) { screen = model; }
    /** Возвращает размер, установленный сценарием. */
    @Override public MainGeometry mainGeometry() { return geometry; }
    /** Открывает модель формы и уведомляет сеанс о показе. */
    @Override public WindowHandle openForm(FormSession session, FormSpec spec, FormView initial, Placement placement) {
        // JavaFX: Dialog → Swing: JDialog → Web: dialog
        Handle h = new Handle(); h.form = session; h.spec = spec; h.view = initial;
        h.id = session.windowId(); h.placement = placement; windows.add(h);
        session.attach(h); session.shown(); return h;
    }
    /** Открывает сообщение с одноразовым обратным вызовом. */
    @Override public WindowHandle showAlert(AlertSpec spec, AlertSession session, Consumer<String> onButton) {
        // JavaFX: Alert → Swing: SwingAlert → Web: dialog
        Handle h = new Handle(); h.alert = spec; h.alertSession = session; h.answer = onButton;
        h.id = session == null ? alertIdentity.get() : session.windowId(); windows.add(h);
        if (session != null) session.shown(); return h;
    }
    /** Записывает контекстное меню. */
    @Override public void showContextMenu(ContextTarget target, List<MenuNode> items) {
        // JavaFX: ContextMenu → Swing: JPopupMenu → Web: menu
        contextTarget = switch (target) {
            case ContextTarget.Row row -> "row:" + row.rowId();
            case ContextTarget.Total total -> "total:" + total.rowId();
            case ContextTarget.PastHeader ignored -> "pastHeader";
            case ContextTarget.Card card -> "card:" + card.cardId();
            case ContextTarget.Preview preview -> "preview:" + preview.windowId() + ":" + preview.index();
            case ContextTarget.Chart ignored -> "chart";
        };
        context = List.copyOf(items);
    }
    /** Записывает запрос выбора и принимает заготовленный ответ. */
    @Override public void chooseFile(FileChooserSpec spec, Consumer<Optional<Path>> onResult) {
        // JavaFX: FileChooser → Swing: JFileChooser → Web: FileBrowserForm
        choosers.add(new UiDump.ChooserRequest("file", spec.mode().name(), spec.title(), spec.filterDescription(),
                spec.initialFolder().toString(), spec.initialName())); choose(onResult);
    }
    /** Записывает запрос выбора папки. */
    @Override public void chooseDirectory(DirectoryChooserSpec spec, Consumer<Optional<Path>> onResult) {
        // JavaFX: DirectoryChooser → Swing: JFileChooser → Web: FileBrowserForm
        choosers.add(new UiDump.ChooserRequest("directory", "", spec.title(), "", spec.initialFolder().toString(), "")); choose(onResult);
    }
    private void choose(Consumer<Optional<Path>> callback) {
        if (chooser != null) throw new IllegalStateException("chooser pending");
        if (queuedChooser != null) { var value = queuedChooser; queuedChooser = null; callback.accept(value); }
        else chooser = callback;
    }
    void chooseAnswer(Optional<Path> value) {
        if (chooser == null) { if (queuedChooser != null) throw new IllegalStateException("chooser queued"); queuedChooser = value; }
        else { var callback = chooser; chooser = null; callback.accept(value); }
    }
    /** Сообщает о невозможности рисования без графического клиента. */
    @Override public byte[] renderChartPng(ChartScene scene) throws IOException { throw new IOException("model PNG unavailable"); }
    /** Запоминает область фокуса. */
    @Override public void focus(FocusTarget target) { focus = target; }
    /** Запоминает фокус таблицы при переходе к строке. */
    @Override public void revealRow(String rowId, RevealMode mode) { focus = FocusTarget.TABLE; }
    /** Записывает буфер обмена модели. */
    @Override public void copyToClipboard(String text) { clipboard = text; }
    /** Записывает завершение, не останавливая JVM тестов. */
    @Override public void exit(ExitKind kind, int code) { exited = true; exitCode = code; scheduler.shutdown(); }
}
