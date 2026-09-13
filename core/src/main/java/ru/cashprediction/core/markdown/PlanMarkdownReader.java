package ru.cashprediction.core.markdown;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.cashprediction.core.diagnostics.Diagnostic;
import ru.cashprediction.core.format.FormatWords;
import ru.cashprediction.core.io.AtomicFiles;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Goal;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.OneTimeTransaction;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.RawBlock;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.TxId;
import ru.cashprediction.core.model.WeekendPolicy;
import ru.cashprediction.core.text.Texts;

/**
 * Читатель файла плана {@code CashMemory/<имя>.md}.
 *
 * <p>Файл мог быть отредактирован вручную, поэтому читатель терпим ко всему, что не мешает понять смысл,
 * и ничего не теряет молча:</p>
 * <ul>
 *   <li>переводы строк CRLF и BOM допускаются;</li>
 *   <li>секции {@code ## Название} ищутся без учёта регистра, двоеточие в конце заголовка игнорируется;
 *       повторная известная секция объединяется с первой, её таблица может продолжаться без своей строки
 *       заголовка (WARNING); известная секция с другим числом «#» ({@code ### Корректировки}) распознаётся (WARNING);</li>
 *   <li>заголовок {@code # План:} распознаётся и после неизвестных секций, если известных ещё не было (WARNING);</li>
 *   <li>строка-разделитель {@code |---|}, скопированная в середину таблицы, пропускается: строка данных над ней
 *       не принимается за новый заголовок; полностью пустая строка таблицы пропускается без сообщений;</li>
 *   <li>неизвестная секция и любой нераспознанный текст сохраняются в {@link RawBlock} и при записи
 *       возвращаются на прежнее место (WARNING); неизвестный параметр остаётся внутри списка «Параметры»;</li>
 *   <li>отсутствующие обязательные параметры получают значения по умолчанию: ₽, сегодняшняя дата,
 *       12 месяцев, 0 (WARNING);</li>
 *   <li>колонки таблиц ищутся по НАЗВАНИЮ: порядок любой, лишние колонки игнорируются (WARNING),
 *       необязательные колонки могут отсутствовать;</li>
 *   <li>строка таблицы или параметр, который не удалось разобрать, исключается из плана и дописывается
 *       в заметку с пометкой {@code (не разобрано, строка N: ...)} (ERROR с номером строки) — так
 *       пользователь его увидит и исправит, а при сохранении он не пропадёт;</li>
 *   <li>сумма со знаком берётся по модулю (WARNING); повторяющийся или некорректный ID заменяется новым
 *       (WARNING), отсутствующий ID назначается (INFO);</li>
 *   <li>корректировка для несуществующего правила сохраняется: движок прогноза сообщит о «сироте».</li>
 * </ul>
 *
 * <p>Исключение {@link MarkdownParseException} бросается, только если в тексте нет ни заголовка
 * {@code # План:}, ни одной известной секции, то есть это вовсе не план.</p>
 *
 * <p>Класс без состояния, потокобезопасен: каждое чтение использует собственный внутренний разборщик.</p>
 */
public final class PlanMarkdownReader {

    /**
     * Заголовок плана: {@code # План: имя}, без учёта регистра и пробелов. Слово заголовка — из грамматики формата
     * ({@link FormatWords}); в шаблон оно попадает экранированным.
     */
    private static final Pattern TITLE = Pattern.compile(
            "#(?!#)\\s*" + Pattern.quote(MarkdownFormat.TITLE_WORD) + "\\s*:\\s*(.*?)\\s*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Заголовок секции второго уровня: {@code ## Название} с необязательным двоеточием. */
    private static final Pattern HEADING = Pattern.compile("##(?!#)\\s*(.*?)\\s*:?\\s*");

    /**
     * Заголовок любого уровня от 1 до 6. Используется только для известных секций, записанных с лишним или
     * недостающим «#» ({@code ### Регулярные операции}): иначе вся таблица молча ушла бы в текст соседней секции.
     */
    private static final Pattern ANY_LEVEL_HEADING = Pattern.compile("(#{1,6})(?!#)\\s*(.*?)\\s*:?\\s*");

    /** Значение параметра «Формат»: {@code CashPrediction N}. */
    private static final Pattern FORMAT_VALUE = Pattern.compile("cashprediction\\s*(\\d{1,9})");

    private PlanMarkdownReader() {
    }

    /**
     * Читает план из текста.
     *
     * @param text         содержимое файла
     * @param fallbackName имя плана, если в тексте нет заголовка {@code # План:} (обычно имя файла)
     * @param today        сегодняшняя дата: подставляется, если в файле нет даты начала
     * @return план и диагностика
     * @throws MarkdownParseException если текст не является планом (нет заголовка и известных секций)
     */
    public static ReadResult read(String text, String fallbackName, LocalDate today) {
        Objects.requireNonNull(today, "today");
        return new Parser(text == null ? "" : text, fallbackName, today).parse();
    }

    /**
     * Читает план из файла в UTF-8 (BOM допускается). Если в файле нет заголовка, имя плана берётся
     * из имени файла без расширения {@code .md}.
     *
     * @param file  файл плана
     * @param today сегодняшняя дата
     * @return план и диагностика
     * @throws IOException            если файл не читается
     * @throws MarkdownParseException если файл не является планом
     */
    public static ReadResult read(Path file, LocalDate today) throws IOException {
        String text = AtomicFiles.readString(file);
        return read(text, nameWithoutExtension(file), today);
    }

    /**
     * Распознаёт строку заголовка плана.
     *
     * @param line строка файла
     * @return имя из заголовка (возможно пустое) или пустое значение, если строка не является заголовком плана
     */
    public static Optional<String> titleName(String line) {
        Matcher m = TITLE.matcher(line == null ? "" : line.strip());
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /**
     * Проверяет, является ли строка заголовком секции второго уровня ({@code ## ...}).
     *
     * @param line строка файла
     * @return {@code true} для заголовка секции
     */
    public static boolean isSectionHeading(String line) {
        return line != null && HEADING.matcher(line.strip()).matches();
    }

    /**
     * Имя файла без расширения {@code .md} (регистр расширения не важен).
     *
     * @param file путь к файлу
     * @return например «Семейный бюджет 2026» для {@code Семейный бюджет 2026.md}
     */
    public static String nameWithoutExtension(Path file) {
        Path fileName = file.getFileName();
        String name = fileName == null ? "" : fileName.toString();
        return name.length() > 3 && name.regionMatches(true, name.length() - 3, ".md", 0, 3)
                ? name.substring(0, name.length() - 3)
                : name;
    }

    // ==================================================================================================

    /** Известные секции файла плана и специальные состояния разборщика. */
    private enum Section {
        /** До первой секции: заголовок плана и текст под ним. */
        PREAMBLE(""),
        /** «Параметры». */
        PARAMETERS(MarkdownFormat.SECTION_PARAMETERS),
        /** «Заметка». */
        NOTE(MarkdownFormat.SECTION_NOTE),
        /** «Регулярные операции». */
        RULES(MarkdownFormat.SECTION_RULES),
        /** «Разовые операции». */
        ONE_TIME(MarkdownFormat.SECTION_ONE_TIME),
        /** «Корректировки». */
        ADJUSTMENTS(MarkdownFormat.SECTION_ADJUSTMENTS),
        /** Секция с неизвестным названием: сохраняется как есть. */
        UNKNOWN("");

        private final String title;

        Section(String title) {
            this.title = title;
        }

        static Section byTitle(String title) {
            String t = RuFormats.normalize(title);
            for (Section s : values()) {
                if (!s.title.isEmpty() && RuFormats.normalize(s.title).equals(t)) {
                    return s;
                }
            }
            return null;
        }
    }

    /**
     * Значение параметра, запомненное до конца разбора: смысл некоторых параметров зависит от других
     * (например, «Цель к дате» без «Цель» не используется).
     *
     * @param key          ключ, как он записан в файле
     * @param value        значение
     * @param line         номер строки
     * @param originalLine исходный текст строки
     */
    private record Param(String key, String value, int line, String originalLine) {
    }

    /**
     * Регулярная или разовая операция, у которой ID ещё не проверен на уникальность.
     *
     * @param rawId исходный текст ячейки ID
     * @param line  номер строки
     * @param title название операции (для сообщений)
     * @param value операция с временным ID
     * @param <T>   тип операции
     */
    private record Pending<T>(String rawId, int line, String title, T value) {
    }

    /**
     * Пометка о неразобранной строке, которая попадёт в заметку.
     *
     * @param line номер строки
     * @param text текст пометки
     */
    private record Mark(int line, String text) {
    }

    /**
     * Соответствие колонок таблицы их позициям, найденное по строке заголовка.
     *
     * @param positions нормализованное название колонки → индекс ячейки
     */
    private record Header(Map<String, Integer> positions) {

        static Header of(List<String> cells) {
            Map<String, Integer> map = new HashMap<>();
            for (int i = 0; i < cells.size(); i++) {
                // При повторе названия действует первая колонка: так же поступил бы человек, читая слева направо.
                map.putIfAbsent(RuFormats.normalize(cells.get(i)), i);
            }
            return new Header(map);
        }

        boolean has(String column) {
            return positions.containsKey(RuFormats.normalize(column));
        }

        String cell(List<String> cells, String column) {
            Integer index = positions.get(RuFormats.normalize(column));
            return index == null || index >= cells.size() ? "" : cells.get(index);
        }
    }

    /**
     * Однопроходный разборщик одного текста. Изменяемый, используется только внутри одного вызова
     * {@link PlanMarkdownReader#read(String, String, LocalDate)}.
     */
    private static final class Parser {

        /** Временный ID операции до назначения окончательного: допустим для RuleId и TxId. */
        private static final String TEMP_ID = "?";

        private final String[] lines;
        private final String fallbackName;
        private final LocalDate today;
        private final List<Diagnostic> diagnostics = new ArrayList<>();

        private String name;
        private int titleLine;
        private boolean titleSeen;
        private boolean knownSectionSeen;
        private Section section = Section.PREAMBLE;
        private final Set<Section> seenSections = EnumSet.noneOf(Section.class);
        /** Название последней известной секции: к ней привязываются нераспознанные фрагменты. */
        private String anchor = "";

        private final List<RawBlock> rawBlocks = new ArrayList<>();
        private List<String> rawLines;
        private String rawAnchor;

        private final Map<String, Param> params = new LinkedHashMap<>();
        private final List<String> parameterExtras = new ArrayList<>();

        private final List<List<String>> noteParts = new ArrayList<>();
        private List<String> currentNote;
        private final List<Mark> marks = new ArrayList<>();

        private Header header;
        /** Последний заголовок таблицы каждой секции: повторная секция продолжает таблицу первой. */
        private final Map<Section, Header> lastHeaders = new EnumMap<>(Section.class);
        /** Была ли в текущем вхождении секции хотя бы одна строка таблицы (после пустых и разделителей). */
        private boolean tableRowSeen;
        private final List<Pending<RecurringRule>> rules = new ArrayList<>();
        private final List<Pending<OneTimeTransaction>> oneTimes = new ArrayList<>();
        private final List<Adjustment> adjustments = new ArrayList<>();

        Parser(String text, String fallbackName, LocalDate today) {
            // BOM добавляет Блокнот; CR/CRLF — любой редактор Windows.
            String t = text.startsWith("﻿") ? text.substring(1) : text;
            this.lines = t.split("\\r\\n|\\r|\\n", -1);
            // Имя плана, если его не удалось взять ни из заголовка, ни из имени файла: текст интерфейса, а не формата.
            // Только значение по умолчанию: по нему нельзя узнавать планы, при другом языке интерфейса оно другое.
            this.fallbackName = fallbackName == null || fallbackName.isBlank()
                    ? Texts.get("markdown.read.defaultPlanName") : fallbackName.strip();
            this.today = today;
        }

        ReadResult parse() {
            for (int i = 0; i < lines.length; i++) {
                int lineNo = i + 1;
                String line = lines[i].stripTrailing();
                String t = line.strip();
                Matcher heading = HEADING.matcher(t);
                if (heading.matches()) {
                    startSection(heading.group(1), line, lineNo);
                    continue;
                }
                Matcher otherLevel = ANY_LEVEL_HEADING.matcher(t);
                if (otherLevel.matches() && Section.byTitle(otherLevel.group(2)) != null) {
                    Section known = Section.byTitle(otherLevel.group(2));
                    diagnostics.add(Diagnostic.warning(lineNo,
                            Texts.get("markdown.read.headingLevel", known.title, otherLevel.group(1))));
                    startSection(otherLevel.group(2), line, lineNo);
                    continue;
                }
                // Заголовок плана распознаётся, пока не встретилась ни одна известная секция: ниже «# План:» — это уже
                // текст секции. Неизвестные секции над заголовком (например, «## Черновик» в начале файла) не мешают:
                // иначе имя плана заменилось бы именем файла, а при каждом сохранении в файле было бы два заголовка.
                if (!titleSeen && !knownSectionSeen) {
                    Matcher title = TITLE.matcher(t);
                    if (title.matches()) {
                        if (section == Section.PREAMBLE) {
                            flushRaw();
                        } else {
                            // Внутри неизвестной секции сброс фрагмента оборвал бы её текст; строку заголовка просто не копируем.
                            diagnostics.add(Diagnostic.warning(lineNo, Texts.get("markdown.read.titleAfterSection")));
                        }
                        titleSeen = true;
                        titleLine = lineNo;
                        name = title.group(1);
                        continue;
                    }
                }
                switch (section) {
                    case PREAMBLE -> looseLine(line, t, lineNo, "", Texts.get("markdown.read.textOutsideSections"));
                    case UNKNOWN -> rawLines.add(line);
                    case PARAMETERS -> parameterLine(line, t, lineNo);
                    case NOTE -> currentNote.add(MarkdownFormat.unescapeNoteLine(line));
                    case RULES, ONE_TIME, ADJUSTMENTS -> tableLine(i, line, t, lineNo);
                }
            }
            flushRaw();
            if (!titleSeen && !knownSectionSeen) {
                throw new MarkdownParseException(Texts.get("markdown.read.notAPlan", MarkdownFormat.TITLE_PREFIX.strip()));
            }
            return new ReadResult(buildPlan(), sortedDiagnostics());
        }

        // -------------------------------------------------------------- структура

        private void startSection(String title, String line, int lineNo) {
            flushRaw();
            header = null;
            tableRowSeen = false;
            Section s = Section.byTitle(title);
            if (s == null) {
                section = Section.UNKNOWN;
                openRaw(anchor);
                rawLines.add(line);
                diagnostics.add(Diagnostic.warning(lineNo, Texts.get("markdown.read.unknownSection", title)));
                return;
            }
            knownSectionSeen = true;
            if (!seenSections.add(s)) {
                diagnostics.add(Diagnostic.warning(lineNo, Texts.get("markdown.read.repeatedSection", s.title)));
            }
            section = s;
            anchor = s.title;
            // Повторная табличная секция объединяется с первой: её строки можно писать без своего заголовка.
            header = lastHeaders.get(s);
            if (s == Section.NOTE) {
                currentNote = new ArrayList<>();
                noteParts.add(currentNote);
            }
        }

        /** Строка, не относящаяся к структуре секции: копится в нераспознанный фрагмент. */
        private void looseLine(String line, String t, int lineNo, String blockAnchor, String message) {
            if (t.isEmpty()) {
                // Пустые строки внутри фрагмента сохраняются, по краям фрагмента — отбрасываются при сбросе.
                if (rawLines != null) {
                    rawLines.add(line);
                }
                return;
            }
            if (rawLines == null) {
                openRaw(blockAnchor);
                diagnostics.add(Diagnostic.warning(lineNo, message));
            }
            rawLines.add(line);
        }

        private void openRaw(String blockAnchor) {
            rawLines = new ArrayList<>();
            rawAnchor = blockAnchor;
        }

        private void flushRaw() {
            if (rawLines != null) {
                List<String> trimmed = trimBlankLines(rawLines);
                if (!trimmed.isEmpty()) {
                    rawBlocks.add(new RawBlock(rawAnchor, trimmed));
                }
            }
            rawLines = null;
            rawAnchor = null;
        }

        // -------------------------------------------------------------- параметры

        private void parameterLine(String line, String t, int lineNo) {
            Optional<ListItem> item = t.isEmpty() ? Optional.empty() : ListItem.parse(line);
            if (item.isEmpty()) {
                looseLine(line, t, lineNo, MarkdownFormat.SECTION_PARAMETERS,
                        Texts.get("markdown.read.badParameterLine", MarkdownFormat.SECTION_PARAMETERS));
                return;
            }
            flushRaw();
            ListItem it = item.get();
            String key = RuFormats.normalize(it.key());
            if (knownParameter(key)) {
                Param previous = params.put(key, new Param(it.key(), it.value(), lineNo, line));
                if (previous != null) {
                    diagnostics.add(Diagnostic.warning(lineNo,
                            Texts.get("markdown.read.repeatedParameter", it.key(), previous.line())));
                }
            } else {
                parameterExtras.add(it.format());
                diagnostics.add(Diagnostic.warning(lineNo, Texts.get("markdown.read.unknownParameter", it.key())));
            }
        }

        private static boolean knownParameter(String normalizedKey) {
            for (String key : List.of(MarkdownFormat.KEY_FORMAT, MarkdownFormat.KEY_CURRENCY, MarkdownFormat.KEY_START,
                    MarkdownFormat.KEY_HORIZON, MarkdownFormat.KEY_START_BALANCE, MarkdownFormat.KEY_CUSHION,
                    MarkdownFormat.KEY_GOAL, MarkdownFormat.KEY_GOAL_DATE, MarkdownFormat.KEY_GOAL_TITLE)) {
                if (RuFormats.normalize(key).equals(normalizedKey)) {
                    return true;
                }
            }
            return false;
        }

        private Param param(String key) {
            return params.get(RuFormats.normalize(key));
        }

        /** Обязательный параметр: отсутствует → значение по умолчанию + WARNING; испорчен → в заметку + ERROR. */
        private <T> T required(String key, T defaultValue, String defaultText, Function<String, T> parser) {
            Param p = param(key);
            if (p == null || RuFormats.isEmptyValue(p.value())) {
                diagnostics.add(Diagnostic.warning(p == null ? 0 : p.line(),
                        Texts.get("markdown.read.missingParameter", key, defaultText)));
                return defaultValue;
            }
            try {
                return parser.apply(p.value());
            } catch (IllegalArgumentException e) {
                unparsed(p.line(), p.originalLine(), MarkdownFormat.SECTION_PARAMETERS,
                        Texts.get("markdown.read.usingDefault", e.getMessage(), defaultText));
                return defaultValue;
            }
        }

        /** Необязательный параметр: отсутствует → {@code null}; испорчен → в заметку + ERROR и {@code null}. */
        private <T> T optional(String key, Function<String, T> parser) {
            Param p = param(key);
            if (p == null || RuFormats.isEmptyValue(p.value())) {
                return null;
            }
            try {
                return parser.apply(p.value());
            } catch (IllegalArgumentException e) {
                unparsed(p.line(), p.originalLine(), MarkdownFormat.SECTION_PARAMETERS, e.getMessage());
                return null;
            }
        }

        private void checkFormat() {
            Param p = param(MarkdownFormat.KEY_FORMAT);
            if (p == null) {
                return;
            }
            Matcher m = FORMAT_VALUE.matcher(RuFormats.normalize(p.value()));
            if (!m.matches()) {
                diagnostics.add(Diagnostic.warning(p.line(), Texts.get("markdown.read.badFormatValue",
                        MarkdownFormat.KEY_FORMAT, p.value(), MarkdownFormat.formatValue())));
            } else if (Long.parseLong(m.group(1)) > MarkdownFormat.FORMAT_VERSION) {
                diagnostics.add(Diagnostic.warning(p.line(), Texts.get("markdown.read.newerFormat", m.group(1))));
            }
        }

        // -------------------------------------------------------------- таблицы

        private void tableLine(int index, String line, String t, int lineNo) {
            if (!MarkdownTable.isTableRow(t)) {
                looseLine(line, t, lineNo, anchor, Texts.get("markdown.read.notTableRow", anchor));
                return;
            }
            flushRaw();
            if (MarkdownTable.isSeparatorRow(t)) {
                return;
            }
            List<String> cells = MarkdownTable.parseRow(t);
            if (cells.stream().allMatch(RuFormats::isEmptyValue)) {
                // Пустая строка-заготовка из Блокнота данных не несёт; разобранная как операция, она навсегда
                // осела бы в заметке пометкой «не разобрано».
                return;
            }
            boolean firstRow = !tableRowSeen;
            tableRowSeen = true;
            boolean nextIsSeparator = index + 1 < lines.length && MarkdownTable.isSeparatorRow(lines[index + 1]);
            if (header == null) {
                Header candidate = Header.of(cells);
                // Первая строка без разделителя и без единой знакомой колонки — это данные без заголовка,
                // а не заголовок: иначе строка молча пропала бы.
                if (nextIsSeparator || knownColumns(candidate) > 0) {
                    adoptHeader(candidate, cells, lineNo);
                    return;
                }
                unparsed(lineNo, t, anchor, Texts.get("markdown.read.noTableHeader"));
                return;
            }
            // Заголовок уже есть (продолжение таблицы или повторная секция). Новым заголовком строка становится,
            // только если она на него похожа: разделитель, скопированный в середину таблицы, не должен превращать
            // строку данных над ним в заголовок — она пропала бы, а следующие строки ушли бы в заметку.
            if ((nextIsSeparator || firstRow) && looksLikeHeader(cells)) {
                adoptHeader(Header.of(cells), cells, lineNo);
                return;
            }
            List<Diagnostic> rowDiagnostics = new ArrayList<>();
            try {
                switch (section) {
                    case RULES -> rules.add(parseRule(cells, lineNo, rowDiagnostics));
                    case ONE_TIME -> oneTimes.add(parseOneTime(cells, lineNo, rowDiagnostics));
                    case ADJUSTMENTS -> adjustments.add(parseAdjustment(cells, lineNo, rowDiagnostics));
                    // Невозможная ветка: tableLine вызывается только для табличных секций; сообщение для разработчика.
                    default -> throw new IllegalStateException("Not a table section: " + section);
                }
                // Предупреждения строки фиксируются только при успехе: у перенесённой в заметку строки
                // достаточно одной ошибки.
                diagnostics.addAll(rowDiagnostics);
            } catch (IllegalArgumentException e) {
                unparsed(lineNo, t, anchor, e.getMessage());
            }
        }

        private List<String> columnsOfSection() {
            return switch (section) {
                case RULES -> MarkdownFormat.RULE_COLUMNS;
                case ONE_TIME -> MarkdownFormat.ONE_TIME_COLUMNS;
                case ADJUSTMENTS -> MarkdownFormat.ADJUSTMENT_COLUMNS;
                default -> List.of();
            };
        }

        private int knownColumns(Header candidate) {
            return (int) columnsOfSection().stream().filter(candidate::has).count();
        }

        private void adoptHeader(Header candidate, List<String> cells, int lineNo) {
            header = candidate;
            lastHeaders.put(section, candidate);
            warnUnusedColumns(cells, lineNo);
        }

        /**
         * Похожа ли строка на заголовок таблицы текущей секции: не меньше половины непустых ячеек — известные
         * названия колонок. Строка данных совпадает с названием колонки разве что одной ячейкой
         * (например, категория «Заметка»), поэтому одного совпадения мало.
         */
        private boolean looksLikeHeader(List<String> cells) {
            Set<String> known = new HashSet<>();
            columnsOfSection().forEach(c -> known.add(RuFormats.normalize(c)));
            int nonBlank = 0;
            int matches = 0;
            for (String cell : cells) {
                if (!cell.isBlank()) {
                    nonBlank++;
                    if (known.contains(RuFormats.normalize(cell))) {
                        matches++;
                    }
                }
            }
            return matches > 0 && matches * 2 >= nonBlank;
        }

        private void warnUnusedColumns(List<String> cells, int lineNo) {
            Set<String> known = new HashSet<>();
            columnsOfSection().forEach(c -> known.add(RuFormats.normalize(c)));
            for (String cell : cells) {
                if (!cell.isBlank() && !known.contains(RuFormats.normalize(cell))) {
                    diagnostics.add(Diagnostic.warning(lineNo, Texts.get("markdown.read.unusedColumn", cell, anchor)));
                }
            }
        }

        private void requireColumns(List<String> required) {
            for (String column : required) {
                if (!header.has(column)) {
                    throw new IllegalArgumentException(Texts.get("markdown.read.missingColumn", column));
                }
            }
        }

        /** Разбирает ячейку и дополняет сообщение об ошибке названием колонки. */
        private <T> T column(List<String> cells, String column, Function<String, T> parser) {
            String value = header.cell(cells, column);
            try {
                return parser.apply(value);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(Texts.get("markdown.read.columnError", column, e.getMessage()), e);
            }
        }

        private Money amount(List<String> cells, String column, int lineNo, List<Diagnostic> rowDiagnostics) {
            String text = header.cell(cells, column);
            Money money = column(cells, column, RuFormats::parseMoney);
            if (RuFormats.hasExplicitSign(text)) {
                money = money.abs();
                rowDiagnostics.add(Diagnostic.warning(lineNo,
                        Texts.get("markdown.read.signIgnored", text, MarkdownFormat.COL_KIND, money.format())));
            }
            if (Money.needsRounding(text)) {
                rowDiagnostics.add(Diagnostic.warning(lineNo, Texts.get("markdown.read.roundedToKopecks", text, money.format())));
            }
            return money;
        }

        private Pending<RecurringRule> parseRule(List<String> cells, int lineNo, List<Diagnostic> rowDiagnostics) {
            requireColumns(MarkdownFormat.RULE_REQUIRED_COLUMNS);
            String title = header.cell(cells, MarkdownFormat.COL_TITLE);
            Kind kind = column(cells, MarkdownFormat.COL_KIND, RuFormats::parseKind);
            Money amount = amount(cells, MarkdownFormat.COL_AMOUNT, lineNo, rowDiagnostics);
            Recurrence recurrence = column(cells, MarkdownFormat.COL_RECURRENCE, RuFormats::parseRecurrence);
            LocalDate from = column(cells, MarkdownFormat.COL_FROM, RuFormats::parseOptionalDate);
            LocalDate until = column(cells, MarkdownFormat.COL_UNTIL, RuFormats::parseOptionalDate);
            WeekendPolicy policy = column(cells, MarkdownFormat.COL_WEEKEND, RuFormats::parseWeekendPolicy);
            boolean enabled = column(cells, MarkdownFormat.COL_ENABLED, v -> RuFormats.isEmptyValue(v) || RuFormats.parseBoolean(v));
            RecurringRule rule = new RecurringRule(new RuleId(TEMP_ID), title, kind, amount,
                    header.cell(cells, MarkdownFormat.COL_CATEGORY), recurrence, from, until, policy, enabled,
                    header.cell(cells, MarkdownFormat.COL_NOTE));
            return new Pending<>(header.cell(cells, MarkdownFormat.COL_ID), lineNo, title, rule);
        }

        private Pending<OneTimeTransaction> parseOneTime(List<String> cells, int lineNo, List<Diagnostic> rowDiagnostics) {
            requireColumns(MarkdownFormat.ONE_TIME_REQUIRED_COLUMNS);
            LocalDate date = column(cells, MarkdownFormat.COL_DATE, RuFormats::parseDate);
            String title = header.cell(cells, MarkdownFormat.COL_TITLE);
            Kind kind = column(cells, MarkdownFormat.COL_KIND, RuFormats::parseKind);
            Money amount = amount(cells, MarkdownFormat.COL_AMOUNT, lineNo, rowDiagnostics);
            OneTimeTransaction tx = new OneTimeTransaction(new TxId(TEMP_ID), date, title, kind, amount,
                    header.cell(cells, MarkdownFormat.COL_CATEGORY), header.cell(cells, MarkdownFormat.COL_NOTE));
            return new Pending<>(header.cell(cells, MarkdownFormat.COL_ID), lineNo, title, tx);
        }

        private Adjustment parseAdjustment(List<String> cells, int lineNo, List<Diagnostic> rowDiagnostics) {
            requireColumns(MarkdownFormat.ADJUSTMENT_REQUIRED_COLUMNS);
            RuleId ruleId = column(cells, MarkdownFormat.COL_RULE, v -> {
                if (RuFormats.isEmptyValue(v)) {
                    throw new IllegalArgumentException(Texts.get("markdown.read.noRuleId"));
                }
                return new RuleId(v);
            });
            LocalDate originalDate = column(cells, MarkdownFormat.COL_ORIGINAL_DATE, RuFormats::parseDate);
            RuFormats.ActionType type = column(cells, MarkdownFormat.COL_ACTION, RuFormats::parseActionType);
            Money newAmount = null;
            LocalDate newDate = null;
            String amountText = header.cell(cells, MarkdownFormat.COL_NEW_AMOUNT);
            String dateText = header.cell(cells, MarkdownFormat.COL_NEW_DATE);
            // Не нужные действию колонки не разбираются: опечатка в лишней ячейке не должна губить строку.
            if (type.requiresAmount()) {
                newAmount = amount(cells, MarkdownFormat.COL_NEW_AMOUNT, lineNo, rowDiagnostics);
            } else if (!RuFormats.isEmptyValue(amountText)) {
                rowDiagnostics.add(Diagnostic.warning(lineNo,
                        Texts.get("markdown.read.columnNotNeeded", MarkdownFormat.COL_NEW_AMOUNT, type.label())));
            }
            if (type.requiresDate()) {
                newDate = column(cells, MarkdownFormat.COL_NEW_DATE, RuFormats::parseDate);
            } else if (!RuFormats.isEmptyValue(dateText)) {
                rowDiagnostics.add(Diagnostic.warning(lineNo,
                        Texts.get("markdown.read.columnNotNeeded", MarkdownFormat.COL_NEW_DATE, type.label())));
            }
            Adjustment.Action action = RuFormats.buildAction(type, newAmount, newDate);
            return new Adjustment(new OccurrenceKey(ruleId, originalDate), action, header.cell(cells, MarkdownFormat.COL_NOTE));
        }

        private void unparsed(int lineNo, String originalLine, String sectionTitle, String message) {
            marks.add(new Mark(lineNo, MarkdownFormat.unparsedMark(lineNo, originalLine)));
            diagnostics.add(Diagnostic.error(lineNo, Texts.get("markdown.read.rowMovedToNote", sectionTitle, message)));
        }

        // -------------------------------------------------------------- сборка плана

        private Plan buildPlan() {
            String planName = name;
            if (!titleSeen) {
                planName = fallbackName;
                diagnostics.add(Diagnostic.info(Texts.get("markdown.read.titleMissingUsingFileName",
                        MarkdownFormat.TITLE_PREFIX.strip(), fallbackName)));
            } else if (planName.isBlank()) {
                planName = fallbackName;
                diagnostics.add(Diagnostic.info(titleLine, Texts.get("markdown.read.titleNameEmpty", fallbackName)));
            }

            checkFormat();
            String currency = required(MarkdownFormat.KEY_CURRENCY, Plan.DEFAULT_CURRENCY, Plan.DEFAULT_CURRENCY, v -> v);
            LocalDate start = required(MarkdownFormat.KEY_START, today,
                    Texts.get("markdown.read.todayDate", RuFormats.formatDate(today)), RuFormats::parseDate);
            Horizon defaultHorizon = new Horizon.Months(12);
            Horizon horizon = required(MarkdownFormat.KEY_HORIZON, defaultHorizon, defaultHorizon.label(), RuFormats::parseHorizon);
            Money startBalance = required(MarkdownFormat.KEY_START_BALANCE, Money.ZERO, Money.ZERO.format(), RuFormats::parseMoney);
            Money cushion = optional(MarkdownFormat.KEY_CUSHION, RuFormats::parseMoney);
            Goal goal = buildGoal();

            List<RecurringRule> finalRules = new ArrayList<>();
            List<String> ruleIds = assignIds(rules, "r", Texts.get("markdown.read.subject.rule"));
            for (int i = 0; i < rules.size(); i++) {
                finalRules.add(rules.get(i).value().withId(new RuleId(ruleIds.get(i))));
            }
            List<OneTimeTransaction> finalOneTimes = new ArrayList<>();
            List<String> txIds = assignIds(oneTimes, "t", Texts.get("markdown.read.subject.oneTime"));
            for (int i = 0; i < oneTimes.size(); i++) {
                finalOneTimes.add(oneTimes.get(i).value().withId(new TxId(txIds.get(i))));
            }

            List<RawBlock> blocks = new ArrayList<>();
            if (!parameterExtras.isEmpty()) {
                blocks.add(new RawBlock(MarkdownFormat.PARAMETER_EXTRAS_ANCHOR, parameterExtras));
            }
            blocks.addAll(rawBlocks);

            return new Plan(planName, buildNote(), currency, start, startBalance, horizon, cushion, goal,
                    finalRules, finalOneTimes, adjustments, blocks);
        }

        private Goal buildGoal() {
            Money target = optional(MarkdownFormat.KEY_GOAL, RuFormats::parseMoney);
            if (target != null) {
                LocalDate wishDate = optional(MarkdownFormat.KEY_GOAL_DATE, RuFormats::parseDate);
                Param title = param(MarkdownFormat.KEY_GOAL_TITLE);
                return new Goal(title == null ? "" : title.value(), target, wishDate);
            }
            // Дата и название цели без суммы бессмысленны для прогноза, но это данные пользователя:
            // оставляем их в списке параметров как есть.
            for (String key : List.of(MarkdownFormat.KEY_GOAL_DATE, MarkdownFormat.KEY_GOAL_TITLE)) {
                Param p = param(key);
                if (p != null) {
                    parameterExtras.add(new ListItem(p.key(), p.value()).format());
                    diagnostics.add(Diagnostic.warning(p.line(),
                            Texts.get("markdown.read.goalPartWithoutGoal", p.key(), MarkdownFormat.KEY_GOAL)));
                }
            }
            return null;
        }

        /**
         * Назначает окончательные ID: первое вхождение корректного ID сохраняется, повторы, некорректные
         * и пустые ID получают следующий свободный номер с префиксом.
         */
        private <T> List<String> assignIds(List<Pending<T>> items, String prefix, String what) {
            int n = items.size();
            String[] result = new String[n];
            Set<String> used = new HashSet<>();
            long max = 0;
            boolean[] valid = new boolean[n];
            for (int i = 0; i < n; i++) {
                String raw = items.get(i).rawId();
                valid[i] = !RuFormats.isEmptyValue(raw) && isValidId(raw);
                if (valid[i]) {
                    max = Math.max(max, numberOf(raw.strip(), prefix));
                }
            }
            for (int i = 0; i < n; i++) {
                if (valid[i] && used.add(items.get(i).rawId().strip())) {
                    result[i] = items.get(i).rawId().strip();
                }
            }
            for (int i = 0; i < n; i++) {
                if (result[i] != null) {
                    continue;
                }
                String id;
                do {
                    id = prefix + (++max);
                } while (used.contains(id));
                used.add(id);
                result[i] = id;
                Pending<T> item = items.get(i);
                String subject = item.title().isBlank() ? what : Texts.get("markdown.read.subjectTitled", what, item.title());
                if (RuFormats.isEmptyValue(item.rawId())) {
                    diagnostics.add(Diagnostic.info(item.line(), Texts.get("markdown.read.idAssigned", subject, id)));
                } else if (!valid[i]) {
                    diagnostics.add(Diagnostic.warning(item.line(),
                            Texts.get("markdown.read.idInvalid", item.rawId(), subject, id)));
                } else {
                    diagnostics.add(Diagnostic.warning(item.line(),
                            Texts.get("markdown.read.idTaken", item.rawId(), subject, id)));
                }
            }
            return List.of(result);
        }

        private static boolean isValidId(String raw) {
            try {
                new RuleId(raw);
                return true;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        private static long numberOf(String id, String prefix) {
            if (id.length() <= prefix.length() || id.length() > prefix.length() + 15 || !id.startsWith(prefix)) {
                return 0;
            }
            String digits = id.substring(prefix.length());
            return digits.chars().allMatch(c -> c >= '0' && c <= '9') ? Long.parseLong(digits) : 0;
        }

        private String buildNote() {
            List<String> result = new ArrayList<>();
            for (List<String> part : noteParts) {
                List<String> trimmed = trimBlankLines(part);
                if (!trimmed.isEmpty()) {
                    if (!result.isEmpty()) {
                        result.add("");
                    }
                    result.addAll(trimmed);
                }
            }
            marks.stream().sorted(Comparator.comparingInt(Mark::line)).forEach(m -> result.add(m.text()));
            return String.join("\n", result);
        }

        private List<Diagnostic> sortedDiagnostics() {
            // Стабильная сортировка: сообщения одной строки остаются в порядке появления.
            List<Diagnostic> sorted = new ArrayList<>(diagnostics);
            sorted.sort(Comparator.comparingInt(Diagnostic::line));
            return sorted;
        }

        private static List<String> trimBlankLines(List<String> source) {
            int from = 0;
            int to = source.size();
            while (from < to && source.get(from).isBlank()) {
                from++;
            }
            while (to > from && source.get(to - 1).isBlank()) {
                to--;
            }
            return new ArrayList<>(source.subList(from, to));
        }
    }
}
