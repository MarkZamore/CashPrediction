package ru.cashprediction.core.session.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import ru.cashprediction.core.session.MainWindowState;
import ru.cashprediction.core.session.PlanState;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;

/**
 * Этап S0.5 для кодеков снимка.
 *
 * <p><b>Грамматика не изменилась:</b> web-session.md, собранный из слов нелокализуемого ресурса формата, совпадает с
 * эталоном байт в байт и читается обратно (в том числе заголовок окна с «модальное, владелец:»).</p>
 *
 * <p><b>Сообщения:</b> ошибки разбора всех трёх кодеков берутся из каталога текстов и совпадают с прежними русскими
 * строками; слова формата («да», «нет», названия разделов) подставляются в них из ресурса формата.</p>
 */
class CodecTextsTest {

    private static final Instant SAVED = Instant.parse("2026-09-13T10:15:30.123Z");

    /** Эталон web-session.md: те же слова, что писались до переноса грамматики в ресурс. */
    private static final String GOLDEN = """
            # Сессия CashPrediction (web)

            - Сохранено: 2026-09-13T10:15:30.123Z
            - Схема: 1

            ## Главное окно

            - Вид: TABLE
            - План: План.md
            - Несохранённые изменения: нет
            - Период: M12
            - Фильтры: showIncome=true
            - Строка поиска:
            - Выделено:
            - Границы: нет
            - Развёрнуто: да

            ## Открытые окна

            ### w1 — RULE_EDITOR (модальное, владелец: main)

            - Контекст: mode=edit
            - Границы: нет
            - title: Аренда

            ### w2 — GOAL_CALCULATOR (немодальное, владелец: w1)

            - Контекст:
            - Границы: x=10; y=20; width=300; height=200
            """;

    /** Начало файла с обязательными строками заголовка, после которого идёт проверяемый раздел. */
    private static final String HEAD = """
            # Сессия CashPrediction (web)

            - Сохранено: 2026-09-13T10:15:30Z
            - Схема: 1

            ## Главное окно

            """;

    @Test
    void markdownGrammarIsByteIdentical() throws SnapshotFormatException {
        Map<String, Boolean> filters = new LinkedHashMap<>();
        filters.put("showIncome", true);
        MainWindowState main = new MainWindowState(null, true, "TABLE", "План.md", "M12", filters, "", "", "");
        WindowState rule = new WindowState("w1", WindowType.RULE_EDITOR, true, "main", null,
                Map.of("mode", "edit"), Map.of("title", "Аренда"));
        WindowState goal = new WindowState("w2", WindowType.GOAL_CALCULATOR, false, "w1",
                new ru.cashprediction.core.session.WindowBounds(10, 20, 300, 200), Map.of(), Map.of());
        SessionSnapshot snapshot = SessionSnapshot.of(SAVED, "web", main, PlanState.CLEAN, List.of(rule, goal));
        MarkdownSnapshotCodec codec = new MarkdownSnapshotCodec();
        assertEquals(GOLDEN, codec.encode(snapshot));
        SessionSnapshot decoded = codec.decode(GOLDEN);
        assertEquals(codec.encode(decoded), GOLDEN);
        assertTrue(decoded.windows().get(0).modal());
        assertEquals("w1", decoded.windows().get(1).ownerId());
        assertEquals(false, decoded.windows().get(1).modal());
    }

    @Test
    void markdownMessages() {
        MarkdownSnapshotCodec codec = new MarkdownSnapshotCodec();
        assertEquals("Файл сессии пуст", message(() -> codec.decode(" ")));
        assertEquals("Файл сессии повреждён: нет заголовка «# Сессия CashPrediction (клиент)»", message(() -> codec.decode("текст")));
        assertEquals("Файл сессии повреждён, строка 1: ожидался заголовок «# Сессия CashPrediction (клиент)»",
                message(() -> codec.decode("# Другое")));
        assertEquals("В файле сессии нет снимка (раздел «Главное окно» отсутствует)",
                message(() -> codec.decode("# Сессия CashPrediction (web)\n\n- Схема: 1\n")));
        assertEquals("Файл сессии повреждён, строка 3: в строке списка нет «:»",
                message(() -> codec.decode("# Сессия CashPrediction (web)\n\n- Схема\n")));
        assertEquals("Файл сессии повреждён: нет строки «PID сервера»",
                message(() -> codec.decode("# Сессия CashPrediction (web)\n\n- Состояние: running\n")));
        assertEquals("Файл сессии повреждён: «Развёрнуто» должно быть «да» или «нет»",
                message(() -> codec.decode(HEAD + "- Развёрнуто: может\n")));
        assertEquals("Файл сессии повреждён: «Несохранённые изменения» должно быть «да» или «нет»",
                message(() -> codec.decode(HEAD + "- Несохранённые изменения: может\n")));
        assertEquals("Файл сессии повреждён: в «Границы» нет «width»",
                message(() -> codec.decode(HEAD + "- Границы: x=1; y=2\n")));
        assertEquals("Файл сессии повреждён: в «a» нет «=»", message(() -> codec.decode(HEAD + "- Фильтры: a\n")));
        // HEAD занимает строки 1–7, добавленный текст начинается со строки 8.
        assertEquals("Файл сессии повреждён, строка 10: некорректный заголовок окна",
                message(() -> codec.decode(HEAD + "## Открытые окна\n\n### w1 RULE_EDITOR\n")));
        assertEquals("Файл сессии повреждён, строка 10: строка списка до заголовка окна",
                message(() -> codec.decode(HEAD + "## Открытые окна\n\n- title: a\n")));
        assertEquals("Файл сессии повреждён, строка 15: поле «title» окна w1 повторяется",
                message(() -> codec.decode(HEAD + "## Открытые окна\n\n### w1 — ALERT (модальное, владелец: main)\n\n"
                        + "- Контекст:\n- Границы: нет\n- title: a\n- title: b\n")));
        assertEquals("Файл сессии повреждён, строка 10: в разделе «Несохранённый план» ожидался блок кода",
                message(() -> codec.decode(HEAD + "## Несохранённый план\n\nтекст\n")));
        assertEquals("Файл сессии повреждён, строка 10: блок кода с текстом плана не закрыт",
                message(() -> codec.decode(HEAD + "## Несохранённый план\n\n```\nтекст\n")));
    }

    @Test
    void xmlMessages() {
        XmlSnapshotCodec codec = new XmlSnapshotCodec();
        assertEquals("XML-файл сессии пуст", message(() -> codec.decode("")));
        assertEquals("XML-файл сессии повреждён: корневой элемент <other>, ожидался <session>",
                message(() -> codec.decode("<other/>")));
        assertEquals("XML-файл сессии повреждён: у элемента <session> нет атрибута «schema»",
                message(() -> codec.decode("<session client=\"fx\"/>")));
        assertEquals("В XML-файле сессии нет снимка (элемент <main> отсутствует)",
                message(() -> codec.decode("<session schema=\"1\" client=\"fx\"/>")));
        assertEquals("Некорректное целое число в «schema»: «x»",
                message(() -> codec.decode("<session schema=\"x\" client=\"fx\"/>")));
        assertEquals("Снимок создан более новой версией программы (схема 2, поддерживается 1)",
                message(() -> codec.decode("<session schema=\"2\" client=\"fx\"/>")));
        assertTrue(message(() -> codec.decode("<session")).startsWith("XML-файл сессии повреждён (строка 1, столбец "));
    }

    @Test
    void jsonMessages() {
        JsonSnapshotCodec codec = new JsonSnapshotCodec();
        assertTrue(message(() -> codec.decode("{")).startsWith("Снимок в формате JSON повреждён: Некорректный JSON, строка 1"));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 0L);
        assertEquals("Некорректная версия схемы снимка: 0", message(() -> codec.fromJsonObject(root)));
        root.put("schemaVersion", 1L);
        root.put("savedAt", SAVED.toString());
        root.put("client", "fx");
        root.put("main", Map.of("filters", Map.of("a", 1L)));
        assertEquals("Фильтр «a» должен быть true или false", message(() -> codec.fromJsonObject(root)));
        root.put("main", Map.of());
        root.put("windows", List.of(Map.of("id", "w1", "context", Map.of("k", 1L))));
        assertEquals("Значение «context.k» должно быть строкой", message(() -> codec.fromJsonObject(root)));
        root.put("windows", List.of(Map.of("id", " ")));
        assertEquals("Снимок в формате JSON повреждён: Идентификатор окна не может быть пустым",
                message(() -> codec.fromJsonObject(root)));
        assertEquals("Маркер сеанса в формате JSON повреждён: Отсутствует обязательное поле «state»",
                message(() -> codec.markerFromJsonObject(Map.of())));
    }

    @Test
    void valueMessages() {
        assertEquals("Некорректное число в «x»: «abc»", message(() -> CodecText.parseNumber("abc", "x")));
        assertEquals("Ожидалось true или false в «filter a»: «yes»", message(() -> CodecText.parseBoolean("yes", "filter a")));
        assertEquals("Некорректный момент времени в «savedAt»: «вчера»", message(() -> CodecText.parseInstant("вчера", "savedAt")));
        assertEquals("Некорректная версия схемы снимка: 0", message(() -> CodecText.parseSchema("0")));
    }

    /** @return сообщение {@link SnapshotFormatException}, брошенного действием */
    private static String message(Executable action) {
        return assertThrows(SnapshotFormatException.class, action).getMessage();
    }
}
