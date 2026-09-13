package ru.cashprediction.core.session.codec;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import ru.cashprediction.core.session.MainWindowState;
import ru.cashprediction.core.session.PlanState;
import ru.cashprediction.core.session.SessionMarker;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.session.SnapshotSchema;
import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;

/**
 * XML-представление файла сессии {@code CashMemory/session-<клиент>.xml} (раздел 5.4 плана).
 *
 * <p>Пример результата:</p>
 * <pre>{@code
 * <?xml version="1.0" encoding="UTF-8"?>
 * <session schema="1" client="fx" state="running" pid="12345" startedAt="2026-09-13T10:00:00Z" savedAt="2026-09-13T10:15:30.123Z">
 *   <main x="100" y="80" width="1200" height="800" maximized="false" view="TABLE" period="12m" filterText="">
 *     <plan path="Семейный бюджет 2026.md"/>
 *     <filters><filter id="showIncome" value="true"/><filter id="showExpense" value="true"/></filters>
 *     <selection rowId="r2@2026-10-01"/>
 *   </main>
 *   <unsavedPlan dirty="true"><![CDATA[# План: Семейный бюджет 2026
 * ...]]></unsavedPlan>
 *   <windows>
 *     <window id="w1" type="RULE_EDITOR" modal="true" owner="main" x="400" y="300" width="520" height="480">
 *       <context key="mode" value="edit"/><context key="ruleId" value="r3"/>
 *       <field id="title" value="Аренда"/>
 *     </window>
 *   </windows>
 * </session>
 * }</pre>
 *
 * <p><b>Запись</b> выполняется собственным форматированием, а не {@code Transformer}: отступы
 * {@code Transformer} вокруг CDATA и смешанного содержимого различаются между версиями JDK,
 * а файл должен выглядеть ровно как документированный образец и одинаково во всех сборках.
 * Экранирование полное: в атрибутах {@code & < > "} и переводы строк/табуляция как символьные
 * ссылки ({@code &#10;}), иначе парсер нормализовал бы их в пробелы; в CDATA последовательность
 * {@code ]]>} и символ CR выносятся за пределы секции. Символы, запрещённые в XML 1.0
 * (U+0000–U+001F, кроме табуляции и переводов строк, одиночные суррогаты, U+FFFE/U+FFFF), заменяются
 * на U+FFFD — их нельзя записать в XML 1.0 никаким способом, а в полях форм они не встречаются.</p>
 *
 * <p><b>Чтение</b> — DOM с защитой от XXE: запрещены DOCTYPE, внешние сущности и внешние DTD.
 * Файл лежит в пользовательской папке и может быть подменён, поэтому доверять ему нельзя.
 * Неизвестные элементы и атрибуты игнорируются ради совместимости с будущими версиями.</p>
 *
 * <p>Класс без состояния (фабрика парсера создаётся на каждый разбор, потому что
 * {@link DocumentBuilderFactory} не потокобезопасна), потокобезопасен.</p>
 */
public final class XmlSnapshotCodec implements SnapshotCodec<String> {

    /** Отступ одного уровня. */
    private static final String INDENT = "  ";

    /** Создаёт кодек (состояния нет, экземпляры взаимозаменяемы). */
    public XmlSnapshotCodec() {
    }

    @Override
    public String formatName() {
        return "XML";
    }

    @Override
    public String encode(SessionSnapshot snapshot) {
        return encodeDocument(new SessionDocument(snapshot.client(), null, snapshot));
    }

    @Override
    public SessionSnapshot decode(String encoded) throws SnapshotFormatException {
        SessionSnapshot snapshot = decodeDocument(encoded).snapshot();
        if (snapshot == null) {
            throw new SnapshotFormatException("В XML-файле сессии нет снимка (элемент <main> отсутствует)");
        }
        return snapshot;
    }

    /**
     * Записывает файл сессии: маркер (атрибуты корня) и снимок, если они есть.
     *
     * @param document содержимое файла
     * @return XML-текст в UTF-8-совместимом виде с переводами строк LF и завершающим переводом строки
     */
    public String encodeDocument(SessionDocument document) {
        SessionSnapshot snapshot = document.snapshot();
        SessionMarker marker = document.marker();
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<session");
        attr(sb, "schema", String.valueOf(snapshot != null ? snapshot.schemaVersion() : SnapshotSchema.CURRENT));
        attr(sb, "client", document.client());
        if (marker != null) {
            attr(sb, "state", marker.state());
            attr(sb, "pid", String.valueOf(marker.pid()));
            attr(sb, "startedAt", marker.startedAt().toString());
        }
        if (snapshot == null) {
            sb.append("/>\n");
            return sb.toString();
        }
        attr(sb, "savedAt", snapshot.savedAt().toString());
        sb.append(">\n");
        writeMain(sb, snapshot.main());
        writePlan(sb, snapshot.plan());
        writeWindows(sb, snapshot.windows());
        sb.append("</session>\n");
        return sb.toString();
    }

    /**
     * Разбирает файл сессии.
     *
     * @param text XML-текст
     * @return маркер и снимок (каждый может отсутствовать)
     * @throws SnapshotFormatException если XML некорректен, содержит DOCTYPE или значения неверны
     */
    public SessionDocument decodeDocument(String text) throws SnapshotFormatException {
        Element root = parse(text).getDocumentElement();
        if (!"session".equals(root.getTagName())) {
            throw new SnapshotFormatException("XML-файл сессии повреждён: корневой элемент <" + root.getTagName()
                    + ">, ожидался <session>");
        }
        try {
            int schema = CodecText.parseSchema(required(root, "schema"));
            String client = required(root, "client");
            SessionMarker marker = null;
            if (root.hasAttribute("state")) {
                marker = new SessionMarker(root.getAttribute("state"), CodecText.parseLong(required(root, "pid"), "pid"),
                        CodecText.parseInstant(required(root, "startedAt"), "startedAt"), client);
            }
            SessionSnapshot snapshot = null;
            Element main = child(root, "main");
            if (main != null) {
                var savedAt = CodecText.parseInstant(required(root, "savedAt"), "savedAt");
                snapshot = new SessionSnapshot(schema, savedAt, client, readMain(main), readPlan(child(root, "unsavedPlan")),
                        readWindows(child(root, "windows")));
            }
            return new SessionDocument(client, marker, snapshot);
        } catch (IllegalArgumentException | NullPointerException e) {
            // Нарушение инвариантов записей (например, отрицательный размер окна).
            throw new SnapshotFormatException("XML-файл сессии повреждён: " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- запись

    private static void writeMain(StringBuilder sb, MainWindowState main) {
        indent(sb, 1).append("<main");
        bounds(sb, main.bounds());
        attr(sb, "maximized", String.valueOf(main.maximized()));
        attr(sb, "view", main.view());
        attr(sb, "period", main.period());
        attr(sb, "filterText", main.filterText());
        sb.append(">\n");
        indent(sb, 2).append("<plan");
        attr(sb, "path", main.planPath());
        sb.append("/>\n");
        indent(sb, 2);
        if (main.filters().isEmpty()) {
            sb.append("<filters/>\n");
        } else {
            // Фильтры в одну строку, как в образце: их мало, и так они читаются как одна настройка.
            sb.append("<filters>");
            main.filters().forEach((id, value) -> {
                sb.append("<filter");
                attr(sb, "id", id);
                attr(sb, "value", String.valueOf(value));
                sb.append("/>");
            });
            sb.append("</filters>\n");
        }
        indent(sb, 2).append("<selection");
        attr(sb, "rowId", main.selectedRowId());
        sb.append("/>\n");
        indent(sb, 1).append("</main>\n");
    }

    private static void writePlan(StringBuilder sb, PlanState plan) {
        indent(sb, 1).append("<unsavedPlan");
        attr(sb, "dirty", String.valueOf(plan.dirty()));
        if (!plan.dirty() && plan.markdown().isEmpty()) {
            sb.append("/>\n");
            return;
        }
        sb.append("><![CDATA[").append(cdata(plan.markdown())).append("]]></unsavedPlan>\n");
    }

    private static void writeWindows(StringBuilder sb, List<WindowState> windows) {
        if (windows.isEmpty()) {
            indent(sb, 1).append("<windows/>\n");
            return;
        }
        indent(sb, 1).append("<windows>\n");
        for (WindowState window : windows) {
            indent(sb, 2).append("<window");
            attr(sb, "id", window.id());
            attr(sb, "type", window.type() == null ? "" : window.type().name());
            attr(sb, "modal", String.valueOf(window.modal()));
            attr(sb, "owner", window.ownerId());
            bounds(sb, window.bounds());
            if (window.context().isEmpty() && window.fields().isEmpty()) {
                sb.append("/>\n");
                continue;
            }
            sb.append(">\n");
            if (!window.context().isEmpty()) {
                // Контекст в одну строку, как в образце: это «адрес» окна, а не данные пользователя.
                indent(sb, 3);
                window.context().forEach((key, value) -> {
                    sb.append("<context");
                    attr(sb, "key", key);
                    attr(sb, "value", value);
                    sb.append("/>");
                });
                sb.append('\n');
            }
            // Поля — по одному на строку: у редактора правила их 16, в одну строку их не прочесть.
            window.fields().forEach((id, value) -> {
                indent(sb, 3).append("<field");
                attr(sb, "id", id);
                attr(sb, "value", value);
                sb.append("/>\n");
            });
            indent(sb, 2).append("</window>\n");
        }
        indent(sb, 1).append("</windows>\n");
    }

    private static void bounds(StringBuilder sb, WindowBounds bounds) {
        if (bounds == null) {
            return;
        }
        attr(sb, "x", CodecText.formatNumber(bounds.x()));
        attr(sb, "y", CodecText.formatNumber(bounds.y()));
        attr(sb, "width", CodecText.formatNumber(bounds.width()));
        attr(sb, "height", CodecText.formatNumber(bounds.height()));
    }

    private static StringBuilder indent(StringBuilder sb, int level) {
        return sb.append(INDENT.repeat(level));
    }

    private static void attr(StringBuilder sb, String name, String value) {
        sb.append(' ').append(name).append("=\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                // Литеральные переводы строк и табуляции парсер превратил бы в пробелы (нормализация атрибутов).
                case '\n' -> sb.append("&#10;");
                case '\r' -> sb.append("&#13;");
                case '\t' -> sb.append("&#9;");
                default -> i = appendXmlChar(sb, value, i);
            }
        }
        sb.append('"');
    }

    private static String cdata(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ']' && text.startsWith("]]>", i)) {
                // «]]>» закрыл бы секцию: разбиваем её на две, символ «>» уходит во вторую.
                sb.append("]]]]><![CDATA[>");
                i += 2;
            } else if (c == '\r') {
                // CR внутри CDATA парсер нормализует в LF; вынесенный наружу &#13; сохраняется точно.
                sb.append("]]>&#13;<![CDATA[");
            } else if (c == '\n' || c == '\t') {
                sb.append(c);
            } else {
                i = appendXmlChar(sb, text, i);
            }
        }
        return sb.toString();
    }

    /**
     * Добавляет символ (или суррогатную пару) в вывод, заменяя недопустимые в XML 1.0 символы на U+FFFD.
     *
     * @return индекс последнего обработанного символа
     */
    private static int appendXmlChar(StringBuilder sb, String text, int i) {
        char c = text.charAt(i);
        if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
            sb.append(c).append(text.charAt(i + 1));
            return i + 1;
        }
        boolean valid = (c >= 0x20 && c <= 0xD7FF) || (c >= 0xE000 && c <= 0xFFFD) || c == '\t' || c == '\n' || c == '\r';
        sb.append(valid ? c : '�');
        return i;
    }

    // ---------------------------------------------------------------- чтение

    private static Document parse(String text) throws SnapshotFormatException {
        if (text == null || text.isBlank()) {
            throw new SnapshotFormatException("XML-файл сессии пуст");
        }
        try {
            DocumentBuilder builder = secureFactory().newDocumentBuilder();
            // Без своего обработчика парсер печатает «[Fatal Error]» в stderr, а ошибка и так станет исключением.
            builder.setErrorHandler(new ErrorHandler() {
                @Override
                public void warning(SAXParseException exception) {
                    // Предупреждения парсера не мешают разобрать снимок.
                }

                @Override
                public void error(SAXParseException exception) throws SAXException {
                    throw exception;
                }

                @Override
                public void fatalError(SAXParseException exception) throws SAXException {
                    throw exception;
                }
            });
            return builder.parse(new InputSource(new StringReader(text)));
        } catch (SAXParseException e) {
            throw new SnapshotFormatException("XML-файл сессии повреждён (строка " + e.getLineNumber() + ", столбец "
                    + e.getColumnNumber() + "): " + e.getMessage(), e);
        } catch (SAXException | IOException e) {
            throw new SnapshotFormatException("XML-файл сессии повреждён: " + e.getMessage(), e);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("XML-парсер JDK не поддерживает защищённый режим", e);
        }
    }

    private static DocumentBuilderFactory secureFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setValidating(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        // Главная защита от XXE и «миллиарда смешков»: документ с DOCTYPE отклоняется целиком.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    private static MainWindowState readMain(Element main) throws SnapshotFormatException {
        Element plan = child(main, "plan");
        Element selection = child(main, "selection");
        Map<String, Boolean> filters = new LinkedHashMap<>();
        Element filtersElement = child(main, "filters");
        if (filtersElement != null) {
            for (Element filter : children(filtersElement, "filter")) {
                String id = required(filter, "id");
                filters.put(id, CodecText.parseBoolean(required(filter, "value"), "filter " + id));
            }
        }
        return new MainWindowState(readBounds(main),
                optionalBoolean(main, "maximized", false),
                main.getAttribute("view"),
                plan == null ? "" : plan.getAttribute("path"),
                main.getAttribute("period"),
                filters,
                main.getAttribute("filterText"),
                selection == null ? "" : selection.getAttribute("rowId"));
    }

    private static PlanState readPlan(Element unsavedPlan) throws SnapshotFormatException {
        if (unsavedPlan == null) {
            return PlanState.CLEAN;
        }
        // getTextContent склеивает соседние CDATA-секции и символьные ссылки, разбитые при записи.
        return new PlanState(optionalBoolean(unsavedPlan, "dirty", false), unsavedPlan.getTextContent());
    }

    private static List<WindowState> readWindows(Element windows) throws SnapshotFormatException {
        List<WindowState> result = new ArrayList<>();
        if (windows == null) {
            return result;
        }
        for (Element window : children(windows, "window")) {
            Map<String, String> context = new LinkedHashMap<>();
            for (Element entry : children(window, "context")) {
                context.put(required(entry, "key"), entry.getAttribute("value"));
            }
            Map<String, String> fields = new LinkedHashMap<>();
            for (Element field : children(window, "field")) {
                fields.put(required(field, "id"), field.getAttribute("value"));
            }
            WindowType type = WindowType.fromName(window.getAttribute("type")).orElse(null);
            result.add(new WindowState(required(window, "id"), type, optionalBoolean(window, "modal", true),
                    window.getAttribute("owner"), readBounds(window), context, fields));
        }
        return result;
    }

    private static WindowBounds readBounds(Element element) throws SnapshotFormatException {
        boolean any = element.hasAttribute("x") || element.hasAttribute("y")
                || element.hasAttribute("width") || element.hasAttribute("height");
        if (!any) {
            return null;
        }
        return new WindowBounds(
                CodecText.parseNumber(required(element, "x"), "x"),
                CodecText.parseNumber(required(element, "y"), "y"),
                CodecText.parseNumber(required(element, "width"), "width"),
                CodecText.parseNumber(required(element, "height"), "height"));
    }

    private static boolean optionalBoolean(Element element, String name, boolean defaultValue)
            throws SnapshotFormatException {
        return element.hasAttribute(name) ? CodecText.parseBoolean(element.getAttribute(name), name) : defaultValue;
    }

    private static String required(Element element, String name) throws SnapshotFormatException {
        if (!element.hasAttribute(name)) {
            throw new SnapshotFormatException("XML-файл сессии повреждён: у элемента <" + element.getTagName()
                    + "> нет атрибута «" + name + "»");
        }
        return element.getAttribute(name);
    }

    private static Element child(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && element.getTagName().equals(name)) {
                return element;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && element.getTagName().equals(name)) {
                result.add(element);
            }
        }
        return result;
    }
}
