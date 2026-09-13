package ru.cashprediction.core.session.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import ru.cashprediction.core.session.MainWindowState;
import ru.cashprediction.core.session.PlanState;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;

/**
 * Аддитивные изменения схемы снимка 1 (правило R3 архитектуры, спецификация интерфейса v2, изменение 6):
 * необязательное поле {@code MainWindowState.whatIfExtra}, ключи карты фильтров {@code pastExpanded},
 * {@code whatIfIncome}, {@code whatIfExpense} и то, что заголовки окон ({@code WindowType.title()}) в снимок не пишутся.
 */
class SnapshotSchemaAdditionsTest {

    private static final Instant SAVED = Instant.parse("2026-09-13T10:15:30.123Z");

    static Stream<SnapshotCodec<String>> codecs() {
        return Stream.of(new JsonSnapshotCodec(), new XmlSnapshotCodec(), new MarkdownSnapshotCodec());
    }

    /** Снимок с «что-если» и всеми дополнительными ключами фильтров. */
    private static SessionSnapshot withAdditions(String client) {
        Map<String, Boolean> filters = new LinkedHashMap<>();
        filters.put("showIncome", true);
        filters.put("showSkipped", false);
        filters.put(MainWindowState.FILTER_PAST_EXPANDED, true);
        filters.put(MainWindowState.FILTER_WHAT_IF_INCOME, true);
        filters.put(MainWindowState.FILTER_WHAT_IF_EXPENSE, false);
        MainWindowState main = new MainWindowState(new WindowBounds(10, 20, 1200, 800), false, "CHART",
                "Пример.md", "M24", filters, "аренда", "r3@2026-10-01", "5000,00");
        WindowState adjustment = new WindowState("w1", WindowType.ADJUSTMENT_EDITOR, true, "main", null,
                Map.of("ruleId", "r1", "originalDate", "2026-10-05"),
                Map.of("action", "CHANGE_AMOUNT", "amount", "95000,00", "date", "", "note", ""));
        return SessionSnapshot.of(SAVED, client, main, PlanState.CLEAN, List.of(adjustment));
    }

    @ParameterizedTest
    @MethodSource("codecs")
    void roundTripsWhatIfExtraAndExtraFilterKeys(SnapshotCodec<String> codec) throws SnapshotFormatException {
        SessionSnapshot snapshot = withAdditions("fx");
        SessionSnapshot decoded = codec.decode(codec.encode(snapshot));
        assertEquals(snapshot, decoded, codec.formatName());
        assertEquals("5000,00", decoded.main().whatIfExtra());
        assertTrue(decoded.main().filter(MainWindowState.FILTER_PAST_EXPANDED, false));
        assertTrue(decoded.main().filter(MainWindowState.FILTER_WHAT_IF_INCOME, false));
        assertFalse(decoded.main().filter(MainWindowState.FILTER_WHAT_IF_EXPENSE, true));
        assertEquals(codec.encode(snapshot), codec.encode(decoded), "повторное кодирование стабильно");
    }

    @ParameterizedTest
    @MethodSource("codecs")
    void emptyWhatIfExtraIsNotWritten(SnapshotCodec<String> codec) {
        SessionSnapshot snapshot = withAdditions("fx");
        SessionSnapshot without = new SessionSnapshot(1, SAVED, "fx", snapshot.main().withWhatIfExtra(""),
                snapshot.plan(), snapshot.windows());
        String text = codec.encode(without);
        assertFalse(text.contains("whatIfExtra"), text);
        assertFalse(text.contains("доп. экономия"), text);
    }

    @Test
    void oldJsonWithoutAdditionsDecodes() throws SnapshotFormatException {
        String json = "{\"schemaVersion\":1,\"savedAt\":\"2026-09-13T10:15:30.123Z\",\"client\":\"fx\","
                + "\"main\":{\"bounds\":null,\"maximized\":false,\"view\":\"TABLE\",\"planPath\":\"План.md\","
                + "\"period\":\"12m\",\"filters\":{\"showIncome\":true},\"filterText\":\"\",\"selectedRowId\":\"\"},"
                + "\"plan\":{\"dirty\":false,\"markdown\":\"\"},\"windows\":[]}";
        MainWindowState main = new JsonSnapshotCodec().decode(json).main();
        assertEquals("", main.whatIfExtra());
        assertFalse(main.filter(MainWindowState.FILTER_PAST_EXPANDED, false), "нет ключа — значение по умолчанию");
        assertEquals(Map.of("showIncome", true), main.filters());
    }

    @Test
    void oldXmlWithoutAdditionsDecodes() throws SnapshotFormatException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <session schema="1" client="swing" state="running" pid="12345" startedAt="2026-09-13T10:00:00Z" savedAt="2026-09-13T10:15:30.123Z">
                  <main x="100" y="80" width="1200" height="800" maximized="false" view="TABLE" period="12m" filterText="">
                    <plan path="Семейный бюджет 2026.md"/>
                    <filters><filter id="showIncome" value="true"/><filter id="showExpense" value="true"/></filters>
                    <selection rowId="r2@2026-10-01"/>
                  </main>
                  <unsavedPlan dirty="false"/>
                  <windows/>
                </session>
                """;
        MainWindowState main = new XmlSnapshotCodec().decode(xml).main();
        assertEquals("", main.whatIfExtra());
        assertEquals("r2@2026-10-01", main.selectedRowId());
        assertEquals(2, main.filters().size());
    }

    @Test
    void oldMarkdownWithoutAdditionsDecodes() throws SnapshotFormatException {
        String md = """
                # Сессия CashPrediction (web)

                - Состояние: running
                - PID сервера: 20440
                - Начата: 2026-09-13T10:00:00Z
                - Сохранено: 2026-09-13T10:15:30.123Z
                - Схема: 1

                ## Главное окно

                - Вид: TABLE
                - План: Семейный бюджет 2026.md
                - Несохранённые изменения: нет
                - Период: 12m
                - Фильтры: showIncome=true; showExpense=true
                - Строка поиска:
                - Выделено: r2@2026-10-01
                - Границы: нет
                - Развёрнуто: нет

                ## Открытые окна
                """;
        MainWindowState main = new MarkdownSnapshotCodec().decode(md).main();
        assertEquals("", main.whatIfExtra());
        assertEquals("TABLE", main.view());
    }

    @Test
    void markdownWhatIfExtraKeywordIsFrozen() throws SnapshotFormatException {
        // Ключ строки — грамматика web-session.md: этап S0.5 переносит его в ресурс формата байт в байт (с запятой и
        // точкой). Фиксированный текст ниже должен читаться всегда, иначе снимки, записанные сейчас, перестанут читаться.
        String line = "- Что-если, доп. экономия в месяц: 5000,00";
        String md = """
                # Сессия CashPrediction (web)

                - Состояние: running
                - PID сервера: 20440
                - Начата: 2026-09-13T10:00:00Z
                - Сохранено: 2026-09-13T10:15:30.123Z
                - Схема: 1

                ## Главное окно

                - Вид: TABLE
                - План: Семейный бюджет 2026.md
                - Несохранённые изменения: нет
                - Период: 12m
                - Фильтры: showIncome=true
                - Строка поиска:
                - Выделено:
                - Границы: нет
                - Развёрнуто: нет
                %s

                ## Открытые окна
                """.formatted(line);
        assertEquals("5000,00", new MarkdownSnapshotCodec().decode(md).main().whatIfExtra());

        String encoded = new MarkdownSnapshotCodec().encode(withAdditions("web"));
        assertTrue(encoded.lines().anyMatch(line::equals), encoded);
    }

    @Test
    void oldEightArgumentConstructorStillWorks() {
        MainWindowState main = new MainWindowState(null, false, "TABLE", "", "12m", Map.of(), "", "");
        assertEquals("", main.whatIfExtra());
        assertEquals(MainWindowState.empty(), new MainWindowState(null, false, "", "", "", Map.of(), "", ""));
    }

    @Test
    void adjustmentEditorTitleFollowsSpec() {
        assertEquals("Корректировка события", WindowType.ADJUSTMENT_EDITOR.title());
    }

    @ParameterizedTest
    @MethodSource("codecs")
    void windowTitlesAreNotPersisted(SnapshotCodec<String> codec) throws SnapshotFormatException {
        SessionSnapshot snapshot = withAdditions("web");
        String text = codec.encode(snapshot);
        // В снимок пишется только имя константы типа окна; заголовок (текст интерфейса) может меняться свободно.
        assertTrue(text.contains("ADJUSTMENT_EDITOR"), text);
        for (WindowType type : WindowType.values()) {
            assertFalse(text.contains(type.title()), codec.formatName() + ": заголовок «" + type.title() + "» попал в снимок");
        }
        assertEquals(WindowType.ADJUSTMENT_EDITOR, codec.decode(text).windows().getFirst().type());
    }
}
