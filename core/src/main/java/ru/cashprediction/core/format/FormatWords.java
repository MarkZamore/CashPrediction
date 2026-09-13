package ru.cashprediction.core.format;

import java.io.InputStream;
import java.time.DayOfWeek;
import java.util.List;
import java.util.Set;
import ru.cashprediction.core.text.TextCatalog;

/**
 * Слова формата файлов CashMemory: грамматика данных, а не текст интерфейса (решение L13, этап S0.5).
 *
 * <p><b>Что здесь.</b> Названия секций, ключи параметров и колонок таблиц файла плана ({@code plan.*}), слова значений
 * («доход», «ежемесячно», «да», «пропустить», «месяцев»), ключи и значения {@code settings.md} ({@code settings.*}),
 * грамматика {@code web-session.md} ({@code session.md.*}) и общие для разбора слова ({@code common.*}: дни недели,
 * буквы «ё/е»). Всё это читает единственный ресурс {@value #RESOURCE}.</p>
 *
 * <p><b>Почему не каталог текстов.</b> Файлы, записанные программой, должны читаться при любом языке интерфейса. Поэтому
 * ресурс <b>никогда</b> не получает суффикса языка, у него нет запасного варианта, а значения не переводятся. Текст
 * интерфейса для тех же понятий («Доход» в выпадающем списке, «пн» в таблице) берётся из {@code core.text.Texts}
 * отдельными ключами, даже если сейчас значения совпадают.</p>
 *
 * <p><b>Строгость.</b> Отсутствующее имя — всегда {@link IllegalStateException}, независимо от строгого режима текстов:
 * подмена слова грамматики заглушкой вида {@code !имя!} молча испортила бы файл пользователя. Ресурс читается строго
 * в UTF-8 тем же загрузчиком, что и каталог текстов ({@link TextCatalog}), поэтому повторы имён и ошибки кодировки
 * видны в {@link #duplicates()} и {@link #loadProblems()} и проверяются тестом.</p>
 *
 * <p><b>Написание значений.</b> Значение — слово или фраза в точном написании файла без пробелов по краям: пробелы,
 * двоеточия и скобки вокруг слов добавляет код. Исключение — строки {@code settings.title} и
 * {@code session.md.title.prefix}/{@code session.md.section.*}: они хранятся целиком вместе с «#», потому что целиком
 * сравниваются с началом строки файла.</p>
 *
 * <p>Пакет используется только внутри ядра и не экспортируется. Класс без состояния, словарь загружается один раз при
 * первом обращении; потокобезопасен.</p>
 */
public final class FormatWords {

    /** Абсолютное имя ресурса со словами формата внутри модуля ядра (без суффикса языка). */
    public static final String RESOURCE = "/ru/cashprediction/core/format/format.properties";

    /** Имя файла ресурса без папки. */
    static final String FILE_NAME = "format.properties";

    private FormatWords() {
    }

    /**
     * Слово формата по имени.
     *
     * @param name имя, например {@code plan.section.rules}
     * @return значение в точном написании файла, например «Регулярные операции»
     * @throws IllegalStateException если имени нет в ресурсе (ошибка сборки, а не данных пользователя)
     */
    public static String get(String name) {
        return Holder.WORDS.template(name).orElseThrow(() -> new IllegalStateException(
                "Missing format word: " + name + (Holder.WORDS.loadProblems().isEmpty()
                        ? "" : " (load problems: " + Holder.WORDS.loadProblems() + ")")));
    }

    /**
     * Есть ли слово с таким именем.
     *
     * @param name имя
     * @return {@code true}, если слово задано
     */
    public static boolean has(String name) {
        return Holder.WORDS.has(name);
    }

    /** @return все имена ресурса, отсортированные по алфавиту (неизменяемое множество) */
    public static Set<String> names() {
        return Holder.WORDS.keys();
    }

    /** @return повторы имён вида {@code "plan.col.note: format, format"}; пустой список — повторов нет */
    public static List<String> duplicates() {
        return Holder.WORDS.duplicates();
    }

    /** @return проблемы загрузки ресурса (нет файла, ошибка чтения или кодировки); пустой список — всё прочитано */
    public static List<String> loadProblems() {
        return Holder.WORDS.loadProblems();
    }

    /**
     * Короткое название дня недели в написании файла плана: «пн» … «вс» («еженедельно сб»).
     *
     * @param day день недели
     * @return слово формата
     */
    public static String weekdayShort(DayOfWeek day) {
        // Имена пишутся литералами: так проверка «каждое слово ресурса используется» видит их в исходниках.
        return switch (day) {
            case MONDAY -> get("common.weekday.short.1");
            case TUESDAY -> get("common.weekday.short.2");
            case WEDNESDAY -> get("common.weekday.short.3");
            case THURSDAY -> get("common.weekday.short.4");
            case FRIDAY -> get("common.weekday.short.5");
            case SATURDAY -> get("common.weekday.short.6");
            case SUNDAY -> get("common.weekday.short.7");
        };
    }

    /**
     * Полное название дня недели, которое понимает разбор файла плана: «понедельник» … «воскресенье».
     *
     * @param day день недели
     * @return слово формата
     */
    public static String weekdayFull(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> get("common.weekday.full.1");
            case TUESDAY -> get("common.weekday.full.2");
            case WEDNESDAY -> get("common.weekday.full.3");
            case THURSDAY -> get("common.weekday.full.4");
            case FRIDAY -> get("common.weekday.full.5");
            case SATURDAY -> get("common.weekday.full.6");
            case SUNDAY -> get("common.weekday.full.7");
        };
    }

    /**
     * Загружает словарь из произвольного источника (для тестов загрузчика).
     *
     * @param source источник файла {@value #FILE_NAME}
     * @return словарь: пустой язык означает «только файл без суффикса, без запасного варианта»
     */
    static TextCatalog load(TextCatalog.ResourceSource source) {
        return TextCatalog.load(List.of("format"), "", source);
    }

    /** Ленивый держатель словаря: загрузка при первом обращении, потокобезопасно средствами JVM. */
    private static final class Holder {
        // Ресурс ищется классом ядра: пакет ресурса принадлежит модулю ядра, инкапсуляция модулей не мешает.
        static final TextCatalog WORDS = load(name -> open(name));

        private static InputStream open(String name) {
            return FILE_NAME.equals(name) ? FormatWords.class.getResourceAsStream(RESOURCE) : null;
        }
    }
}
