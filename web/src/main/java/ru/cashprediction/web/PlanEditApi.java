package ru.cashprediction.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.forecast.ForecastEngine;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.json.PlanJson;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.OneTimeTransaction;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.TxId;
import ru.cashprediction.core.recurrence.Occurrence;
import ru.cashprediction.core.recurrence.OccurrenceGenerator;
import ru.cashprediction.core.util.DateFormats;
import ru.cashprediction.core.util.RuText;

/**
 * Маршруты правки плана: параметры, регулярные и разовые операции, корректировки событий, отмена/повтор,
 * актуализация, сверка баланса и «что-если».
 *
 * <p>Все изменения идут только через {@link PlanDocument#edit} и специальные методы документа — так в web-клиенте
 * работают та же история отмены, тот же флаг «изменён» и то же автосохранение, что и в desktop-клиентах.
 * Поля форм приходят в канонических строках ({@link PlanForms}); ответ — полное состояние ({@link StateJson}).</p>
 *
 * <p>Класс без изменяемого состояния; маршруты выполняются под монитором {@link ServerState}.</p>
 */
public final class PlanEditApi {

    /** Сколько ближайших дат показывает предпросмотр в редакторе правила. */
    public static final int PREVIEW_COUNT = 6;

    private final ServerState state;
    private final PlanFileCommands files;

    /**
     * Создаёт маршруты.
     *
     * @param state серверное состояние
     * @param files команды файлов (переименование из диалога «Параметры плана»)
     */
    public PlanEditApi(ServerState state, PlanFileCommands files) {
        this.state = Objects.requireNonNull(state, "state");
        this.files = Objects.requireNonNull(files, "files");
    }

    /**
     * Регистрирует маршруты.
     *
     * @param router маршрутизатор
     */
    public void register(Router router) {
        // Диалог 2 «Параметры плана», слайдер горизонта (CustomMenuItem) и диалог 8 «Валюта» (ChoiceDialog).
        router.add("PUT", "/api/plan/settings", request -> {
            Map<String, String> fields = ApiRequest.stringMap(request.objectOrSelf("fields"));
            Plan current = document().plan();
            // Сначала проверка: неверное поле не должно успеть переименовать файл.
            PlanForms.applySettings(current, fields);
            String name = fields.getOrDefault("name", "").strip();
            if (!name.isEmpty() && !name.equals(current.name())) {
                files.rename(name);
            }
            Plan base = document().plan();
            Plan next = PlanForms.applySettings(base, fields);
            document().edit(request.string("description", "Параметры плана"), p -> next);
            return done(request);
        });

        // Диалог 3 «Регулярная операция»: живой предпросмотр «Ближайшие даты» (OccurrenceGenerator.upcoming).
        // GET — поля формы в строке запроса (или JSON правила в параметре rule), POST — поля в теле.
        router.add("GET", "/api/preview-dates", request -> ApiResponse.json(preview(request)));
        router.add("POST", "/api/preview-dates", request -> ApiResponse.json(preview(request)));
        router.add("POST", "/api/rules/preview", request -> ApiResponse.json(preview(request)));
        router.add("POST", "/api/rules", request -> {
            Plan plan = document().plan();
            RecurringRule rule = PlanForms.ruleFromFields(ApiRequest.stringMap(request.objectOrSelf("fields")), plan.nextRuleId());
            document().edit("Добавление «" + rule.title() + "»", p -> p.withRuleAdded(rule));
            Map<String, Object> m = stateWithWindowClosed(request);
            m.put("createdId", rule.id().value());
            return ApiResponse.json(m);
        });
        router.add("PUT", "/api/rules/{id}", request -> {
            RecurringRule old = requireRule(request.pathParam("id"));
            RecurringRule rule = PlanForms.ruleFromFields(ApiRequest.stringMap(request.objectOrSelf("fields")), old.id());
            document().edit("Изменение «" + rule.title() + "»", p -> p.withRuleReplaced(rule));
            return done(request);
        });
        // Контекстное меню строки: «Отключить правило» / «Включить правило».
        router.add("POST", "/api/rules/{id}/enabled", request -> {
            RecurringRule old = requireRule(request.pathParam("id"));
            boolean enabled = request.bool("enabled", !old.enabled());
            document().edit((enabled ? "Включение «" : "Отключение «") + old.title() + "»",
                    p -> p.withRuleReplaced(old.withEnabled(enabled)));
            return done(request);
        });
        // Диалог 11 «Удаление»: Alert(CONFIRMATION) подтверждается в браузере, здесь только удаление.
        router.add("DELETE", "/api/rules/{id}", request -> {
            RecurringRule old = requireRule(request.pathParam("id"));
            int adjustments = document().plan().adjustmentsOf(old.id()).size();
            document().edit("Удаление «" + old.title() + "»", p -> p.withRuleRemoved(old.id()));
            Map<String, Object> m = stateWithWindowClosed(request);
            m.put("removedAdjustments", (long) adjustments);
            return ApiResponse.json(m);
        });

        // Диалог 4 «Разовая операция».
        router.add("POST", "/api/one-time", request -> {
            Plan plan = document().plan();
            OneTimeTransaction tx = PlanForms.oneTimeFromFields(ApiRequest.stringMap(request.objectOrSelf("fields")), plan.nextTxId());
            document().edit("Добавление «" + tx.title() + "»", p -> p.withOneTimeAdded(tx));
            Map<String, Object> m = stateWithWindowClosed(request);
            m.put("createdId", tx.id().value());
            return ApiResponse.json(m);
        });
        router.add("PUT", "/api/one-time/{id}", request -> {
            OneTimeTransaction old = requireOneTime(request.pathParam("id"));
            OneTimeTransaction tx = PlanForms.oneTimeFromFields(ApiRequest.stringMap(request.objectOrSelf("fields")), old.id());
            document().edit("Изменение «" + tx.title() + "»", p -> p.withOneTimeReplaced(tx));
            return done(request);
        });
        router.add("DELETE", "/api/one-time/{id}", request -> {
            OneTimeTransaction old = requireOneTime(request.pathParam("id"));
            document().edit("Удаление «" + old.title() + "»", p -> p.withOneTimeRemoved(old.id()));
            return done(request);
        });

        // Диалог 5 «Корректировка события» и Popup быстрой правки суммы (QUICK_EDIT_POPUP).
        router.add("PUT", "/api/adjustments", request -> {
            Map<String, String> fields = ApiRequest.stringMap(request.objectOrSelf("fields"));
            Adjustment adjustment = PlanForms.adjustmentFromFields(fields);
            requireRule(adjustment.key().ruleId().value());
            document().edit("Корректировка " + DateFormats.ru(adjustment.key().originalDate()) + ": "
                    + adjustment.action().label(), p -> p.withAdjustmentPut(adjustment));
            return done(request);
        });
        // «Сбросить» в диалоге корректировки и «Вернуть как по правилу» в меню.
        router.add("DELETE", "/api/adjustments", request -> {
            String ruleId = request.query("ruleId", "");
            LocalDate original = PlanForms.parseDate(request.query("originalDate", ""), "Исходная дата");
            OccurrenceKey key = new OccurrenceKey(new RuleId(ruleId), original);
            if (document().plan().findAdjustment(key).isEmpty()) {
                throw new NoSuchElementException("У события " + DateFormats.ru(original) + " нет корректировки");
            }
            document().edit("Возврат события " + DateFormats.ru(original) + " к правилу", p -> p.withAdjustmentRemoved(key));
            return done(request);
        });
        // «Инструменты → Очистить неиспользуемые корректировки» (PlanDocument.removeOrphanAdjustments).
        Router.Handler cleanupOrphans = request -> {
            int removed = document().removeOrphanAdjustments();
            Map<String, Object> m = StateJson.build(state);
            m.put("removed", (long) removed);
            m.put("message", removed == 0 ? "Неиспользуемых корректировок нет"
                    : "Удалено " + RuText.count(removed, "корректировка", "корректировки", "корректировок"));
            return ApiResponse.json(m);
        };
        router.add("POST", "/api/cleanup-orphans", cleanupOrphans);
        router.add("POST", "/api/adjustments/remove-orphans", cleanupOrphans);

        router.add("POST", "/api/undo", request -> {
            document().undo();
            return done(request);
        });
        router.add("POST", "/api/redo", request -> {
            document().redo();
            return done(request);
        });

        // «Актуализировать на сегодня…»: предпросмотр для Alert и само действие.
        router.add("GET", "/api/actualize/preview", request -> ApiResponse.json(actualizePreview()));
        router.add("POST", "/api/actualize", request -> {
            String balance = request.string("balance", "").strip();
            document().actualize(state.today(), balance.isEmpty() ? null : PlanForms.parseMoney(balance, "Баланс"));
            return done(request);
        });
        // Диалог 7 «Сверить баланс» (TextInputDialog).
        router.add("POST", "/api/reconcile", request -> {
            // {"balance": "…"}; поле value — имя поля TextInputDialog в снимке (WindowType.TEXT_INPUT), тоже принимается.
            String text = request.string("balance", request.string("value", "")).strip();
            if (text.isEmpty()) {
                throw new IllegalArgumentException("Поле «Фактический баланс»: укажите сумму");
            }
            Money actual = PlanForms.parseMoney(text, "Фактический баланс");
            document().reconcile(state.today(), actual);
            return done(request);
        });

        // Меню «Что-если»: CheckMenuItem ±10 % и CustomMenuItem «Откладывать доп.».
        router.add("PUT", "/api/what-if", request -> {
            WhatIf current = document().viewState().whatIf();
            Map<String, Object> json = request.json();
            WhatIf next = current;
            if (json.containsKey("incomeFactor")) {
                next = next.withIncomeFactor(factor(request.string("incomeFactor", "1")));
            }
            if (json.containsKey("expenseFactor")) {
                next = next.withExpenseFactor(factor(request.string("expenseFactor", "1")));
            }
            if (json.containsKey("extraMonthlySaving")) {
                String text = request.string("extraMonthlySaving", "").strip();
                Money saving = text.isEmpty() ? Money.ZERO : PlanForms.parseMoney(text, "Откладывать доп.");
                if (saving.isNegative()) {
                    throw new IllegalArgumentException("Поле «Откладывать доп.»: сумма не может быть отрицательной");
                }
                next = next.withExtraMonthlySaving(saving);
            }
            document().setViewState(document().viewState().withWhatIf(next));
            return done(request);
        });
        router.add("POST", "/api/what-if/reset", request -> {
            document().setViewState(document().viewState().withWhatIf(WhatIf.NONE));
            return done(request);
        });
        // «Применить к плану…»: документ сам сбрасывает «что-если» после применения.
        router.add("POST", "/api/what-if/apply", request -> {
            document().applyWhatIfToPlan();
            return done(request);
        });
    }

    /** @return документ плана (монитор уже взят) */
    private PlanDocument document() {
        return state.document();
    }

    /**
     * Ответ правки: полное состояние; если в теле указан {@code windowId}, это окно закрывается на сервере.
     *
     * @param request запрос
     * @return ответ
     */
    private ApiResponse done(ApiRequest request) {
        return ApiResponse.json(stateWithWindowClosed(request));
    }

    /**
     * Закрывает окно-источник команды (если указано) и собирает состояние.
     *
     * @param request запрос
     * @return изменяемый объект состояния
     */
    private Map<String, Object> stateWithWindowClosed(ApiRequest request) {
        String windowId = request.body().isBlank() ? "" : request.string("windowId", "");
        if (!windowId.isBlank() && state.windows().stream().anyMatch(w -> w.windowId().equals(windowId))) {
            state.closeWindow(windowId);
        }
        return StateJson.build(state);
    }

    /**
     * Предпросмотр «Ближайшие даты» редактора правила.
     *
     * @param request запрос с полями редактора
     * @return {@code {text, dates:[{date, dateText, weekday, nominal, shifted}], error}}
     */
    private Map<String, Object> preview(ApiRequest request) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> dates = new ArrayList<>();
        m.put("dates", dates);
        try {
            Plan plan = document().plan();
            RecurringRule rule = PlanForms.ruleFromFields(previewFields(request), new RuleId("preview"));
            LocalDate from = state.today().isAfter(plan.startDate()) ? state.today() : plan.startDate();
            for (Occurrence occurrence : OccurrenceGenerator.upcoming(rule, plan.startDate(), from, PREVIEW_COUNT)) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("date", DateFormats.iso(occurrence.actual()));
                d.put("dateText", DateFormats.ru(occurrence.actual()));
                d.put("weekday", RuText.weekdayShort(occurrence.actual().getDayOfWeek()));
                d.put("nominal", DateFormats.iso(occurrence.nominal()));
                d.put("nominalText", DateFormats.ru(occurrence.nominal()));
                d.put("shifted", occurrence.shifted());
                dates.add(d);
            }
            m.put("text", rule.recurrence().toRussian());
            m.put("error", null);
        } catch (RuntimeException e) {
            // Недописанная форма — обычное дело, это не ошибка запроса: браузер покажет причину под предпросмотром.
            m.put("text", null);
            m.put("error", e.getMessage());
        }
        return m;
    }

    /**
     * Поля редактора правила для предпросмотра из запроса любого вида.
     *
     * <p>POST: тело {@code {"fields": {...}}} или сами поля. GET: параметр {@code rule} с JSON-объектом полей либо
     * сами поля в строке запроса ({@code ?kind=INCOME&recurrenceKind=MONTHLY&dayOfMonth=5}); параметр токена
     * {@code t} в поля не попадает.</p>
     *
     * @param request запрос
     * @return поля в канонической форме {@code WindowType.RULE_EDITOR}
     */
    private static Map<String, String> previewFields(ApiRequest request) {
        if (!"GET".equals(request.method()) && !"HEAD".equals(request.method())) {
            return ApiRequest.stringMap(request.objectOrSelf("fields"));
        }
        if (request.hasQuery("rule")) {
            Map<String, Object> rule = Json.asObject(ru.cashprediction.core.json.JsonParser.parse(request.query("rule", "{}")),
                    "rule");
            return ApiRequest.stringMap(Json.optionalObject(rule, "fields").orElse(rule));
        }
        Map<String, String> fields = new LinkedHashMap<>(request.queryMap());
        fields.remove(ApiHandler.TOKEN_PARAM);
        return fields;
    }

    /**
     * Что сделает «Актуализировать на сегодня»: новая дата начала и баланс по прогнозу без «что-если».
     *
     * @return {@code {today, todayText, startDate, balance, balanceText, needed}}
     */
    private Map<String, Object> actualizePreview() {
        Plan plan = document().plan();
        LocalDate today = state.today();
        Money balance;
        if (!today.isAfter(plan.startDate())) {
            balance = plan.startBalance();
        } else {
            // Баланс на начало сегодняшнего дня = конец вчерашнего (так же считает PlanDocument.actualize).
            balance = ForecastEngine.forecast(plan, WhatIf.NONE, today, false).balanceAt(today.minusDays(1));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("today", DateFormats.iso(today));
        m.put("todayText", DateFormats.ru(today));
        m.put("startDate", DateFormats.iso(plan.startDate()));
        m.put("startDateText", DateFormats.ru(plan.startDate()));
        m.put("balance", balance.formatPlain());
        m.put("balanceText", balance.format(plan.currency()));
        m.put("needed", !today.equals(plan.startDate()));
        m.put("plan", PlanJson.plan(plan).get("name"));
        return m;
    }

    /**
     * Коэффициент «что-если» из текста.
     *
     * @param text «1.10», «0,9»
     * @return коэффициент
     */
    private static BigDecimal factor(String text) {
        try {
            BigDecimal value = new BigDecimal(text.strip().replace(',', '.'));
            if (value.signum() < 0) {
                throw new IllegalArgumentException("Что-если: коэффициент не может быть отрицательным");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Что-если: некорректный коэффициент «" + text + "»");
        }
    }

    private RecurringRule requireRule(String id) {
        return document().plan().findRule(new RuleId(id))
                .orElseThrow(() -> new NoSuchElementException("Регулярная операция «" + id + "» не найдена"));
    }

    private OneTimeTransaction requireOneTime(String id) {
        return document().plan().findOneTime(new TxId(id))
                .orElseThrow(() -> new NoSuchElementException("Разовая операция «" + id + "» не найдена"));
    }

    /**
     * Вложенный объект тела запроса (для совместимости с телами {@code {"fields": {...}}}).
     *
     * @param request запрос
     * @param key     ключ
     * @return объект или пустой объект
     */
    static Map<String, Object> object(ApiRequest request, String key) {
        return Json.object(request.json(), key);
    }
}
