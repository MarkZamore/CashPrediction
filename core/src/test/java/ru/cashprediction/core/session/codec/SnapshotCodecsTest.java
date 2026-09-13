package ru.cashprediction.core.session.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.session.MainWindowState;
import ru.cashprediction.core.session.PlanState;
import ru.cashprediction.core.session.SessionFixtures;
import ru.cashprediction.core.session.SessionMarker;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.session.WindowState;

/**
 * Тесты трёх кодеков снимка: обратимость на трудных данных, точная форма XML и Markdown по образцам плана,
 * отказ на повреждённых данных и на снимках более новой схемы.
 */
class SnapshotCodecsTest {

    static Stream<SnapshotCodec<String>> codecs() {
        return Stream.of(new JsonSnapshotCodec(), new XmlSnapshotCodec(), new MarkdownSnapshotCodec());
    }

    @ParameterizedTest
    @MethodSource("codecs")
    void roundTripsTrickySnapshot(SnapshotCodec<String> codec) throws SnapshotFormatException {
        SessionSnapshot snapshot = SessionFixtures.tricky("fx");
        String encoded = codec.encode(snapshot);
        SessionSnapshot decoded = codec.decode(encoded);
        assertEquals(snapshot, decoded, codec.formatName());
        // Порядок окон, полей и владельцев — часть контракта.
        assertEquals(List.of("w1", "w2", "w3", "w4", "w5"), decoded.windows().stream().map(WindowState::id).toList());
        assertEquals(List.of("title", "note", "amount", "category"), List.copyOf(decoded.windows().get(1).fields().keySet()));
        assertNull(decoded.windows().get(3).type(), "неизвестный тип сохраняется как null");
        assertNull(decoded.windows().get(0).bounds());
        assertEquals("w4", decoded.windows().get(4).ownerId());
        // Повторное кодирование стабильно.
        assertEquals(encoded, codec.encode(decoded), codec.formatName());
    }

    @ParameterizedTest
    @MethodSource("codecs")
    void roundTripsMinimalSnapshot(SnapshotCodec<String> codec) throws SnapshotFormatException {
        SessionSnapshot empty = SessionSnapshot.of(SessionFixtures.SAVED, "web", MainWindowState.empty(), PlanState.CLEAN, List.of());
        assertEquals(empty, codec.decode(codec.encode(empty)), codec.formatName());
        SessionSnapshot emptyDirtyPlan = SessionSnapshot.of(SessionFixtures.SAVED, "web", MainWindowState.empty(),
                PlanState.dirty(""), List.of());
        assertEquals(emptyDirtyPlan, codec.decode(codec.encode(emptyDirtyPlan)), codec.formatName());
    }

    @Test
    void jsonAndXmlPreserveCarriageReturnsInPlanText() throws SnapshotFormatException {
        SessionSnapshot base = SessionFixtures.simple("fx");
        SessionSnapshot crlf = new SessionSnapshot(1, base.savedAt(), "fx", base.main(),
                PlanState.dirty("строка 1\r\nстрока 2\rконец\u0007"), base.windows());
        assertEquals(crlf, new JsonSnapshotCodec().decode(new JsonSnapshotCodec().encode(crlf)));
        SessionSnapshot xmlDecoded = new XmlSnapshotCodec().decode(new XmlSnapshotCodec().encode(crlf));
        // U+0007 недопустим в XML 1.0 и заменяется на U+FFFD; CR сохраняется.
        assertEquals("строка 1\r\nстрока 2\rконец\uFFFD", xmlDecoded.plan().markdown());
    }

    @Test
    void xmlDocumentHasDocumentedShape() throws SnapshotFormatException {
        XmlSnapshotCodec codec = new XmlSnapshotCodec();
        SessionDocument document = new SessionDocument("fx", SessionFixtures.running("fx"), SessionFixtures.simple("fx"));
        String expected = """
                <?xml version="1.0" encoding="UTF-8"?>
                <session schema="1" client="fx" state="running" pid="12345" startedAt="2026-09-13T10:00:00Z" savedAt="2026-09-13T10:15:30.123Z">
                  <main x="100" y="80" width="1200" height="800" maximized="false" view="TABLE" period="12m" filterText="">
                    <plan path="Семейный бюджет 2026.md"/>
                    <filters><filter id="showIncome" value="true"/><filter id="showExpense" value="true"/></filters>
                    <selection rowId="r2@2026-10-01"/>
                  </main>
                  <unsavedPlan dirty="true"><![CDATA[# План: Семейный бюджет 2026
                ]]></unsavedPlan>
                  <windows>
                    <window id="w1" type="RULE_EDITOR" modal="true" owner="main" x="400" y="300" width="520" height="480">
                      <context key="mode" value="edit"/><context key="ruleId" value="r3"/>
                      <field id="title" value="Аренда"/>
                      <field id="kind" value="EXPENSE"/>
                      <field id="amount" value="45000,00"/>
                    </window>
                  </windows>
                </session>
                """;
        assertEquals(expected, codec.encodeDocument(document));
        assertEquals(document, codec.decodeDocument(expected));
    }

    @Test
    void xmlEscapesAttributesAndCdata() {
        String xml = new XmlSnapshotCodec().encode(SessionFixtures.tricky("fx"));
        assertTrue(xml.contains("value=\"Аренда &quot;квартиры&quot; &lt;дом&gt; &amp; | ; = : \\ конец\""), xml);
        assertTrue(xml.contains("value=\"строка 1&#10;строка 2&#13;&#10;строка 3&#9;таб\""), xml);
        assertTrue(xml.contains("]]]]><![CDATA[>"), "«]]>» в тексте плана разбивает CDATA");
        assertTrue(xml.contains("<window id=\"w4\" type=\"\" modal=\"true\" owner=\"w3\">"), xml);
        assertTrue(xml.contains("<window id=\"w3\" type=\"ALERT\" modal=\"true\" owner=\"w2\">"), xml);
    }

    @Test
    void xmlMarkerOnlyDocument() throws SnapshotFormatException {
        XmlSnapshotCodec codec = new XmlSnapshotCodec();
        SessionDocument markerOnly = new SessionDocument("swing", SessionFixtures.running("swing"), null);
        String text = codec.encodeDocument(markerOnly);
        assertEquals("""
                <?xml version="1.0" encoding="UTF-8"?>
                <session schema="1" client="swing" state="running" pid="12345" startedAt="2026-09-13T10:00:00Z"/>
                """, text);
        assertEquals(markerOnly, codec.decodeDocument(text));
        SnapshotFormatException e = assertThrows(SnapshotFormatException.class, () -> codec.decode(text));
        assertTrue(e.getMessage().contains("нет снимка"), e.getMessage());
    }

    @Test
    void xmlRejectsDoctypeAndExternalEntities() {
        String xxe = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE session [<!ENTITY secret SYSTEM "file:///C:/Windows/win.ini">]>
                <session schema="1" client="fx" state="running" pid="1" startedAt="2026-09-13T10:00:00Z">&secret;</session>
                """;
        SnapshotFormatException e = assertThrows(SnapshotFormatException.class, () -> new XmlSnapshotCodec().decodeDocument(xxe));
        assertTrue(e.getMessage().startsWith("XML-файл сессии повреждён"), e.getMessage());
    }

    @Test
    void markdownDocumentHasDocumentedShape() throws SnapshotFormatException {
        MarkdownSnapshotCodec codec = new MarkdownSnapshotCodec();
        SessionMarker marker = SessionMarker.running(20440, SessionFixtures.STARTED, "web");
        SessionDocument document = new SessionDocument("web", marker, SessionFixtures.simple("web"));
        String expected = """
                # Сессия CashPrediction (web)

                - Состояние: running
                - PID сервера: 20440
                - Начата: 2026-09-13T10:00:00Z
                - Сохранено: 2026-09-13T10:15:30.123Z
                - Схема: 1

                ## Главное окно

                - Вид: TABLE
                - План: Семейный бюджет 2026.md
                - Несохранённые изменения: да (web-session.plan.md)
                - Период: 12m
                - Фильтры: showIncome=true; showExpense=true
                - Строка поиска:
                - Выделено: r2@2026-10-01
                - Границы: x=100; y=80; width=1200; height=800
                - Развёрнуто: нет

                ## Открытые окна

                ### w1 — RULE_EDITOR (модальное, владелец: main)

                - Контекст: mode=edit; ruleId=r3
                - Границы: x=400; y=300; width=520; height=480
                - title: Аренда
                - kind: EXPENSE
                - amount: 45000,00
                """;
        assertEquals(expected, codec.encodeDocument(document, "web-session.plan.md"));
        assertEquals("web-session.plan.md", MarkdownSnapshotCodec.externalPlanFile(expected));
        // Текст плана вынесен: при разборе он пуст, его подставляет хранилище.
        SessionDocument decoded = codec.decodeDocument(expected);
        assertEquals(marker, decoded.marker());
        assertEquals(PlanState.dirty(""), decoded.snapshot().plan());
        assertEquals(SessionFixtures.simple("web").windows(), decoded.snapshot().windows());
        // CRLF, записанный Блокнотом, читается так же.
        assertEquals(decoded, codec.decodeDocument(expected.replace("\n", "\r\n")));
    }

    @Test
    void markdownEscapesValuesReadably() {
        String text = new MarkdownSnapshotCodec().encode(SessionFixtures.tricky("web"));
        assertTrue(text.contains("- title: Аренда \"квартиры\" <дом> & | ; = : \\\\ конец\n"), text);
        assertTrue(text.contains("- note: строка 1\\nстрока 2\\r\\nстрока 3\\tтаб\n"), text);
        assertTrue(text.contains("- value: \\sпробелы по краям\\s\n"), text);
        assertTrue(text.contains("- key\\:with;=chars: \\s\n"), text);
        assertTrue(text.contains("### w4 — ? (модальное, владелец: w3)"), text);
        assertTrue(text.contains("### w1 — GOAL_CALCULATOR (немодальное, владелец: main)"), text);
        assertTrue(text.contains("- Фильтры: showIncome=true; showExpense=false; фильтр\\;с\\=символами=true\n"), text);
        assertTrue(text.contains("## Несохранённый план\n\n`````\n# План: Семейный бюджет 2026\n"), text);
        assertTrue(text.contains("- Контекст:\n- Границы: нет\n- target: 1 000 000,00"), text);
        // Текст плана сам содержит строку-ловушку «- Состояние:», поэтому проверяем только шапку файла.
        String header = text.substring(0, text.indexOf("## Главное окно"));
        assertFalse(header.contains("- Состояние"), "без маркера строк маркера нет");
    }

    @Test
    void markdownMarkerOnlyDocument() throws SnapshotFormatException {
        MarkdownSnapshotCodec codec = new MarkdownSnapshotCodec();
        SessionDocument markerOnly = new SessionDocument("web", SessionMarker.running(7, SessionFixtures.STARTED, "web").closed(), null);
        String text = codec.encodeDocument(markerOnly, null);
        assertEquals("""
                # Сессия CashPrediction (web)

                - Состояние: closed
                - PID сервера: 7
                - Начата: 2026-09-13T10:00:00Z
                - Схема: 1
                """, text);
        assertEquals(markerOnly, codec.decodeDocument(text));
    }

    @Test
    void jsonMarkerRoundTrip() throws SnapshotFormatException {
        JsonSnapshotCodec codec = new JsonSnapshotCodec();
        SessionMarker marker = SessionFixtures.running("web");
        Map<String, Object> json = codec.markerToJsonObject(marker);
        assertEquals(marker, codec.markerFromJsonObject(JsonParser.parseObject(ru.cashprediction.core.json.JsonWriter.write(json))));
        assertThrows(SnapshotFormatException.class, () -> codec.markerFromJsonObject(Map.of("state", "running")));
    }

    @Test
    void rejectsNewerSchema() {
        String json = new JsonSnapshotCodec().encode(SessionFixtures.simple("fx")).replace("\"schemaVersion\":1", "\"schemaVersion\":2");
        assertNewer(() -> new JsonSnapshotCodec().decode(json));
        String xml = new XmlSnapshotCodec().encode(SessionFixtures.simple("fx")).replace("schema=\"1\"", "schema=\"2\"");
        assertNewer(() -> new XmlSnapshotCodec().decode(xml));
        String md = new MarkdownSnapshotCodec().encode(SessionFixtures.simple("web")).replace("- Схема: 1", "- Схема: 2");
        assertNewer(() -> new MarkdownSnapshotCodec().decode(md));
    }

    /** Действие, бросающее исключение формата. */
    private interface Decode {
        void run() throws SnapshotFormatException;
    }

    private static void assertNewer(Decode decode) {
        SnapshotFormatException e = assertThrows(SnapshotFormatException.class, decode::run);
        assertTrue(e.getMessage().contains("более новой версией"), e.getMessage());
    }

    @Test
    void corruptInputsProduceRussianMessages() {
        assertCorrupt(() -> new JsonSnapshotCodec().decode("{\"schemaVersion\":1,"), "Снимок в формате JSON повреждён");
        assertCorrupt(() -> new JsonSnapshotCodec().decode("{\"schemaVersion\":1,\"savedAt\":\"вчера\",\"client\":\"fx\"}"),
                "Некорректный момент времени");
        assertCorrupt(() -> new JsonSnapshotCodec().decode(
                "{\"schemaVersion\":1,\"savedAt\":\"2026-09-13T10:15:30Z\",\"client\":\"fx\",\"windows\":[{\"id\":5}]}"),
                "Снимок в формате JSON повреждён");
        assertCorrupt(() -> new XmlSnapshotCodec().decode("<session schema=\"1\""), "XML-файл сессии повреждён");
        assertCorrupt(() -> new XmlSnapshotCodec().decode("<other/>"), "корневой элемент <other>");
        assertCorrupt(() -> new XmlSnapshotCodec().decode(""), "XML-файл сессии пуст");
        assertCorrupt(() -> new MarkdownSnapshotCodec().decode("просто текст"), "нет заголовка");
        assertCorrupt(() -> new MarkdownSnapshotCodec().decode("# Сессия CashPrediction (web)\n\n## Главное окно\n"),
                "нет строки «Сохранено»");
        assertCorrupt(() -> new MarkdownSnapshotCodec().decode(
                "# Сессия CashPrediction (web)\n\n- Сохранено: 2026-09-13T10:15:30Z\n\n## Главное окно\n\n## Открытые окна\n\n### кривой заголовок\n"),
                "строка 9: некорректный заголовок окна");
    }

    private static void assertCorrupt(Decode decode, String fragment) {
        SnapshotFormatException e = assertThrows(SnapshotFormatException.class, decode::run);
        assertTrue(e.getMessage().contains(fragment), e.getMessage());
    }
}
