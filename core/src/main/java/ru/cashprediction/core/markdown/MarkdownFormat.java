package ru.cashprediction.core.markdown;

import java.util.List;
import ru.cashprediction.core.format.FormatWords;
import ru.cashprediction.core.text.Texts;

/**
 * Словарь формата файла плана: заголовок, названия секций, ключи параметров и названия колонок таблиц.
 *
 * <p>Все тексты, которые читатель ищет в файле и которые писатель в него выводит, собраны здесь,
 * чтобы {@link PlanMarkdownReader}, {@link PlanMarkdownWriter} и справка ({@link #userGuide()})
 * не разошлись в написании хотя бы одной буквы. Сравнение при чтении выполняется без учёта регистра,
 * лишних пробелов и различия «ё/е» (см. {@link RuFormats#normalize(String)}), а писатель всегда
 * использует каноническое написание из этого класса.</p>
 *
 * <p><b>Откуда слова (решение L13).</b> Русские слова формата берутся из нелокализуемого ресурса
 * {@link FormatWords} ({@code plan.*}): это грамматика данных, а не текст интерфейса, поэтому файл читается при
 * любом языке интерфейса. Константы остались публичными ради совместимости исходников, но заполняются при загрузке
 * класса и больше не являются константами времени компиляции (их нельзя ставить в {@code case}).</p>
 *
 * <p>Класс содержит только неизменяемые значения и потокобезопасен.</p>
 */
public final class MarkdownFormat {

    /** Версия формата файла плана, которую пишет эта программа. */
    public static final int FORMAT_VERSION = 1;

    /** Название формата в параметре «Формат»: {@code CashPrediction 1} (слово формата {@code plan.format.name}). */
    public static final String FORMAT_NAME = FormatWords.get("plan.format.name");

    /** Слово заголовка плана: «План» в строке {@code # План: имя}. */
    public static final String TITLE_WORD = FormatWords.get("plan.title.word");

    /** Начало первой строки файла: {@code # План: Семейный бюджет 2026}. */
    public static final String TITLE_PREFIX = "# " + TITLE_WORD + ": ";

    /** Префикс заголовка секции второго уровня. */
    public static final String SECTION_PREFIX = "## ";

    // ------------------------------------------------------------------ секции

    /** Секция параметров плана (список «- Ключ: значение»). */
    public static final String SECTION_PARAMETERS = FormatWords.get("plan.section.parameters");

    /** Секция свободной заметки. */
    public static final String SECTION_NOTE = FormatWords.get("plan.section.note");

    /** Секция таблицы регулярных операций. */
    public static final String SECTION_RULES = FormatWords.get("plan.section.rules");

    /** Секция таблицы разовых операций. */
    public static final String SECTION_ONE_TIME = FormatWords.get("plan.section.oneTime");

    /** Секция таблицы корректировок отдельных событий. */
    public static final String SECTION_ADJUSTMENTS = FormatWords.get("plan.section.adjustments");

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
    public static final String PARAMETER_EXTRAS_ANCHOR = FormatWords.get("plan.parameter.extrasAnchor");

    // ------------------------------------------------------------------ ключи параметров

    /** Параметр версии формата: {@code - Формат: CashPrediction 1}. */
    public static final String KEY_FORMAT = FormatWords.get("plan.key.format");

    /** Параметр обозначения валюты: {@code - Валюта: ₽}. */
    public static final String KEY_CURRENCY = FormatWords.get("plan.key.currency");

    /** Параметр даты начала плана: {@code - Начало: 2026-09-01}. */
    public static final String KEY_START = FormatWords.get("plan.key.start");

    /** Параметр горизонта прогноза: {@code - Горизонт: 12 месяцев}. */
    public static final String KEY_HORIZON = FormatWords.get("plan.key.horizon");

    /** Параметр баланса на дату начала: {@code - Начальный баланс: 150 000,00}. */
    public static final String KEY_START_BALANCE = FormatWords.get("plan.key.startBalance");

    /** Необязательный параметр подушки безопасности. */
    public static final String KEY_CUSHION = FormatWords.get("plan.key.cushion");

    /** Необязательный параметр суммы цели накопления. */
    public static final String KEY_GOAL = FormatWords.get("plan.key.goal");

    /** Необязательный параметр желаемой даты достижения цели. */
    public static final String KEY_GOAL_DATE = FormatWords.get("plan.key.goalDate");

    /** Необязательный параметр названия цели. */
    public static final String KEY_GOAL_TITLE = FormatWords.get("plan.key.goalTitle");

    // ------------------------------------------------------------------ колонки таблиц

    /** Колонка идентификатора (r1, t1). */
    public static final String COL_ID = FormatWords.get("plan.col.id");
    /** Колонка названия операции. */
    public static final String COL_TITLE = FormatWords.get("plan.col.title");
    /** Колонка типа: доход / расход. */
    public static final String COL_KIND = FormatWords.get("plan.col.kind");
    /** Колонка суммы. */
    public static final String COL_AMOUNT = FormatWords.get("plan.col.amount");
    /** Колонка категории. */
    public static final String COL_CATEGORY = FormatWords.get("plan.col.category");
    /** Колонка правила повтора. */
    public static final String COL_RECURRENCE = FormatWords.get("plan.col.recurrence");
    /** Колонка первой допустимой даты правила. */
    public static final String COL_FROM = FormatWords.get("plan.col.from");
    /** Колонка последней допустимой даты правила. */
    public static final String COL_UNTIL = FormatWords.get("plan.col.until");
    /** Колонка сдвига с выходных. */
    public static final String COL_WEEKEND = FormatWords.get("plan.col.weekend");
    /** Колонка признака «правило включено». */
    public static final String COL_ENABLED = FormatWords.get("plan.col.enabled");
    /** Колонка заметки. */
    public static final String COL_NOTE = FormatWords.get("plan.col.note");
    /** Колонка даты разовой операции. */
    public static final String COL_DATE = FormatWords.get("plan.col.date");
    /** Колонка идентификатора правила в корректировке. */
    public static final String COL_RULE = FormatWords.get("plan.col.rule");
    /** Колонка номинальной даты корректируемого события. */
    public static final String COL_ORIGINAL_DATE = FormatWords.get("plan.col.originalDate");
    /** Колонка действия корректировки. */
    public static final String COL_ACTION = FormatWords.get("plan.col.action");
    /** Колонка новой суммы корректировки. */
    public static final String COL_NEW_AMOUNT = FormatWords.get("plan.col.newAmount");
    /** Колонка новой даты корректировки. */
    public static final String COL_NEW_DATE = FormatWords.get("plan.col.newDate");

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
    public static final String UNPARSED_PREFIX = "(" + FormatWords.get("plan.unparsed.lead") + " ";

    /**
     * Имя справки о формате файла в каталоге текстов без суффикса языка и расширения: файл
     * {@code ru/cashprediction/core/ui/text/help-format_ru.md}. Справка — текст интерфейса (решение L13), поэтому она
     * лежит рядом с областями каталога и ищется по языку, в отличие от слов формата {@link FormatWords}.
     */
    public static final String USER_GUIDE_NAME = "help-format";

    /** Расширение файла справки о формате (без точки). */
    public static final String USER_GUIDE_EXTENSION = "md";

    /**
     * Абсолютное имя ресурса справки о формате для текущего языка внутри модуля core, например
     * {@code /ru/cashprediction/core/ui/text/help-format_ru.md}. Вычисляется при загрузке класса.
     */
    public static final String USER_GUIDE_RESOURCE = Texts.documentResource(USER_GUIDE_NAME, USER_GUIDE_EXTENSION);

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

    /**
     * Текст справки «Формат файла .md» для пункта меню «Справка».
     *
     * <p>Документ ищется каталогом текстов по языку ({@link Texts#document(String, String)}):
     * {@code help-format_ru.md}, а если его нет — {@code help-format.md}; читается строго в UTF-8 без BOM. Ресурс
     * читается классом самого модуля core: в модульном режиме ресурсы модуля инкапсулированы, и клиенты (JavaFX,
     * Swing, Web) не смогли бы прочитать его своими загрузчиками напрямую.</p>
     *
     * @return текст справки или короткое сообщение из каталога текстов, если документ не найден или не читается
     */
    public static String userGuide() {
        // Повреждённая сборка: справка не критична, вместо неё — понятное сообщение.
        return Texts.document(USER_GUIDE_NAME, USER_GUIDE_EXTENSION)
                .orElseGet(() -> Texts.get("markdown.help.unavailable", USER_GUIDE_RESOURCE));
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
