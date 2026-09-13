package ru.cashprediction.swing.selftest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.json.JsonWriter;
import ru.cashprediction.core.json.PlanJson;
import ru.cashprediction.core.session.MainWindowState;
import ru.cashprediction.core.session.SessionRecorder;
import ru.cashprediction.core.session.StatefulWindow;
import ru.cashprediction.core.session.StoreStatus;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.swing.MainFrame;

/**
 * Команда самотеста {@code dump}: состояние приложения в JSON (через {@code JsonWriter} ядра).
 *
 * <p>Формат одинаков в JavaFX- и Swing-клиентах: {@code title} — заголовок окна; {@code main} — то, что
 * главное окно отдаёт в снимок сессии; {@code plan} — имя, признак несохранённых изменений, правила, разовые
 * операции и корректировки в виде {@code PlanJson}; {@code windows} — состояние каждого зарегистрированного окна
 * ({@code id, type, modal, ownerId, context, fields}); {@code recorderStarted}; {@code restoreWarnings};
 * {@code storeStatuses}.</p>
 *
 * <p>Вызывается в потоке EDT.</p>
 */
public final class SelfTestDump {

    private SelfTestDump() {
    }

    /**
     * Собирает JSON-текст состояния.
     *
     * @param app главное окно
     * @return JSON с отступами
     */
    public static String json(MainFrame app) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("title", app.title());
        root.put("main", main(app.captureMain()));
        PlanDocument document = app.document();
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("name", document.plan().name());
        plan.put("dirty", document.isDirty());
        plan.put("rules", document.plan().rules().stream().map(PlanJson::rule).toList());
        plan.put("oneTimes", document.plan().oneTimes().stream().map(PlanJson::oneTime).toList());
        plan.put("adjustments", document.plan().adjustments().stream().map(PlanJson::adjustment).toList());
        root.put("plan", plan);
        SessionRecorder recorder = app.recorder();
        List<Object> windows = new ArrayList<>();
        if (recorder != null) {
            for (StatefulWindow window : recorder.registeredWindows()) {
                WindowState state = window.captureState();
                if (state != null) {
                    windows.add(window(state));
                }
            }
        }
        root.put("windows", windows);
        root.put("recorderStarted", recorder != null && recorder.isStarted());
        root.put("restoreWarnings", app.restoreWarnings());
        List<Object> statuses = new ArrayList<>();
        for (StoreStatus status : app.statusBar().storeStatuses()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("storeId", status.storeId());
            s.put("ok", status.ok());
            s.put("savedAt", status.savedAt() == null ? null : status.savedAt().toString());
            s.put("message", status.message());
            statuses.add(s);
        }
        root.put("storeStatuses", statuses);
        return JsonWriter.writePretty(root);
    }

    private static Map<String, Object> main(MainWindowState main) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (main.bounds() == null) {
            m.put("bounds", null);
        } else {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("x", main.bounds().x());
            b.put("y", main.bounds().y());
            b.put("width", main.bounds().width());
            b.put("height", main.bounds().height());
            m.put("bounds", b);
        }
        m.put("maximized", main.maximized());
        m.put("view", main.view());
        m.put("planPath", main.planPath());
        m.put("period", main.period());
        m.put("filters", new LinkedHashMap<>(main.filters()));
        m.put("filterText", main.filterText());
        m.put("selectedRowId", main.selectedRowId());
        return m;
    }

    private static Map<String, Object> window(WindowState state) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("id", state.id());
        w.put("type", state.type() == null ? null : state.type().name());
        w.put("modal", state.modal());
        w.put("ownerId", state.ownerId());
        w.put("context", new LinkedHashMap<>(state.context()));
        w.put("fields", new LinkedHashMap<>(state.fields()));
        return w;
    }
}
