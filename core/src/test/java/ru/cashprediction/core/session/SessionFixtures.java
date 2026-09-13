package ru.cashprediction.core.session;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Готовые снимки для тестов кодеков и хранилищ.
 *
 * <p>{@link #tricky(String)} собирает все неудобные для сериализации случаи: кириллицу, кавычки,
 * {@code < > &}, вертикальную черту, переводы строк (LF и CRLF), табуляцию, краевые пробелы,
 * пустые карты, отсутствующие границы, окно неизвестного типа и цепочку владельцев.</p>
 */
public final class SessionFixtures {

    /** Момент снимка. */
    public static final Instant SAVED = Instant.parse("2026-09-13T10:15:30.123Z");

    /** Момент начала сеанса. */
    public static final Instant STARTED = Instant.parse("2026-09-13T10:00:00Z");

    /** Текст несохранённого плана со «взрывоопасными» для XML и Markdown фрагментами (без CR). */
    public static final String TRICKY_PLAN = """
            # План: Семейный бюджет 2026

            | Дата | Сумма | Заметка |
            |------|-------|---------|
            | 05.10.2026 | 80 000,00 | зарплата \\| аванс |

            <tag attr="1"> & ]]> конец CDATA ```code``` и ```` четыре
            ## Не раздел сессии
            - Состояние: ловушка
            """;

    private SessionFixtures() {
    }

    /**
     * Простой снимок, похожий на образцы формата из плана.
     *
     * @param client клиент
     * @return снимок с одним редактором правила
     */
    public static SessionSnapshot simple(String client) {
        Map<String, Boolean> filters = new LinkedHashMap<>();
        filters.put("showIncome", true);
        filters.put("showExpense", true);
        MainWindowState main = new MainWindowState(new WindowBounds(100, 80, 1200, 800), false, "TABLE",
                "Семейный бюджет 2026.md", "12m", filters, "", "r2@2026-10-01");
        Map<String, String> context = new LinkedHashMap<>();
        context.put("mode", "edit");
        context.put("ruleId", "r3");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", "Аренда");
        fields.put("kind", "EXPENSE");
        fields.put("amount", "45000,00");
        WindowState rule = new WindowState("w1", WindowType.RULE_EDITOR, true, "main",
                new WindowBounds(400, 300, 520, 480), context, fields);
        return SessionSnapshot.of(SAVED, client, main, PlanState.dirty("# План: Семейный бюджет 2026\n"), List.of(rule));
    }

    /**
     * Снимок со всеми трудными случаями.
     *
     * @param client клиент
     * @return снимок из пяти окон
     */
    public static SessionSnapshot tricky(String client) {
        Map<String, Boolean> filters = new LinkedHashMap<>();
        filters.put("showIncome", true);
        filters.put("showExpense", false);
        filters.put("фильтр;с=символами", true);
        MainWindowState main = new MainWindowState(new WindowBounds(100, 80, 1200, 800.5), true, "CHART",
                "Папка с пробелом/Семейный бюджет «2026».md", "12m", filters, "аренда | \"кв\" <b>&amp;",
                "r2@2026-10-01");

        Map<String, String> goalFields = new LinkedHashMap<>();
        goalFields.put("target", "1 000 000,00");
        goalFields.put("byDateEnabled", "true");
        WindowState goal = new WindowState("w1", WindowType.GOAL_CALCULATOR, false, "main", null, Map.of(), goalFields);

        Map<String, String> ruleContext = new LinkedHashMap<>();
        ruleContext.put("mode", "edit");
        ruleContext.put("ruleId", "r3");
        Map<String, String> ruleFields = new LinkedHashMap<>();
        ruleFields.put("title", "Аренда \"квартиры\" <дом> & | ; = : \\ конец");
        ruleFields.put("note", "строка 1\nстрока 2\r\nстрока 3\tтаб");
        ruleFields.put("amount", "45000,00");
        ruleFields.put("category", "");
        WindowState rule = new WindowState("w2", WindowType.RULE_EDITOR, true, "main",
                new WindowBounds(400, 300, 520, 480), ruleContext, ruleFields);

        Map<String, String> alertContext = new LinkedHashMap<>();
        alertContext.put("purpose", "deleteRule");
        alertContext.put("targetId", "r3");
        WindowState alert = new WindowState("w3", WindowType.ALERT, true, "w2", null, alertContext, Map.of());

        WindowState unknown = new WindowState("w4", null, true, "w3", null, Map.of("x", "y"), Map.of("a", "b"));

        Map<String, String> inputFields = new LinkedHashMap<>();
        inputFields.put("value", " пробелы по краям ");
        inputFields.put("key:with;=chars", " ");
        inputFields.put("Контекст", "поле с именем служебной строки");
        WindowState input = new WindowState("w5", WindowType.TEXT_INPUT, true, "w4",
                new WindowBounds(-10.25, 0, 0, 0), Map.of("purpose", "rename"), inputFields);

        return SessionSnapshot.of(SAVED, client, main, PlanState.dirty(TRICKY_PLAN), List.of(goal, rule, alert, unknown, input));
    }

    /**
     * Маркер работающего сеанса.
     *
     * @param client клиент
     * @return маркер с pid 12345
     */
    public static SessionMarker running(String client) {
        return SessionMarker.running(12345, STARTED, client);
    }
}
