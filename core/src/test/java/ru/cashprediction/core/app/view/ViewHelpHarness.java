package ru.cashprediction.core.app.view;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.app.fake.FakeUiPort;
import ru.cashprediction.core.app.flow.*;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.alert.AlertSpec;
import ru.cashprediction.core.ui.form.*;

/** Изолированный контекст потоков вида и справки: только память, без запуска приложения и хранилищ. */
final class ViewHelpHarness {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    final FakeUiPort port;
    final PlanDocument document;
    final AppEnvironment environment;
    final FlowContext context;
    final ViewFlow views;
    final HelpFlow help;
    final EditFlow edits;
    final List<List<Object>> statuses = new ArrayList<>();
    String selection = "";
    boolean pastExpanded;
    boolean failedForecast;
    Forecast overrideForecast;
    int viewUpdates;
    int refreshes;
    AppSettings settings = AppSettings.defaults();
    FormRequest request;
    FormSession form;
    FormSession quickEdit;
    int formClosures;
    boolean shown;
    Consumer<Object> onResult;

    ViewHelpHarness(Plan plan, Path home) {
        this(plan, home, ClientProfile.swing());
    }

    ViewHelpHarness(Plan plan, Path home, ClientProfile profile) {
        port = new FakeUiPort(profile);
        document = new PlanDocument(plan, null, () -> TODAY);
        environment = AppEnvironment.from(LaunchOptions.parse(new String[]{"--home", home.toString(),
                "--today", TODAY.toString(), "--registry", "memory"}));
        context = (FlowContext) Proxy.newProxyInstance(FlowContext.class.getClassLoader(),
                new Class<?>[]{FlowContext.class}, (proxy, method, args) -> invoke(method.getName(), args));
        views = new ViewFlow(context);
        help = new HelpFlow(context);
        edits = new EditFlow(context);
    }

    /** Реализует только вызовы, разрешённые этой области; неизвестный вызов проваливает тест. */
    @SuppressWarnings("unchecked")
    private Object invoke(String method, Object[] args) {
        switch (method) {
            case "port": return port;
            case "environment": return environment;
            case "document": return document;
            case "state": return state();
            case "edits": return edits;
            case "views": return views;
            case "help": return help;
            case "recorder": return null;
            case "updateView":
                document.setViewState(((UnaryOperator<ru.cashprediction.core.document.ViewState>) args[0])
                        .apply(document.viewState()));
                settings = document.viewState().applyTo(settings);
                viewUpdates++;
                refreshes++;
                return null;
            case "setSelection": selection = (String) args[0]; return null;
            case "setPastExpanded": pastExpanded = (boolean) args[0]; return null;
            case "refresh": refreshes++; return null;
            case "showMain": shown = true; return null;
            case "status":
                statuses.add(List.of(args[0], args[1], List.of((Object[]) args[2])));
                return null;
            case "singleInstance":
                return WindowType.QUICK_EDIT_POPUP.name().equals(args[0]) ? Optional.ofNullable(quickEdit)
                        : Optional.empty();
            case "showAlert": return port.showAlert((AlertSpec) args[0], null, (Consumer<String>) args[1]);
            case "openForm":
                request = (FormRequest) args[0];
                onResult = (Consumer<Object>) args[2];
                form = session(request);
                form.attach(port.openForm(form, form.spec(), form.view(), (Placement) args[1]));
                return form;
            default: throw new AssertionError("Unexpected FlowContext call: " + method);
        }
    }

    /** Свежий снимок вычисляется из настоящего документа, включая грязность и историю отмены. */
    AppState state() {
        Forecast forecast = failedForecast ? null : overrideForecast != null ? overrideForecast : document.forecast();
        DocumentView view = new DocumentView(document.plan(), null, document.isDirty(), document.canUndo(),
                document.undoDescription().orElse(""), document.canRedo(), document.redoDescription().orElse(""),
                forecast, failedForecast ? "failure" : "", List.of());
        return new AppState(1, port.profile(), TODAY, environment.cashMemory(), null, view, document.viewState(),
                selection, pastExpanded, settings, null, List.of(), null, null, "");
    }

    /** Сеанс использует настоящий цикл формы; имитируются только операции контроллера вокруг окна. */
    FormSession session(FormRequest fresh) {
        FormSession.Host host = (FormSession.Host) Proxy.newProxyInstance(FormSession.Host.class.getClassLoader(),
                new Class<?>[]{FormSession.Host.class}, (proxy, method, args) -> {
                    if (method.getName().equals("closed")) {
                        formClosures++;
                        if (onResult != null) onResult.accept(args[1]);
                    }
                    return null;
                });
        return new FormSession(fresh.type(), fresh.modal(), fresh.logic(),
                new FormContext("w1", null, fresh.context(), state()), host);
    }

    /** Создаёт немодальную быструю правку, чтобы проверить закрытие через сеанс, а не ручку. */
    void installQuickEdit() {
        quickEdit = session(FormRequest.fresh(ru.cashprediction.core.ui.forms.simple.TextInputForms.customMonths(),
                WindowType.QUICK_EDIT_POPUP, false, Map.of()));
        quickEdit.attach(port.openForm(quickEdit, quickEdit.spec(), quickEdit.view(), null));
    }
}
