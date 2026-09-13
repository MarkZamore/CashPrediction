package ru.cashprediction.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.forecast.ChartSeries;
import ru.cashprediction.core.forecast.DailyPoint;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.json.PlanJson;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.session.MainWindowState;
import ru.cashprediction.core.session.RestoreCoordinator;
import ru.cashprediction.core.session.RestoreReport;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.session.StoreStatus;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.util.DateFormats;

/**
 * Полное состояние для браузера ({@code GET /api/state} и ответ каждой правки): план, прогноз видимого периода,
 * точки графика, вид, отмена/повтор, настройки, открытые окна и сведения о сессии.
 *
 * <p>Тонкий клиент ничего не считает сам: после любой правки он просто перерисовывает страницу по этому объекту.
 * Поэтому все три клиента показывают одинаковые числа — их считает одно ядро.</p>
 *
 * <p>Методы вызываются только под монитором {@link ServerState#lock}. Класс без состояния.</p>
 */
public final class StateJson {

    /** Наибольшее число строк таблицы в ответе; больше браузеру не нужно (показывается предупреждение). */
    public static final int MAX_ROWS = 10_000;

    /** Формат времени снимка в строке состояния. */
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Формат даты и времени снимка в баннере восстановления. */
    static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    private StateJson() {
    }

    /**
     * Собирает состояние.
     *
     * @param state серверное состояние (монитор уже взят)
     * @return JSON-объект
     */
    public static Map<String, Object> build(ServerState state) {
        PlanDocument document = state.document();
        Plan plan = document.plan();
        ViewState view = document.viewState();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("today", DateFormats.iso(state.today()));
        m.put("plan", PlanJson.plan(plan));
        m.put("view", PlanJson.viewState(view));
        try {
            Forecast forecast = document.forecast();
            LocalDate periodEnd = view.periodEnd(plan, forecast.anchor());
            if (periodEnd.isBefore(plan.startDate())) {
                periodEnd = forecast.endDate();
            }
            List<ForecastRow> rows = document.visibleRows();
            int total = rows.size();
            if (total > MAX_ROWS) {
                rows = rows.subList(0, MAX_ROWS);
            }
            List<DailyPoint> chart = ChartSeries.sample(forecast, plan.startDate(), periodEnd, ChartSeries.DEFAULT_MAX_POINTS);
            m.put("forecast", PlanJson.forecast(forecast, rows, chart));
            m.put("forecastError", null);
            m.put("periodEnd", DateFormats.iso(periodEnd));
            m.put("periodEndText", DateFormats.ru(periodEnd));
            Money now = forecast.balanceAt(forecast.anchor());
            m.put("nowBalance", now.formatPlain());
            m.put("nowBalanceText", now.format());
            m.put("rowsTotal", (long) total);
            m.put("rowsTruncated", total > MAX_ROWS);
        } catch (RuntimeException e) {
            // Например, горизонт длиннее 200 000 дней: план показывается, прогноз — нет, с понятной причиной.
            m.put("forecast", null);
            m.put("forecastError", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            m.put("rowsTotal", 0L);
            m.put("rowsTruncated", false);
        }
        Map<String, Object> documentJson = documentJson(state);
        m.put("document", documentJson);
        // Плоские копии самых нужных полей: браузеру не приходится лезть во вложенные объекты ради заголовка и кнопок.
        m.put("viewState", m.get("view"));
        m.put("dirty", documentJson.get("dirty"));
        m.put("file", documentJson.get("file"));
        m.put("title", documentJson.get("title"));
        m.put("canUndo", documentJson.get("canUndo"));
        m.put("canRedo", documentJson.get("canRedo"));
        m.put("pendingRestore", state.isRestorePending());
        m.put("settings", PlanJson.settings(state.settings()));
        m.put("windows", windowsJson(state));
        m.put("session", sessionJson(state));
        m.put("notices", new ArrayList<Object>(state.notices()));
        m.put("openWizard", state.openWizard());
        m.put("autosaveProblem", state.autosaveProblem());
        m.put("cashMemory", state.layout().dir().toString());
        m.put("extraFolder", state.extraFolder() == null ? null : state.extraFolder().toString());
        return m;
    }

    /**
     * Сведения о документе: файл, «грязность», отмена/повтор, заголовок окна.
     *
     * @param state состояние
     * @return объект
     */
    static Map<String, Object> documentJson(ServerState state) {
        PlanDocument document = state.document();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dirty", document.isDirty());
        m.put("file", document.file().map(p -> p.toAbsolutePath().toString()).orElse(null));
        m.put("fileName", document.file().map(p -> p.getFileName().toString()).orElse(null));
        m.put("settingsName", document.file().map(state::settingsName).orElse(null));
        m.put("title", "CashPrediction — " + document.plan().name() + (document.isDirty() ? " *" : ""));
        m.put("canUndo", document.canUndo());
        m.put("canRedo", document.canRedo());
        m.put("undoText", document.undoDescription().orElse(null));
        m.put("redoText", document.redoDescription().orElse(null));
        m.put("loadDiagnostics", PlanJson.diagnostics(document.loadDiagnostics()));
        return m;
    }

    /**
     * Окна, открытые в браузере (зарегистрированные на сервере).
     *
     * @param state состояние
     * @return массив окон в порядке открытия
     */
    static List<Object> windowsJson(ServerState state) {
        List<Object> list = new ArrayList<>();
        state.windows().forEach(w -> list.add(w.toJson()));
        return list;
    }

    /**
     * Сведения о записи сессии и восстановлении после сбоя.
     *
     * @param state состояние
     * @return объект {@code {store, storeFile, recording, enabled, alreadyRunning, pendingRestore, pending..., statuses,
     *         statusText, lastRestore, unrestoredPlan}}
     */
    static Map<String, Object> sessionJson(ServerState state) {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean recording = state.recorder().isStarted() && state.recorder().isEnabled() && !state.recorder().isClosed();
        // Сводное состояние сеанса (раздел 5.5 плана): crashed — ждёт решения «Восстановить / Начать заново»,
        // running — идёт запись снимков, disabled — второй сервер над той же папкой, new — запись ещё не начата
        // (например, восстановление не открыло несохранённый план), closed — сервер остановлен.
        String summary;
        if (state.isRestorePending()) {
            summary = "crashed";
        } else if (state.alreadyRunning()) {
            summary = "disabled";
        } else if (state.recorder().isClosed()) {
            summary = "closed";
        } else if (recording) {
            summary = "running";
        } else {
            summary = "new";
        }
        m.put("state", summary);
        m.put("savedAt", state.recorder().lastSavedAt(state.store().id()).map(Instant::toString).orElse(null));
        m.put("store", state.store().title());
        m.put("storeFile", state.store().sessionFile().toString());
        m.put("recording", recording);
        m.put("enabled", state.recorder().isEnabled());
        m.put("alreadyRunning", state.alreadyRunning());
        m.put("pendingRestore", state.isRestorePending());
        m.put("pendingProblem", state.pendingProblem());
        Optional<SessionSnapshot> pending = state.pendingRestore();
        m.put("pendingSavedAt", pending.map(s -> s.savedAt().toString()).orElse(null));
        m.put("pendingSavedAtText", pending.map(s -> format(s.savedAt(), DATE_TIME)).orElse(null));
        m.put("pendingStartedAtText", state.detection() == null ? null
                : state.detection().findMarker().map(mk -> format(mk.startedAt(), DATE_TIME)).orElse(null));
        m.put("pendingPlan", pending.map(s -> s.main().planPath()).orElse(null));
        m.put("pendingDirty", pending.map(s -> s.plan().dirty()).orElse(false));
        List<Object> titles = new ArrayList<>();
        pending.ifPresent(s -> s.windows().forEach(w -> titles.add(windowTitle(w))));
        m.put("pendingWindows", titles);
        m.put("pendingSnapshot", pending.map(StateJson::snapshotJson).orElse(null));
        List<Object> statuses = new ArrayList<>();
        StringBuilder statusText = new StringBuilder();
        for (StoreStatus status : state.storeStatuses()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("storeId", status.storeId());
            s.put("ok", status.ok());
            s.put("savedAt", status.savedAt() == null ? null : status.savedAt().toString());
            s.put("savedAtText", status.savedAt() == null ? null : format(status.savedAt(), TIME));
            s.put("message", status.message());
            statuses.add(s);
            if (!statusText.isEmpty()) {
                statusText.append(" | ");
            }
            statusText.append(state.store().title()).append(status.ok() ? " ✓ " : " ✗ ")
                    .append(status.savedAt() == null ? "" : format(status.savedAt(), TIME));
        }
        m.put("statuses", statuses);
        m.put("statusText", statusText.toString().strip());
        RestoreReport report = state.lastRestoreReport();
        if (report == null) {
            m.put("lastRestore", null);
        } else {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("windowsRestored", (long) report.windowsRestored());
            r.put("warnings", new ArrayList<Object>(report.warnings()));
            r.put("recorderNotStarted", report.warnings().stream().anyMatch(w -> w.startsWith(
                    RestoreCoordinator.RECORDER_NOT_STARTED.substring(0, 30))));
            m.put("lastRestore", r);
        }
        m.put("unrestoredPlan", state.unrestoredPlanMarkdown() != null);
        return m;
    }

    /**
     * Снимок, ожидающий восстановления, для баннера: что именно вернётся (план, вид, окна с введёнными значениями).
     *
     * @param snapshot снимок аварийно завершённого сеанса
     * @return {@code {savedAt, savedAtText, client, main:{view, planPath, period, filters, filterText, selectedRowId},
     *         planDirty, windows:[{id, type, title, modal, ownerId, bounds, context, fields}]}}
     */
    static Map<String, Object> snapshotJson(SessionSnapshot snapshot) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("savedAt", snapshot.savedAt().toString());
        m.put("savedAtText", format(snapshot.savedAt(), DATE_TIME));
        m.put("client", snapshot.client());
        MainWindowState main = snapshot.main();
        Map<String, Object> mainJson = new LinkedHashMap<>();
        mainJson.put("view", main.view());
        mainJson.put("planPath", main.planPath());
        mainJson.put("period", main.period());
        mainJson.put("filters", new LinkedHashMap<String, Object>(main.filters()));
        mainJson.put("filterText", main.filterText());
        mainJson.put("selectedRowId", main.selectedRowId());
        m.put("main", mainJson);
        m.put("planDirty", snapshot.plan().dirty());
        List<Object> windows = new ArrayList<>();
        snapshot.windows().forEach(w -> windows.add(WebWindow.toJson(w)));
        m.put("windows", windows);
        return m;
    }

    /**
     * Название окна из снимка для списка в баннере восстановления.
     *
     * @param w состояние окна
     * @return «Регулярная операция» или «Неизвестное окно»
     */
    private static String windowTitle(WindowState w) {
        return w.type() == null ? "Неизвестное окно" : w.type().title();
    }

    /**
     * Форматирует момент времени по часовому поясу компьютера.
     *
     * @param instant   момент
     * @param formatter формат
     * @return текст
     */
    static String format(Instant instant, DateTimeFormatter formatter) {
        return formatter.format(instant.atZone(ZoneId.systemDefault()));
    }
}
