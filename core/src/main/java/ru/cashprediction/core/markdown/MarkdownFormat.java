package ru.cashprediction.core.markdown;

import java.util.List;

/**
 * Словарь формата файла плана: заголовок, названия секций, ключи параметров и названия колонок таблиц.
 *
 * <p>Все тексты, которые читатель ищет в файле и которые писатель в него выводит, собраны здесь,
 * чтобы {@link PlanMarkdownReader}, {@link PlanMarkdownWriter} и справка {@code FORMAT.md}
 * не разошлись в написании хотя бы одной буквы. Сравнение при чтении выполняется без учёта регистра,
 * лишних пробелов и различия «ё/е» (см. {@link RuFormats#normalize(String)}), а писатель всегда
 * использует каноническое написание из этого класса.</p>
 *
 * <p>Класс содержит только неизменяемые константы и потокобезопасен.</p>
 */
public final class MarkdownFormat {

    /** Версия формата файла плана, которую пишет эта программа. */
    public static final int FORMAT_VERSION = 1;

    /** Название формата в параметре «Формат»: {@code CashPrediction 1}. */
    public static final String FORMAT_NAME = "CashPrediction";

    /** Начало первой строки файла: {@code # План: Семейный бюджет 2026}. */
    public static final String TITLE_PREFIX = "# План: ";

    /** Префикс заголовка секции второго уровня. */
    public static final String SECTION_PREFIX = "## ";

    // ------------------------------------------------------------------ секции

    /** Секция параметров плана (список «- Ключ: значение»). */
    public static final String SECTION_PARAMETERS = "Параметры";

    /** Секция свободной заметки. */
    public static final String SECTION_NOTE = "Заметка";

    /** Секция таблицы регулярных операций. */
    public static final String SECTION_RULES = "Регулярные операции";

    /** Секция таблицы разовых операций. */
    public static final String SECTION_ONE_TIME = "Разовые операции";

    /** Секция таблицы корректировок отдельных событий. */
    public static final String SECTION_ADJUSTMENTS = "Корректировки";

    /** Известные секции в том порядке, в котором их выводит писатель. */
    public static final List<String> SECTION_ORDER = List.of(
            SECTION_PARAMETERS, SECTION_NOTE, SECTION_RULES, SECTION_ONE_TIME, SECTION_ADJUSTMENTS);

    /**
     * Особое значение {@code RawBlock.afterSection} для нераспознанных пунктов списка параметров.
     *
     * <p>Обычный {@code RawBlock} выводится отдельным абзацем после своей секции. Неизвестный параметр
     * ({@code - Мой ключ: значение}) должен остаться внутри списка «Параметры», иначе после сохранения
     * он «выпадет» из списка. Поэтому такие строки хранятся под этим служебным именем, которое
     * не может совпасть с названием секции: в файле заголовок секции не содержит символа «§».</p>
     */
    public static final String PARAMETER_EXTRAS_ANCHOR = "§Параметры:список";

    // ------------------------------------------------------------------ ключи параметров

    /** Параметр версии формата: {@code - Формат: CashPrediction 1}. */
    public static final String KEY_FORMAT = "Формат";

    /** Параметр обозначения валюты: {@code - Валюта: ₽}. */
    public static final String KEY_CURRENCY = "Валюта";

    /** Параметр даты начала плана: {@code - Начало: 2026-09-01}. */
    public static final String KEY_START = "Начало";

    /** Параметр горизонта прогноза: {@code - Горизонт: 12 месяцев}. */
    public static final String KEY_HORIZON = "Горизонт";

    /** Параметр баланса на дату начала: {@code - Начальный баланс: 150 000,00}. */
    public static final String KEY_START_BALANCE = "Начальный баланс";

    /** Необязательный параметр подушки безопасности. */
    public static final String KEY_CUSHION = "Подушка безопасности";

    /** Необязательный параметр суммы цели накопления. */
    public static final String KEY_GOAL = "Цель";

    /** Необязательный параметр желаемой даты достижения цели. */
    public static final String KEY_GOAL_DATE = "Цель к дате";

    /** Необязательный параметр названия цели. */
    public static final String KEY_GOAL_TITLE = "Название цели";

    // ------------------------------------------------------------------ колонки таблиц

    /** Колонка идентификатора (r1, t1). */
    public static final String COL_ID = "ID";
    /** Колонка названия операции. */
    public static final String COL_TITLE = "Название";
    /** Колонка типа: доход / расход. */
    public static final String COL_KIND = "Тип";
    /** Колонка суммы. */
    public static final String COL_AMOUNT = "Сумма";
    /** Колонка категории. */
    public static final String COL_CATEGORY = "Категория";
    /** Колонка правила повтора. */
    public static final String COL_RECURRENCE = "Повтор";
    /** Колонка первой допустимой даты правила. */
    public static final String COL_FROM = "С";
    /** Колонка последней допустимой даты правила. */
    public static final String COL_UNTIL = "По";
    /** Колонка сдвига с выходных. */
    public static final String COL_WEEKEND = "Выходные";
    /** Колонка признака «правило включено». */
    public static final String COL_ENABLED = "Активна";
    /** Колонка заметки. */
    public static final String COL_NOTE = "Заметка";
    /** Колонка даты разовой операции. */
    public static final String COL_DATE = "Дата";
    /** Колонка идентификатора правила в корректировке. */
    public static final String COL_RULE = "Правило";
    /** Колонка номинальной даты корректируемого события. */
    public static final String COL_ORIGINAL_DATE = "Исходная дата";
    /** Колонка действия корректировки. */
    public static final String COL_ACTION = "Действие";
    /** Колонка новой суммы корректировки. */
    public static final String COL_NEW_AMOUNT = "Новая сумма";
    /** Колонка новой даты корректировки. */
    public static final String COL_NEW_DATE = "Новая дата";

    /** Колонки таблицы регулярных операций в порядке вывода. */
    public static final List<String> RULE_COLUMNS = List.of(
            COL_ID, COL_TITLE, COL_KIND, COL_AMOUNT, COL_CATEGORY, COL_RECURRENCE,
            COL_FROM, COL_UNTIL, COL_WEEKEND, COL_ENABLED, COL_NOTE);

    /** Обязательные колонки таблицы регулярных операций: без них строку нельзя превратить в правило. */
    public static final List<String> RULE_REQUIRED_COLUMNS = List.of(COL_KIND, COL_AMOUNT, COL_RECURRENCE);

    /** Колонки таблицы разовых операций в порядке вывода. */
    public static final List<String> ONE_TIME_COLUMNS = List.of(
            COL_ID, COL_DATE, COL_TITLE, COL_KIND, COL_AMOUNT, COL_CATEGORY, COL_NOTE);

    /** Обязательные колонки таблицы разовых операций. */
    public static final List<String> ONE_TIME_REQUIRED_COLUMNS = List.of(COL_DATE, COL_KIND, COL_AMOUNT);

    /** Колонки таблицы корректировок в порядке вывода. */
    public static final List<String> ADJUSTMENT_COLUMNS = List.of(
            COL_RULE, COL_ORIGINAL_DATE, COL_ACTION, COL_NEW_AMOUNT, COL_NEW_DATE, COL_NOTE);

    /** Обязательные колонки таблицы корректировок. */
    public static final List<String> ADJUSTMENT_REQUIRED_COLUMNS = List.of(COL_RULE, COL_ORIGINAL_DATE, COL_ACTION);

    /**
     * Начало пометки, с которой неразобранная строка таблицы переносится в заметку:
     * {@code (не разобрано, строка 27: | r9 | ... |)}.
     */
    public static final String UNPARSED_PREFIX = "(не разобрано, строка ";

    private MarkdownFormat() {
    }

    /**
     * Строит пометку для строки, которую не удалось разобрать.
     *
     * @param line         номер строки файла, начиная с 1
     * @param originalLine исходный текст строки
     * @return текст вида {@code (не разобрано, строка 27: | r9 | ... |)}
     */
    public static String unparsedMark(int line, String originalLine) {
        return UNPARSED_PREFIX + line + ": " + originalLine.strip() + ")";
    }

    /**
     * Строка параметра «Формат» для текущей версии.
     *
     * @return {@code CashPrediction 1}
     */
    public static String formatValue() {
        return FORMAT_NAME + " " + FORMAT_VERSION;
    }

    /** Путь к справке о формате файла внутри модуля core. */
    public static final String USER_GUIDE_RESOURCE = "/ru/cashprediction/core/FORMAT.md";

    /**
     * Текст справки «Формат файла .md» для пункта меню «Справка».
     *
     * <p>Ресурс читается классом самого модуля core: в модульном режиме ресурсы модуля инкапсулированы,
     * и клиенты (JavaFX, Swing, Web) не смогли бы прочитать его своими загрузчиками напрямую.</p>
     *
     * @return содержимое {@code FORMAT.md} или короткое сообщение, если ресурс не найден
     */
    public static String userGuide() {
        try (java.io.InputStream in = MarkdownFormat.class.getResourceAsStream(USER_GUIDE_RESOURCE)) {
            if (in != null) {
                String text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                return text.startsWith("﻿") ? text.substring(1) : text;
            }
        } catch (java.io.IOException e) {
            // Повреждённая сборка: справка не критична, ниже вернём понятное сообщение.
        }
        return "Справка о формате файла недоступна: ресурс " + USER_GUIDE_RESOURCE + " не найден.";
    }

    /**
     * Экранирует строку заметки перед записью.
     *
     * <p>Строка заметки, начинающаяся с «#» (например, «## Идеи»), при следующем чтении была бы принята
     * за заголовок секции и «вырвалась» бы из заметки. Поэтому перед первым «#» (после отступа и уже
     * имеющихся обратных косых) добавляется одна обратная косая: {@code ## Идеи} → {@code \## Идеи}.
     * Правило обратимо: {@code \# x} записывается как {@code \\# x} и читается обратно как {@code \# x}.</p>
     *
     * @param line строка заметки
     * @return строка для файла
     */
    public static String escapeNoteLine(String line) {
        int start = indentEnd(line);
        int hash = backslashesEnd(line, start);
        if (hash < line.length() && line.charAt(hash) == '#') {
            return line.substring(0, start) + "\\" + line.substring(start);
        }
        return line;
    }

    /**
     * Снимает экранирование, добавленное {@link #escapeNoteLine(String)}.
     *
     * @param line строка заметки из файла
     * @return исходная строка заметки
     */
    public static String unescapeNoteLine(String line) {
        int start = indentEnd(line);
        int hash = backslashesEnd(line, start);
        if (hash > start && hash < line.length() && line.charAt(hash) == '#') {
            return line.substring(0, start) + line.substring(start + 1);
        }
        return line;
    }

    private static int indentEnd(String line) {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int backslashesEnd(String line, int from) {
        int i = from;
        while (i < line.length() && line.charAt(i) == '\\') {
            i++;
        }
        return i;
    }
}
