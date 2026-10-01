package ru.cashprediction.core.app.edit;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.app.flow.*;
import ru.cashprediction.core.document.*;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.model.*;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.ui.alert.AlertSpec;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.view.status.StatusLevel;

/** Изолированный контекст потоков: реальная модель и формы, виртуальные окна и часы без файлов и реестра. */
final class EditHarness {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 5);
    final PlanDocument document;
    final FlowContext context;
    final EditFlow edits;
    final ToolsFlow tools;
    final List<FormSession> forms = new ArrayList<>();
    final List<AlertSpec> alerts = new ArrayList<>();
    final Map<String, FormSession> windows = new LinkedHashMap<>();
    final List<Pending> tasks = new ArrayList<>();
    Consumer<String> answer;
    Placement placement;
    String statusKey = "";
    String statusText = "";
    String clipboard = "";
    int fronts;
    int refreshes;
    long elapsed;
    boolean shown;

    /** Запланированное действие с виртуальным сроком и отменой. */
    private static final class Pending {
        final Runnable action;
        final long due;
        boolean cancelled;
        Pending(Runnable action, long due) { this.action = action; this.due = due; }
    }

    EditHarness(Plan plan) {
        document = new PlanDocument(plan, null, () -> TODAY);
        Scheduler scheduler = proxy(Scheduler.class, (p, m, a) -> {
            if (m.getName().equals("schedule")) {
                Pending pending = new Pending((Runnable) a[0], elapsed + ((Duration) a[1]).toMillis());
                tasks.add(pending);
                return (Scheduler.Task) () -> pending.cancelled = true;
            }
            throw new AssertionError(m.getName());
        });
        UiPort port = proxy(UiPort.class, (p, m, a) -> {
            return switch (m.getName()) {
                case "scheduler" -> scheduler;
                case "executor" -> UiExecutor.direct();
                case "profile" -> ClientProfile.swing();
                case "copyToClipboard" -> { clipboard = (String) a[0]; yield null; }
                default -> throw new AssertionError("Unexpected port: " + m.getName());
            };
        });
        context = proxy(FlowContext.class, (p, m, a) -> {
            return switch (m.getName()) {
                case "port" -> port;
                case "document" -> document;
                case "state" -> state();
                case "edits" -> edits();
                case "tools" -> tools();
                case "singleInstance" -> windows.values().stream().filter(s -> s.windowType().name().equals(a[0])).findFirst();
                case "session" -> Optional.ofNullable(windows.get(a[0]));
                case "refresh" -> { refreshes++; yield null; }
                case "showMain" -> { shown = true; yield null; }
                case "updateView" -> {
                    @SuppressWarnings("unchecked")
                    UnaryOperator<ViewState> change = (UnaryOperator<ViewState>) a[0];
                    document.setViewState(change.apply(document.viewState()));
                    yield null;
                }
                case "status" -> {
                    statusKey = (String) a[1];
                    statusText = UiText.get(statusKey, (Object[]) a[2]);
                    yield null;
                }
                case "showAlert" -> {
                    alerts.add((AlertSpec) a[0]);
                    @SuppressWarnings("unchecked")
                    Consumer<String> callback = (Consumer<String>) a[1];
                    answer = callback;
                    yield handle();
                }
                case "openForm" -> {
                    @SuppressWarnings("unchecked")
                    Consumer<Object> callback = (Consumer<Object>) a[2];
                    yield open((FormRequest) a[0], (Placement) a[1], callback);
                }
                default -> throw new AssertionError("Unexpected context: " + m.getName());
            };
        });
        edits = new EditFlow(context);
        tools = new ToolsFlow(context);
    }

    /** Отложенный доступ нужен лишь во время конструирования прокси. */
    private EditFlow edits() { return edits; }
    private ToolsFlow tools() { return tools; }

    /** Срез содержит реальный прогноз либо его ошибку, как у контроллера. */
    AppState state() {
        Forecast forecast = null;
        String error = "";
        try { forecast = document.forecast(); } catch (RuntimeException ex) { error = ex.getMessage(); }
        DocumentView view = new DocumentView(document.plan(), null, document.isDirty(), document.canUndo(),
                document.undoDescription().orElse(""), document.canRedo(), document.redoDescription().orElse(""),
                forecast, error, document.loadDiagnostics());
        return new AppState(0, ClientProfile.swing(), TODAY, Path.of("CashMemory"), null, view,
                document.viewState(), "", true, AppSettings.defaults(), RecorderStatus.NOT_STARTED,
                List.of(), OpenWindows.NONE, StatusMessages.EMPTY, "");
    }

    /** Создаёт настоящий сеанс; исключение обработчика результата не удаляет окно. */
    private FormSession open(FormRequest request, Placement place, Consumer<Object> callback) {
        placement = place;
        String id = "w" + (forms.size() + 1);
        FormSession.Host host = proxy(FormSession.Host.class, (p, m, a) -> {
            FormSession session = (FormSession) a[0];
            switch (m.getName()) {
                case "closed", "applied" -> { if (callback != null) callback.accept(a[1]); }
                case "unregistered" -> windows.remove(session.windowId());
                case "registered", "touched" -> { }
                default -> throw new AssertionError(m.getName());
            }
            return null;
        });
        FormSession session = new FormSession(request.type(), request.modal(), request.logic(),
                new FormContext(id, WindowState.MAIN_OWNER, request.context(), state()), host);
        forms.add(session);
        windows.put(id, session);
        session.attach(handle());
        session.shown();
        return session;
    }

    private WindowHandle handle() {
        return proxy(WindowHandle.class, (p, m, a) -> {
            if (m.getName().equals("toFront")) fronts++;
            return m.getReturnType() == boolean.class ? true : null;
        });
    }

    /** Продвигает время без сна, применяя только достигшие срока задачи. */
    void advance(long millis) {
        elapsed += millis;
        for (Pending pending : List.copyOf(tasks)) {
            if (!pending.cancelled && pending.due <= elapsed) {
                pending.cancelled = true;
                pending.action.run();
            }
        }
    }

    FormSession form() { return forms.getLast(); }
    AlertSpec alert() { return alerts.getLast(); }
    void answer(String button) { Consumer<String> callback = answer; answer = null; callback.accept(button); }

    /** Минимальный план с началом до сегодняшней даты. */
    static Plan plan() {
        return Plan.empty("План", TODAY.minusDays(4)).withHorizon(new Horizon.Months(3)).withStart(TODAY.minusDays(4), Money.ofMajor(1000));
    }

    /** Правило с событием сегодня, без выходного переноса. */
    static RecurringRule rule() {
        return new RecurringRule(new RuleId("r1"), "Доход", Kind.INCOME, Money.ofMajor(100),
                "", new Recurrence.Monthly(5, 1), null, null, WeekendPolicy.NONE, true, "");
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
