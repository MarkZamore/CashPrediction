package ru.cashprediction.core.app.session.capture;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.UnaryOperator;
import java.util.function.Consumer;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.app.fake.FakeUiPort;
import ru.cashprediction.core.app.fake.FakeWindowHandle;
import ru.cashprediction.core.app.flow.*;
import ru.cashprediction.core.document.*;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.session.MainWindowState;
import ru.cashprediction.core.session.SessionRecorder;
import ru.cashprediction.core.session.StatefulWindow;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.ui.alert.AlertSession;
import ru.cashprediction.core.ui.alert.AlertSpec;
import ru.cashprediction.core.ui.form.*;

/** Изолированный контекст захвата: неизвестный вызов сразу проваливает тест. */
final class CaptureContext implements InvocationHandler {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    final FakeUiPort port;
    final AppEnvironment environment;
    final PlanDocument document;
    final ExternalChangeGuard external = new ExternalChangeGuard();
    final FlowContext flow;
    final EditFlow edits;
    final ExitFlow exit;
    final FileFlow files;
    final List<FormSession> sessions = new ArrayList<>();
    final List<RestoredAlert> alerts = new ArrayList<>();
    final List<String> statusKeys = new ArrayList<>();
    final List<List<Object>> statusArguments = new ArrayList<>();
    SessionRecorder recorder;
    boolean failAlertOpen;
    boolean failFormOpenAfterShown;
    boolean past;
    boolean delayedShow;
    String selection = "";
    MainWindowState shown;
    Placement placement;
    int shownCount;
    int registrations;
    int unregistrations;

    /** Сеанс сообщения и необработанный callback ответа для проверки защиты фабрики от повторов. */
    record RestoredAlert(AlertSession session, WindowHandle handle, Consumer<String> onButton) { }

    /** Создаёт контекст без обращения к реестру и файловым хранилищам. */
    CaptureContext(Path home, ClientProfile profile) {
        environment = AppEnvironment.from(LaunchOptions.parse(List.of("--home", home.toString(),
                "--registry", "memory", "--today", TODAY.toString()), new Properties()));
        port = new FakeUiPort(profile);
        document = new PlanDocument(SamplePlan.create(TODAY), null, () -> TODAY);
        flow = (FlowContext) Proxy.newProxyInstance(FlowContext.class.getClassLoader(),
                new Class<?>[]{FlowContext.class}, this);
        edits = new EditFlow(flow);
        exit = new ExitFlow(flow);
        files = new FileFlow(flow);
    }

    /** Строит состояние с настоящим прогнозом текущего документа. */
    AppState state() {
        DocumentView view = new DocumentView(document.plan(), document.file().orElse(null), document.isDirty(),
                false, "", false, "", document.forecast(), "", document.loadDiagnostics());
        return new AppState(1, port.profile(), TODAY, environment.cashMemory(), null, view,
                document.viewState(), selection, past, AppSettings.defaults(), null, List.of(), null, null, "");
    }

    /** {@inheritDoc} Обрабатывает только используемые мостом операции контракта. */
    @Override
    @SuppressWarnings("unchecked")
    public Object invoke(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "port" -> port;
            case "environment" -> environment;
            case "document" -> document;
            case "state" -> state();
            case "externalChanges" -> external;
            case "edits" -> edits;
            case "exit" -> exit;
            case "files" -> files;
            case "recorder" -> recorder;
            case "refresh" -> null;
            case "status" -> {
                statusKeys.add((String) args[1]);
                statusArguments.add(List.copyOf(java.util.Arrays.asList((Object[]) args[2])));
                yield null;
            }
            case "updateView" -> {
                document.setViewState(((UnaryOperator<ViewState>) args[0]).apply(document.viewState()));
                yield null;
            }
            case "setPastExpanded" -> { past = (boolean) args[0]; yield null; }
            case "setSelection" -> { selection = (String) args[0]; yield null; }
            case "showMain" -> { shown = (MainWindowState) args[0]; shownCount++; yield null; }
            case "openForm" -> open((FormRequest) args[0], (Placement) args[1], (Consumer<Object>) args[2]);
            case "showAlert" -> port.showAlert((AlertSpec) args[0], null,
                    args[1] == null ? button -> { } : (Consumer<String>) args[1]);
            case "showRestoredAlert" -> alert((AlertSpec) args[0], (WindowState) args[1],
                    (Consumer<StatefulWindow>) args[2], (Consumer<String>) args[3]);
            default -> throw new AssertionError("Unexpected FlowContext call: " + method.getName());
        };
    }

    /** Моделирует восстановление сообщения контроллером, включая ранний и отложенный shown. */
    private WindowHandle alert(AlertSpec spec, WindowState restored, Consumer<StatefulWindow> onShown,
                               Consumer<String> onButton) {
        if (failAlertOpen) throw new IllegalStateException("alert open failure");
        AlertSession session = new AlertSession(restored.id(), restored.ownerId(), spec, new AlertSession.Host() {
            /** {@inheritDoc} */
            @Override public void registered(AlertSession value) {
                registrations++;
                if (recorder != null) recorder.register(value);
            }
            /** {@inheritDoc} */
            @Override public void unregistered(AlertSession value) {
                unregistrations++;
                if (recorder != null) recorder.unregister(value);
            }
        });
        session.applyState(restored);
        Consumer<String> answer = button -> { session.closed(); onButton.accept(button); };
        WindowHandle handle = port.showAlert(spec, session, answer);
        session.attach(handle);
        alerts.add(new RestoredAlert(session, handle, answer));
        if (!delayedShow) session.shown();
        session.whenShown(onShown::accept);
        return handle;
    }

    /** Моделирует общий путь контроллера: applyState до показа, attach, затем shown. */
    private FormSession open(FormRequest request, Placement position, Consumer<Object> onResult) {
        placement = position == null ? Placement.centered(WindowState.MAIN_OWNER) : position;
        String id = request.restored() == null ? "fresh" + (sessions.size() + 1) : request.restored().id();
        FormSession session = new FormSession(request.type(), request.modal(), request.logic(),
                new FormContext(id, placement.ownerId(), request.context(), state()),
                new FormSession.Host() {
                    /** {@inheritDoc} */
                    @Override public void registered(FormSession value) { registrations++; }
                    /** {@inheritDoc} */
                    @Override public void unregistered(FormSession value) { }
                    /** {@inheritDoc} */
                    @Override public void touched(FormSession value) { }
                    /** {@inheritDoc} */
                    @Override public void closed(FormSession value, Object result) { if (onResult != null) onResult.accept(result); }
                    /** {@inheritDoc} */
                    @Override public void openChild(FormSession parent, ru.cashprediction.core.session.WindowState child) { }
                    /** {@inheritDoc} */
                    @Override public void applied(FormSession value, Object action) { if (onResult != null) onResult.accept(action); }
                });
        if (request.restored() != null) session.applyState(request.restored());
        FakeWindowHandle handle = new FakeWindowHandle(session.windowId(), placement.bounds());
        session.attach(handle);
        if (!delayedShow) session.shown();
        sessions.add(session);
        if (failFormOpenAfterShown) {
            // Контроллер очищает неудачный показ до передачи ошибки фабрике, не доставляя результат формы.
            session.abortOpening();
            throw new IllegalStateException("form open failure after shown");
        }
        return session;
    }
}
