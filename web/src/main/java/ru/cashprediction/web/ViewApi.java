package ru.cashprediction.web;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import ru.cashprediction.core.diagnostics.PlanValidator;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.document.RecoveryStoreKind;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.export.CsvExporter;
import ru.cashprediction.core.export.CsvOptions;
import ru.cashprediction.core.forecast.ChartSeries;
import ru.cashprediction.core.forecast.DailyPoint;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.ForecastEngine;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.GoalCalculator;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.io.PlanRepository;
import ru.cashprediction.core.json.PlanJson;
import ru.cashprediction.core.markdown.MarkdownFormat;
import ru.cashprediction.core.model.Goal;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.util.DateFormats;
import ru.cashprediction.core.util.RuText;

/**
 * Маршруты чтения и вида: полное состояние, вид (режим, период, фильтры), настройки, диагностика, калькулятор цели,
 * экспорт CSV, справка по формату файла и «О программе».
 *
 * <p>Класс без изменяемого состояния; маршруты выполняются под монитором {@link ServerState}.</p>
 */
public final class ViewApi {

    /** Версия программы для «О программе». */
    public static final String VERSION = "1.0.0";

    /** Наибольшее число точек графика, которое можно запросить в {@code GET /api/forecast}. */
    public static final int MAX_CHART_POINTS = 20_000;

    private final ServerState state;

    /**
     * Создаёт маршруты.
     *
     * @param state серверное состояние
     */
    public ViewApi(ServerState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    /**
     * Регистрирует маршруты.
     *
     * @param router маршрутизатор
     */
    public void register(Router router) {
        router.add("GET", "/api/state", request -> ApiResponse.json(StateJson.build(state)));
        // Прогноз произвольного интервала: график «показать с даты», перерисовка при масштабировании.
        router.add("GET", "/api/forecast", request -> ApiResponse.json(forecastRange(request)));
        // JavaFX: RadioMenuItem «Таблица/График» → Swing: JRadioButtonMenuItem → Web: role="menuitemradio";
        // то же для периода, CheckMenuItem фильтров (role="menuitemcheckbox") и строки фильтра.
        // Тело — сам ViewState ({"mode": "CHART"}) или {"viewState": {...}}; неуказанные поля не меняются.
        router.add("PUT", "/api/view", request -> {
            applyView(state, request.objectOrSelf("viewState"));
            return ApiResponse.json(StateJson.build(state));
        });
        // CheckMenuItem «Автосохранение» (и хранилище восстановления — у web оно всегда «Сервер»).
        router.add("PUT", "/api/settings", request -> {
            Map<String, Object> json = request.json();
            if (json.containsKey("autosave")) {
                boolean autosave = request.bool("autosave", false);
                state.updateSettings(s -> s.withAutosave(autosave));
            }
            if (json.containsKey("recoveryStore")) {
                RecoveryStoreKind kind = RecoveryStoreKind.parse(request.string("recoveryStore", ""))
                        .orElseThrow(() -> new IllegalArgumentException("Неизвестное хранилище восстановления"));
                state.updateSettings(s -> s.withRecoveryStore(kind));
            }
            return ApiResponse.json(StateJson.build(state));
        });
        // Диалог 15 «Диагностика» (Alert WARNING с expandableContent).
        router.add("GET", "/api/diagnostics", request -> {
            PlanDocument document = state.document();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("validation", PlanJson.diagnostics(PlanValidator.validate(document.plan())));
            m.put("load", PlanJson.diagnostics(document.loadDiagnostics()));
            try {
                m.put("warnings", PlanJson.warnings(document.forecast().warnings()));
            } catch (RuntimeException e) {
                m.put("warnings", List.of());
                m.put("forecastError", e.getMessage());
            }
            return ApiResponse.json(m);
        });
        // Диалог 6 «Калькулятор цели» (немодальный).
        router.add("GET", "/api/goal", request -> ApiResponse.json(goal(request)));
        router.add("POST", "/api/goal/save", request -> {
            Money target = PlanForms.parseMoney(request.string("target", ""), "Цель");
            String dateText = request.string("byDate", "").strip();
            LocalDate byDate = dateText.isEmpty() ? null : PlanForms.parseDate(dateText, "К дате");
            Plan plan = state.document().plan();
            String title = plan.goal() == null || plan.goal().title().isBlank() ? request.string("title", "Цель") : plan.goal().title();
            Goal goal = new Goal(title, target, byDate);
            state.document().edit("Цель плана", p -> p.withGoal(goal));
            return ApiResponse.json(StateJson.build(state));
        });
        // Диалог 14 «Экспорт CSV»: в браузере «Сохранить» — это скачивание файла (аналог FileChooser.showSaveDialog).
        router.add("GET", "/api/export.csv", request -> {
            PlanDocument document = state.document();
            Forecast forecast = document.forecast();
            char separator = switch (request.query("separator", ";")) {
                case "," -> ',';
                case "TAB", "\t" -> '\t';
                default -> ';';
            };
            boolean bom = !"false".equalsIgnoreCase(request.query("bom", "true"));
            CsvOptions options = new CsvOptions(separator, bom, null, null);
            if ("PERIOD".equalsIgnoreCase(request.query("range", "PERIOD"))) {
                LocalDate end = document.viewState().periodEnd(document.plan(), forecast.anchor());
                options = options.withRange(document.plan().startDate(), end.isBefore(document.plan().startDate()) ? forecast.endDate() : end);
            }
            String fileName = PlanRepository.fileBaseName(document.plan().name()) + ".csv";
            return ApiResponse.download("text/csv; charset=utf-8", CsvExporter.toCsv(forecast, options), fileName);
        });
        // «Справка → Формат файла .md»: Alert с текстом FORMAT.md ядра в expandableContent.
        // MarkdownFormat.userGuide() читает ресурс через класс модуля ядра — работает и на module path, и на classpath.
        Router.Handler formatHelp = request -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("text", MarkdownFormat.userGuide());
            return ApiResponse.json(m);
        };
        router.add("GET", "/api/format-help", formatHelp);
        router.add("GET", "/api/help/format", formatHelp);
        // Диалог 19 «О программе».
        router.add("GET", "/api/about", request -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", VERSION);
            m.put("java", System.getProperty("java.version"));
            m.put("os", System.getProperty("os.name"));
            m.put("cashMemory", state.layout().dir().toString());
            m.put("sessionFile", state.store().sessionFile().toString());
            return ApiResponse.json(m);
        });
    }

    /**
     * Сливает частичное описание вида с текущим и применяет его к документу.
     *
     * @param state   серверное состояние (монитор взят)
     * @param partial присланные поля ({@code mode}, {@code period}, флаги, {@code filterText}, {@code whatIf})
     */
    static void applyView(ServerState state, Map<String, Object> partial) {
        PlanDocument document = state.document();
        Map<String, Object> merged = PlanJson.viewState(document.viewState());
        partial.forEach((key, value) -> {
            if (merged.containsKey(key)) {
                merged.put(key, value);
            }
        });
        ViewState next = PlanJson.viewStateFrom(merged);
        document.setViewState(next);
    }

    /**
     * Прогноз произвольного интервала ({@code GET /api/forecast?from&to&maxPoints}).
     *
     * <p>Строки — события интервала, отфильтрованные текущим видом ({@link ViewState#accepts}), как в таблице;
     * точки графика — {@link ChartSeries#sample} не больше {@code maxPoints} (по умолчанию
     * {@value ChartSeries#DEFAULT_MAX_POINTS}). Интервал по умолчанию — видимый период.</p>
     *
     * @param request запрос: {@code from}, {@code to} (ISO или ДД.ММ.ГГГГ), {@code maxPoints}
     * @return JSON прогноза ({@code PlanJson.forecast}) плюс {@code from}, {@code to}, {@code rowsTotal}, {@code rowsTruncated}
     */
    private Map<String, Object> forecastRange(ApiRequest request) {
        PlanDocument document = state.document();
        Plan plan = document.plan();
        Forecast forecast = document.forecast();
        LocalDate periodEnd = document.viewState().periodEnd(plan, forecast.anchor());
        if (periodEnd.isBefore(plan.startDate())) {
            // Якорь «сейчас» позже конца плана: периода нет, показываем весь горизонт.
            periodEnd = forecast.endDate();
        }
        String fromText = request.query("from", "").strip();
        String toText = request.query("to", "").strip();
        LocalDate from = fromText.isEmpty() ? plan.startDate() : PlanForms.parseDate(fromText, "С");
        LocalDate to = toText.isEmpty() ? periodEnd : PlanForms.parseDate(toText, "По");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("Дата «По» не может быть раньше даты «С»");
        }
        int maxPoints = ChartSeries.DEFAULT_MAX_POINTS;
        String maxText = request.query("maxPoints", "").strip();
        if (!maxText.isEmpty()) {
            try {
                maxPoints = Integer.parseInt(maxText);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Параметр maxPoints должен быть целым числом, получено «" + maxText + "»");
            }
        }
        // ChartSeries требует не меньше 2 точек; верхняя граница защищает браузер от гигантского ответа.
        maxPoints = Math.clamp(maxPoints, 2, MAX_CHART_POINTS);
        ViewState view = document.viewState();
        List<ForecastRow> rows = forecast.rowsBetween(from, to).stream().filter(view::accepts).toList();
        int total = rows.size();
        if (total > StateJson.MAX_ROWS) {
            rows = rows.subList(0, StateJson.MAX_ROWS);
        }
        List<DailyPoint> chart = ChartSeries.sample(forecast, from, to, maxPoints);
        Map<String, Object> m = PlanJson.forecast(forecast, rows, chart);
        m.put("from", DateFormats.iso(from));
        m.put("to", DateFormats.iso(to));
        m.put("maxPoints", (long) maxPoints);
        m.put("rowsTotal", (long) total);
        m.put("rowsTruncated", total > StateJson.MAX_ROWS);
        return m;
    }

    /**
     * Расчёт калькулятора цели.
     *
     * @param request запрос с параметрами {@code target, byDateEnabled, byDate, extraSaving}
     * @return результат или {@code {error}} для недописанной формы
     */
    private Map<String, Object> goal(ApiRequest request) {
        Map<String, Object> m = new LinkedHashMap<>();
        PlanDocument document = state.document();
        Plan plan = document.plan();
        try {
            String targetText = request.query("target", "").strip();
            if (targetText.isEmpty()) {
                throw new IllegalArgumentException("Введите целевую сумму");
            }
            Money target = PlanForms.parseMoney(targetText, "Цель");
            String extraText = request.query("extraSaving", "").strip();
            Money extra = extraText.isEmpty() ? Money.ZERO : PlanForms.parseMoney(extraText, "Откладывать доп.");
            if (extra.isNegative()) {
                throw new IllegalArgumentException("Поле «Откладывать доп.»: сумма не может быть отрицательной");
            }
            ViewState view = document.viewState();
            WhatIf whatIf = view.whatIf().withExtraMonthlySaving(view.whatIf().extraMonthlySaving().plus(extra));
            Forecast forecast = ForecastEngine.forecast(plan, whatIf, state.today(), view.showSkipped());
            String currency = plan.currency();
            m.put("error", null);
            m.put("target", target.formatPlain());
            m.put("targetText", target.format(currency));
            m.put("endBalanceText", forecast.endBalance().format(currency));
            m.put("endDateText", DateFormats.ru(forecast.endDate()));
            Optional<LocalDate> reach = GoalCalculator.reachDate(forecast, target);
            m.put("reachDate", reach.map(DateFormats::iso).orElse(null));
            m.put("reachDateText", reach.map(DateFormats::ru).orElse(null));
            m.put("reachInText", reach.map(d -> {
                long months = java.time.temporal.ChronoUnit.MONTHS.between(forecast.anchor(), d);
                return months <= 0 ? "уже в этом месяце" : "через " + RuText.count(months, "месяц", "месяца", "месяцев");
            }).orElse(null));
            // Без флажка byDateEnabled дата учитывается, если передана (GET /api/goal?target=…&byDate=…);
            // с флажком — как в форме калькулятора: снятый флажок отключает дату, даже если поле заполнено.
            boolean byDateEnabled = !request.hasQuery("byDateEnabled")
                    || "true".equalsIgnoreCase(request.query("byDateEnabled", "false"));
            String byDateText = request.query("byDate", "").strip();
            if (byDateEnabled && !byDateText.isEmpty()) {
                LocalDate byDate = PlanForms.parseDate(byDateText, "К дате");
                Money at = GoalCalculator.balanceAt(forecast, byDate);
                m.put("byDateText", DateFormats.ru(byDate));
                m.put("balanceAtByDateText", at.format(currency));
                Optional<Money> required = GoalCalculator.requiredExtraMonthly(forecast, target, byDate);
                m.put("required", required.map(Money::formatPlain).orElse(null));
                m.put("requiredText", required.map(r -> r.format(currency)).orElse(null));
                m.put("requiredProblem", required.isPresent() ? null
                        : "К этой дате цель недостижима дополнительной ежемесячной экономией (дата вне горизонта или раньше «сейчас»)");
            }
        } catch (RuntimeException e) {
            m.clear();
            m.put("error", e.getMessage());
        }
        return m;
    }
}
