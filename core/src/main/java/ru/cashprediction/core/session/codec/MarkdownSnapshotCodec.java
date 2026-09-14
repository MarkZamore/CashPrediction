package ru.cashprediction.core.session.codec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.cashprediction.core.format.FormatWords;
import ru.cashprediction.core.session.MainWindowState;
import ru.cashprediction.core.session.PlanState;
import ru.cashprediction.core.session.SessionMarker;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.session.SnapshotSchema;
import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.text.Texts;

/**
 * Markdown-представление файла сессии web-сервера {@code CashMemory/web-session.md} (раздел 5.5 плана).
 *
 * <p>Пример результата:</p>
 * <pre>
 * # Сессия CashPrediction (web)
 *
 * - Состояние: running
 * - PID сервера: 20440
 * - Начата: 2026-09-13T10:00:00Z
 * - Сохранено: 2026-09-13T10:15:30Z
 * - Схема: 1
 *
 * ## Главное окно
 *
 * - Вид: TABLE
 * - План: Семейный бюджет 2026.md
 * - Несохранённые изменения: да (web-session.plan.md)
 * - Период: 12m
 * - Фильтры: showIncome=true; showExpense=true; showOneTime=true
 * - Строка поиска:
 * - Выделено: r2@2026-10-01
 * - Границы: нет
 * - Развёрнуто: нет
 * - Что-если, доп. экономия в месяц: 5000,00     (необязательная строка: только если задано)
 *
 * ## Открытые окна
 *
 * ### w1 - RULE_EDITOR (модальное, владелец: main)
 *
 * - Контекст: mode=edit; ruleId=r3
 * - Границы: нет
 * - title: Аренда
 * - kind: EXPENSE
 * - amount: 45000,00
 * </pre>
 *
 * <p><b>Грамматика и тексты.</b> Заголовок, названия разделов, ключи строк, «да/нет», «модальное/немодальное» и
 * «владелец» — грамматика файла: они берутся из нелокализуемого ресурса {@link FormatWords} ({@code session.md.*})
 * и не зависят от языка интерфейса, иначе снимок, записанный при одном языке, не прочитался бы при другом. Сообщения
 * об ошибках разбора — текст интерфейса из каталога {@link Texts} ({@code session.codec.md.*}); слова формата
 * подставляются в них аргументами.</p>
 *
 * <p><b>Экранирование значений.</b> Файл должен оставаться читаемым, но значения полей — это
 * произвольный ввод пользователя. Поэтому в значениях обратная косая черта пишется как {@code \\},
 * перевод строки — {@code \n}, CR — {@code \r}, табуляция — {@code \t}, пробел в начале или в конце
 * значения — {@code \s} (текстовые редакторы молча срезают концевые пробелы). В списках
 * «ключ=значение» дополнительно экранируются {@code ;} и {@code =}, в именах полей — {@code :},
 * в заголовке окна — пробелы, скобки и запятая. Обычный текст, включая кириллицу, кавычки,
 * {@code < > & |}, пишется как есть.</p>
 *
 * <p><b>Порядок строк окна фиксирован:</b> первые две строки — «Контекст» и «Границы», далее поля.
 * Поэтому поле с идентификатором «Контекст» не спутать со строкой контекста.</p>
 *
 * <p><b>Текст плана</b> при несохранённых изменениях либо выносится в отдельный файл (так делает
 * {@code MarkdownSessionStore}: строка «да (web-session.plan.md)»), либо встраивается в раздел
 * «## Несохранённый план» огороженным блоком кода. Ограждение длиннее самой длинной серии обратных
 * апострофов в тексте, так что любой Markdown-текст плана переносится без изменений (CRLF
 * нормализуется в LF, как и во всём файле).</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class MarkdownSnapshotCodec implements SnapshotCodec<String> {

    /** Начало первой строки файла до имени клиента, вместе с «# ». */
    private static final String TITLE_PREFIX = FormatWords.get("session.md.title.prefix");
    /** Заголовок раздела главного окна, вместе с «## ». */
    private static final String SECTION_MAIN = FormatWords.get("session.md.section.main");
    /** Заголовок раздела открытых окон, вместе с «## ». */
    private static final String SECTION_WINDOWS = FormatWords.get("session.md.section.windows");
    /** Заголовок раздела встроенного текста плана, вместе с «## ». */
    private static final String SECTION_PLAN = FormatWords.get("session.md.section.plan");

    private static final String KEY_STATE = FormatWords.get("session.md.key.state");
    private static final String KEY_PID = FormatWords.get("session.md.key.pid");
    private static final String KEY_STARTED = FormatWords.get("session.md.key.started");
    private static final String KEY_SAVED = FormatWords.get("session.md.key.saved");
    private static final String KEY_SCHEMA = FormatWords.get("session.md.key.schema");
    private static final String KEY_VIEW = FormatWords.get("session.md.key.view");
    private static final String KEY_PLAN = FormatWords.get("session.md.key.plan");
    private static final String KEY_DIRTY = FormatWords.get("session.md.key.dirty");
    private static final String KEY_PERIOD = FormatWords.get("session.md.key.period");
    private static final String KEY_FILTERS = FormatWords.get("session.md.key.filters");
    private static final String KEY_FILTER_TEXT = FormatWords.get("session.md.key.filterText");
    private static final String KEY_SELECTED = FormatWords.get("session.md.key.selected");
    private static final String KEY_BOUNDS = FormatWords.get("session.md.key.bounds");
    private static final String KEY_MAXIMIZED = FormatWords.get("session.md.key.maximized");
    /** Необязательная строка главного окна: дополнительная экономия «что-если» в месяц. */
    private static final String KEY_WHAT_IF_EXTRA = FormatWords.get("session.md.key.whatIfExtra");
    private static final String KEY_CONTEXT = FormatWords.get("session.md.key.context");

    private static final String YES = FormatWords.get("session.md.yes");
    private static final String NO = FormatWords.get("session.md.no");
    private static final String MODAL = FormatWords.get("session.md.modal");
    private static final String MODELESS = FormatWords.get("session.md.modeless");
    /** Слово перед владельцем в заголовке окна; «, » и «: » вокруг него добавляет код. */
    private static final String OWNER = FormatWords.get("session.md.owner");
    /** Как пишется неизвестный тип окна. */
    private static final String UNKNOWN_TYPE = "?";

    /** Заголовок окна: {@code ### w1 - RULE_EDITOR (модальное, владелец: main)}; id и владелец без сырых пробелов. */
    private static final Pattern WINDOW_HEADING = Pattern.compile("^### (\\S+) - (\\S+) \\(("
            + Pattern.quote(MODAL) + "|" + Pattern.quote(MODELESS) + "), " + Pattern.quote(OWNER) + ": (\\S+)\\)$");

    /** Создаёт кодек (состояния нет, экземпляры взаимозаменяемы). */
    public MarkdownSnapshotCodec() {
    }

    @Override
    public String formatName() {
        return "Markdown";
    }

    @Override
    public String encode(SessionSnapshot snapshot) {
        return encodeDocument(new SessionDocument(snapshot.client(), null, snapshot), null);
    }

    @Override
    public SessionSnapshot decode(String encoded) throws SnapshotFormatException {
        SessionSnapshot snapshot = decodeDocument(encoded).snapshot();
        if (snapshot == null) {
            throw new SnapshotFormatException(Texts.get("session.codec.md.noSnapshot", sectionName(SECTION_MAIN)));
        }
        return snapshot;
    }

    /**
     * Записывает файл сессии.
     *
     * @param document         маркер и снимок (каждый может отсутствовать)
     * @param externalPlanFile имя отдельного файла с текстом несохранённого плана или {@code null},
     *                         чтобы встроить текст плана в раздел «## Несохранённый план»
     * @return Markdown-текст с переводами строк LF и одним завершающим переводом строки
     */
    public String encodeDocument(SessionDocument document, String externalPlanFile) {
        SessionMarker marker = document.marker();
        SessionSnapshot snapshot = document.snapshot();
        List<String> lines = new ArrayList<>();
        lines.add(TITLE_PREFIX + document.client() + ")");
        lines.add("");
        if (marker != null) {
            lines.add(item(KEY_STATE, marker.state()));
            lines.add(item(KEY_PID, String.valueOf(marker.pid())));
            lines.add(item(KEY_STARTED, marker.startedAt().toString()));
        }
        if (snapshot != null) {
            lines.add(item(KEY_SAVED, snapshot.savedAt().toString()));
        }
        lines.add(item(KEY_SCHEMA, String.valueOf(snapshot != null ? snapshot.schemaVersion() : SnapshotSchema.CURRENT)));
        if (snapshot == null) {
            return join(lines);
        }
        MainWindowState main = snapshot.main();
        PlanState plan = snapshot.plan();
        boolean embedPlan = plan.dirty() && externalPlanFile == null;
        lines.add("");
        lines.add(SECTION_MAIN);
        lines.add("");
        lines.add(item(KEY_VIEW, escapeValue(main.view())));
        lines.add(item(KEY_PLAN, escapeValue(main.planPath())));
        lines.add(item(KEY_DIRTY, !plan.dirty() ? NO
                : externalPlanFile != null ? YES + " (" + escapeValue(externalPlanFile) + ")" : YES));
        lines.add(item(KEY_PERIOD, escapeValue(main.period())));
        Map<String, String> filters = new LinkedHashMap<>();
        main.filters().forEach((key, value) -> filters.put(key, String.valueOf(value)));
        lines.add(item(KEY_FILTERS, pairs(filters)));
        lines.add(item(KEY_FILTER_TEXT, escapeValue(main.filterText())));
        lines.add(item(KEY_SELECTED, escapeValue(main.selectedRowId())));
        lines.add(item(KEY_BOUNDS, bounds(main.bounds())));
        lines.add(item(KEY_MAXIMIZED, main.maximized() ? YES : NO));
        // Необязательная строка схемы 1: только при значении, чтобы файлы без «что-если» не менялись.
        if (!main.whatIfExtra().isEmpty()) {
            lines.add(item(KEY_WHAT_IF_EXTRA, escapeValue(main.whatIfExtra())));
        }
        lines.add("");
        lines.add(SECTION_WINDOWS);
        for (WindowState window : snapshot.windows()) {
            lines.add("");
            lines.add("### " + escapeToken(window.id()) + " - "
                    + (window.type() == null ? UNKNOWN_TYPE : window.type().name())
                    + " (" + (window.modal() ? MODAL : MODELESS) + ", " + OWNER + ": " + escapeToken(window.ownerId()) + ")");
            lines.add("");
            lines.add(item(KEY_CONTEXT, pairs(window.context())));
            lines.add(item(KEY_BOUNDS, bounds(window.bounds())));
            window.fields().forEach((id, value) -> lines.add(item(escapeKey(id), escapeValue(value))));
        }
        if (embedPlan) {
            lines.add("");
            lines.add(SECTION_PLAN);
            lines.add("");
            String fence = "`".repeat(Math.max(3, longestBacktickRun(plan.markdown()) + 1));
            lines.add(fence);
            // split с limit -1 сохраняет пустые хвосты: текст «a\n» даёт строки «a» и «», и склейка вернёт «a\n».
            for (String line : plan.markdown().split("\n", -1)) {
                lines.add(line);
            }
            lines.add(fence);
        }
        return join(lines);
    }

    /**
     * Разбирает файл сессии. Если текст плана вынесен в отдельный файл, {@link PlanState#markdown()}
     * будет пустым — его подставляет хранилище.
     *
     * @param text Markdown-текст
     * @return маркер и снимок (каждый может отсутствовать)
     * @throws SnapshotFormatException если структура или значения некорректны
     */
    public SessionDocument decodeDocument(String text) throws SnapshotFormatException {
        if (text == null || text.isBlank()) {
            throw new SnapshotFormatException(Texts.get("session.codec.md.empty"));
        }
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        try {
            return new Parser(lines).parse();
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new SnapshotFormatException(Texts.get("session.codec.md.corrupted", e.getMessage()), e);
        }
    }

    /**
     * Проверяет, ссылается ли файл сессии на отдельный файл несохранённого плана, и возвращает его имя.
     *
     * @param text Markdown-текст файла сессии
     * @return имя файла или {@code null}, если ссылки нет
     */
    public static String externalPlanFile(String text) {
        String prefix = "- " + KEY_DIRTY + ": " + YES + " (";
        for (String line : text.replace("\r\n", "\n").split("\n")) {
            if (line.startsWith(prefix) && line.endsWith(")")) {
                return unescape(line.substring(prefix.length(), line.length() - 1));
            }
        }
        return null;
    }

    /**
     * Название раздела без «## » — так раздел называется в сообщениях.
     *
     * @param section заголовок раздела из {@link FormatWords}
     * @return например «Главное окно»
     */
    private static String sectionName(String section) {
        return section.substring(3);
    }

    /** @return образец первой строки для сообщений: «# Сессия CashPrediction (клиент)» */
    private static String titleSample() {
        return TITLE_PREFIX + Texts.get("session.codec.md.clientPlaceholder") + ")";
    }

    // ---------------------------------------------------------------- разбор

    /** Однопроходный разборщик строк файла; живёт в пределах одного вызова. */
    private static final class Parser {
        private final String[] lines;
        private String client;
        private final Map<String, String> header = new LinkedHashMap<>();
        private final Map<String, String> main = new LinkedHashMap<>();
        private boolean mainSeen;
        private final List<WindowDraft> windows = new ArrayList<>();
        private String embeddedPlan;

        Parser(String[] lines) {
            this.lines = lines;
        }

        SessionDocument parse() throws SnapshotFormatException {
            String section = "header";
            WindowDraft window = null;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                int lineNo = i + 1;
                if (line.startsWith("# ")) {
                    if (!line.startsWith(TITLE_PREFIX) || !line.endsWith(")")) {
                        throw error(lineNo, Texts.get("session.codec.md.expectedTitle", titleSample()));
                    }
                    client = line.substring(TITLE_PREFIX.length(), line.length() - 1);
                    section = "header";
                } else if (line.startsWith("## ")) {
                    window = null;
                    if (line.equals(SECTION_MAIN)) {
                        section = "main";
                        mainSeen = true;
                    } else if (line.equals(SECTION_WINDOWS)) {
                        section = "windows";
                    } else if (line.equals(SECTION_PLAN)) {
                        i = readFence(i + 1);
                        section = "ignored";
                    } else {
                        // Неизвестный раздел (например, из будущей версии) пропускаем целиком.
                        section = "ignored";
                    }
                } else if (line.startsWith("### ") && section.equals("windows")) {
                    window = parseHeading(line, lineNo);
                    windows.add(window);
                } else if (line.startsWith("- ")) {
                    int colon = indexOfUnescaped(line, ':', 2);
                    if (colon < 0) {
                        throw error(lineNo, Texts.get("session.codec.md.noColon"));
                    }
                    String key = unescape(line.substring(2, colon));
                    String raw = line.substring(colon + 1);
                    // Значение отделено одним пробелом; пустое значение редакторы часто сохраняют без него.
                    String value = raw.startsWith(" ") ? raw.substring(1) : raw;
                    switch (section) {
                        case "header" -> header.put(key, value);
                        case "main" -> main.put(key, value);
                        case "windows" -> {
                            if (window == null) {
                                throw error(lineNo, Texts.get("session.codec.md.itemBeforeWindow"));
                            }
                            window.add(key, value, lineNo);
                        }
                        default -> {
                            // Строки неизвестных разделов не разбираются.
                        }
                    }
                }
                // Пустые строки и произвольный текст между элементами игнорируются.
            }
            if (client == null) {
                throw new SnapshotFormatException(Texts.get("session.codec.md.noTitle", titleSample()));
            }
            int schema = header.containsKey(KEY_SCHEMA) ? CodecText.parseSchema(header.get(KEY_SCHEMA)) : SnapshotSchema.CURRENT;
            SessionMarker marker = null;
            if (header.containsKey(KEY_STATE)) {
                marker = new SessionMarker(header.get(KEY_STATE).strip(),
                        CodecText.parseLong(requireKey(header, KEY_PID), KEY_PID),
                        CodecText.parseInstant(requireKey(header, KEY_STARTED), KEY_STARTED), client);
            }
            SessionSnapshot snapshot = null;
            if (mainSeen) {
                Instant savedAt = CodecText.parseInstant(requireKey(header, KEY_SAVED), KEY_SAVED);
                List<WindowState> states = new ArrayList<>();
                for (WindowDraft draft : windows) {
                    states.add(draft.toState());
                }
                snapshot = new SessionSnapshot(schema, savedAt, client, mainState(), planState(), states);
            }
            return new SessionDocument(client, marker, snapshot);
        }

        private MainWindowState mainState() throws SnapshotFormatException {
            Map<String, Boolean> filters = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : parsePairs(main.getOrDefault(KEY_FILTERS, "")).entrySet()) {
                filters.put(entry.getKey(), CodecText.parseBoolean(entry.getValue(), KEY_FILTERS));
            }
            return new MainWindowState(parseBounds(main.getOrDefault(KEY_BOUNDS, NO)),
                    parseYesNo(main.getOrDefault(KEY_MAXIMIZED, NO), KEY_MAXIMIZED),
                    unescape(main.getOrDefault(KEY_VIEW, "")),
                    unescape(main.getOrDefault(KEY_PLAN, "")),
                    unescape(main.getOrDefault(KEY_PERIOD, "")),
                    filters,
                    unescape(main.getOrDefault(KEY_FILTER_TEXT, "")),
                    unescape(main.getOrDefault(KEY_SELECTED, "")),
                    unescape(main.getOrDefault(KEY_WHAT_IF_EXTRA, "")));
        }

        private PlanState planState() throws SnapshotFormatException {
            String dirty = main.getOrDefault(KEY_DIRTY, NO).strip();
            if (dirty.equals(NO)) {
                return PlanState.CLEAN;
            }
            if (!dirty.equals(YES) && !dirty.startsWith(YES + " (")) {
                throw notYesNo(KEY_DIRTY);
            }
            return PlanState.dirty(embeddedPlan == null ? "" : embeddedPlan);
        }

        /** Читает огороженный блок, начиная со строки {@code from}; возвращает индекс закрывающей строки. */
        private int readFence(int from) throws SnapshotFormatException {
            int i = from;
            while (i < lines.length && lines[i].isEmpty()) {
                i++;
            }
            if (i >= lines.length || !lines[i].startsWith("```")) {
                throw error(i + 1, Texts.get("session.codec.md.expectedCodeBlock", sectionName(SECTION_PLAN)));
            }
            String fence = lines[i];
            List<String> content = new ArrayList<>();
            for (int j = i + 1; j < lines.length; j++) {
                if (lines[j].equals(fence)) {
                    embeddedPlan = String.join("\n", content);
                    return j;
                }
                content.add(lines[j]);
            }
            throw error(i + 1, Texts.get("session.codec.md.codeBlockNotClosed"));
        }

        private WindowDraft parseHeading(String line, int lineNo) throws SnapshotFormatException {
            Matcher m = WINDOW_HEADING.matcher(line);
            if (!m.matches()) {
                throw error(lineNo, Texts.get("session.codec.md.badWindowHeading"));
            }
            WindowType type = m.group(2).equals(UNKNOWN_TYPE) ? null : WindowType.fromName(m.group(2)).orElse(null);
            return new WindowDraft(unescape(m.group(1)), type, m.group(3).equals(MODAL), unescape(m.group(4)));
        }

        private SnapshotFormatException error(int lineNo, String detail) {
            return errorAtLine(lineNo, detail);
        }
    }

    /** Окно, собираемое из заголовка и строк списка. */
    private static final class WindowDraft {
        private final String id;
        private final WindowType type;
        private final boolean modal;
        private final String owner;
        private String context;
        private String bounds;
        private final Map<String, String> fields = new LinkedHashMap<>();

        WindowDraft(String id, WindowType type, boolean modal, String owner) {
            this.id = id;
            this.type = type;
            this.modal = modal;
            this.owner = owner;
        }

        void add(String key, String value, int lineNo) throws SnapshotFormatException {
            // Строки контекста и границ распознаются только до первого поля (порядок фиксирован записью).
            if (fields.isEmpty() && context == null && key.equals(KEY_CONTEXT)) {
                context = value;
            } else if (fields.isEmpty() && bounds == null && key.equals(KEY_BOUNDS)) {
                bounds = value;
            } else {
                if (fields.containsKey(key)) {
                    throw errorAtLine(lineNo, Texts.get("session.codec.md.repeatedField", key, id));
                }
                fields.put(key, unescape(value));
            }
        }

        WindowState toState() throws SnapshotFormatException {
            return new WindowState(id, type, modal, owner, parseBounds(bounds == null ? NO : bounds),
                    parsePairs(context == null ? "" : context), fields);
        }
    }

    // ---------------------------------------------------------------- текстовые помощники

    /**
     * Ошибка разбора с номером строки файла.
     *
     * @param lineNo номер строки (с 1)
     * @param detail причина на языке интерфейса
     * @return исключение с готовым сообщением
     */
    private static SnapshotFormatException errorAtLine(int lineNo, String detail) {
        return new SnapshotFormatException(Texts.get("session.codec.md.corruptedAtLine", lineNo, detail));
    }

    /**
     * Ошибка «значение не да/нет»; сами слова берутся из грамматики файла.
     *
     * @param key ключ строки
     * @return исключение с готовым сообщением
     */
    private static SnapshotFormatException notYesNo(String key) {
        return new SnapshotFormatException(Texts.get("session.codec.md.yesNo", key, YES, NO));
    }

    private static String item(String key, String value) {
        // Для пустого значения не оставляем концевой пробел: редакторы его всё равно срежут.
        return value.isEmpty() ? "- " + key + ":" : "- " + key + ": " + value;
    }

    private static String join(List<String> lines) {
        return String.join("\n", lines) + "\n";
    }

    private static String bounds(WindowBounds bounds) {
        if (bounds == null) {
            return NO;
        }
        return "x=" + CodecText.formatNumber(bounds.x()) + "; y=" + CodecText.formatNumber(bounds.y())
                + "; width=" + CodecText.formatNumber(bounds.width()) + "; height=" + CodecText.formatNumber(bounds.height());
    }

    private static WindowBounds parseBounds(String text) throws SnapshotFormatException {
        String value = text.strip();
        if (value.equals(NO) || value.isEmpty()) {
            return null;
        }
        Map<String, String> map = parsePairs(value);
        for (String key : List.of("x", "y", "width", "height")) {
            if (!map.containsKey(key)) {
                throw new SnapshotFormatException(Texts.get("session.codec.md.missingPart", KEY_BOUNDS, key));
            }
        }
        return new WindowBounds(CodecText.parseNumber(map.get("x"), "x"), CodecText.parseNumber(map.get("y"), "y"),
                CodecText.parseNumber(map.get("width"), "width"), CodecText.parseNumber(map.get("height"), "height"));
    }

    private static boolean parseYesNo(String text, String what) throws SnapshotFormatException {
        // Слова «да/нет» больше не константы времени компиляции (они из FormatWords), поэтому не switch.
        String value = text.strip();
        if (value.equals(YES)) {
            return true;
        }
        if (value.equals(NO)) {
            return false;
        }
        throw notYesNo(what);
    }

    private static String requireKey(Map<String, String> map, String key) throws SnapshotFormatException {
        String value = map.get(key);
        if (value == null) {
            throw new SnapshotFormatException(Texts.get("session.codec.md.missingLine", key));
        }
        return value;
    }

    /** Список «k=v; k2=v2» с экранированными ключами и значениями. */
    private static String pairs(Map<String, String> map) {
        List<String> parts = new ArrayList<>();
        map.forEach((key, value) -> parts.add(escapePairPart(key) + "=" + escapePairPart(value)));
        return String.join("; ", parts);
    }

    private static Map<String, String> parsePairs(String text) throws SnapshotFormatException {
        Map<String, String> result = new LinkedHashMap<>();
        for (String entry : splitUnescaped(text, ';')) {
            // Сырые пробелы по краям — только от разделителя «; »: краевые пробелы значений экранированы как \s.
            String trimmed = entry.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = indexOfUnescaped(trimmed, '=', 0);
            if (eq < 0) {
                throw new SnapshotFormatException(Texts.get("session.codec.md.missingPart", trimmed, "="));
            }
            result.put(unescape(trimmed.substring(0, eq)), unescape(trimmed.substring(eq + 1)));
        }
        return result;
    }

    /**
     * Экранирует значение строки списка.
     *
     * @param value исходное значение
     * @return значение без переводов строк и краевых пробелов
     */
    static String escapeValue(String value) {
        return escape(value, "");
    }

    private static String escapeKey(String key) {
        return escape(key, ":");
    }

    private static String escapePairPart(String part) {
        return escape(part, ";=");
    }

    /** Идентификатор в заголовке окна: без сырых пробелов, скобок и запятых. */
    private static String escapeToken(String token) {
        // escape() экранирует только краевые пробелы; внутренние тоже заменяем, чтобы заголовок делился по пробелам.
        return escape(token, "(),").replace(" ", "\\s");
    }

    private static String escape(String value, String extraSpecials) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean edge = i == 0 || i == value.length() - 1;
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case ' ' -> sb.append(edge ? "\\s" : " ");
                default -> {
                    if (extraSpecials.indexOf(c) >= 0) {
                        sb.append('\\');
                    }
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    /**
     * Снимает экранирование, выполненное при записи.
     *
     * @param value экранированный текст
     * @return исходный текст
     */
    static String unescape(String value) {
        if (value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                sb.append(c);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 's' -> sb.append(' ');
                default -> sb.append(next);
            }
        }
        return sb.toString();
    }

    private static int indexOfUnescaped(String text, char target, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == target) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> splitUnescaped(String text, char separator) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == separator) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(Math.min(start, text.length())));
        return parts;
    }

    private static int longestBacktickRun(String text) {
        int longest = 0;
        int current = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '`') {
                current++;
                longest = Math.max(longest, current);
            } else {
                current = 0;
            }
        }
        return longest;
    }
}
