package ru.cashprediction.core.text;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Общий каталог текстов программы — единственный источник русских строк для всех слоёв ядра и трёх клиентов
 * (решение L13).
 *
 * <p><b>Где лежат тексты.</b> Ресурсы ядра {@code ru/cashprediction/core/ui/text/<область>_ru.properties}
 * (UTF-8), по файлу на область ({@link #AREAS}). Язык зафиксирован: {@value #LANGUAGE}; переключения языка и
 * английских файлов пока нет, но поиск уже устроен как {@code <область>_<язык>} с запасным {@code <область>}.
 * Длинные тексты справки лежат там же отдельными документами {@code <имя>_<язык>.<расширение>} (например
 * {@code help-format_ru.md}) и читаются методом {@link #document(String, String)} по тем же правилам.</p>
 *
 * <p><b>Кто пользуется.</b> Этот класс не зависит ни от одного пакета ядра, поэтому им пользуются и
 * {@code core.model} ({@code Money.parse}), и {@code core.session}, и прогноз, и Markdown; модели интерфейса
 * обращаются к тем же текстам через {@code core.ui.text.UiText}. В основном коде ядра нет русских строковых
 * литералов (это проверяет {@code NoCyrillicLiteralsTest}): всё, что видит пользователь, берётся отсюда по ключу.</p>
 *
 * <p><b>Не тексты.</b> Ключевые слова формата файлов плана, {@code settings.md} и {@code web-session.md} —
 * грамматика данных, а не интерфейс. Они лежат в отдельном нелокализуемом ресурсе без суффикса языка
 * {@code ru/cashprediction/core/format/format.properties} и читаются классом {@code core.format.FormatWords}, чтобы
 * сохранённые файлы читались при любом языке интерфейса.</p>
 *
 * <p>Каталог загружается один раз при первом обращении и дальше неизменяем; класс потокобезопасен.</p>
 */
public final class Texts {

    /** Папка ресурсов каталога внутри модуля ядра (абсолютное имя ресурса). */
    public static final String RESOURCE_DIR = "/ru/cashprediction/core/ui/text/";

    /** Язык каталога; других языков пока нет. */
    public static final String LANGUAGE = "ru";

    /** Области текстов интерфейса (спецификация v2, §8; владельцы — задачи этапа S1). */
    public static final List<String> UI_AREAS = List.of("menu", "toolbar", "status", "hotkeys", "summary", "table",
            "popup", "chart", "forms-misc", "alerts", "buttons", "restore", "forms-plan", "forms-ops", "app");

    /**
     * Все области каталога в порядке загрузки: {@link #UI_AREAS} и области слоёв ядра без интерфейса, созданные на
     * этапе S0.5: {@code model} (доменная модель), {@code dates} (месяцы и дни недели), {@code markdown} (чтение
     * файла плана), {@code diagnostics} (проверка плана), {@code document} (документ плана), {@code export} (CSV),
     * {@code forecast} (прогноз и генерация дат), {@code io} (файлы CashMemory), {@code json} (JSON web-клиента)
     * и {@code session} (снимки и восстановление сеанса).
     */
    public static final List<String> AREAS = concat(UI_AREAS, List.of("model", "dates", "markdown",
            "diagnostics", "document", "export", "forecast", "io", "json", "session"));

    private Texts() {
    }

    /** @return загруженный общий каталог */
    public static TextCatalog catalog() {
        return Holder.CATALOG;
    }

    /**
     * Текст по ключу общего каталога.
     *
     * @param key  ключ
     * @param args аргументы подстановок
     * @return готовый текст
     * @see TextCatalog#get(String, Object...)
     */
    public static String get(String key, Object... args) {
        return Holder.CATALOG.get(key, args);
    }

    /**
     * Есть ли ключ в общем каталоге.
     *
     * @param key ключ
     * @return {@code true}, если текст задан
     */
    public static boolean has(String key) {
        return Holder.CATALOG.has(key);
    }

    /**
     * Имя файла области для текущего языка.
     *
     * @param area область, например {@code menu}
     * @return например {@code menu_ru.properties}
     */
    public static String fileName(String area) {
        return area + "_" + LANGUAGE + ".properties";
    }

    /**
     * Имя документа справки для текущего языка.
     *
     * @param name      имя документа без суффикса языка, например {@code help-format}
     * @param extension расширение без точки, например {@code md}
     * @return например {@code help-format_ru.md}
     */
    public static String documentFileName(String name, String extension) {
        return TextCatalog.documentCandidates(name, extension, LANGUAGE).getFirst();
    }

    /**
     * Абсолютное имя ресурса документа справки, который будет прочитан для текущего языка.
     *
     * @param name      имя документа без суффикса языка
     * @param extension расширение без точки
     * @return первый существующий из {@code <имя>_<язык>.<расширение>} и {@code <имя>.<расширение>}; если нет ни
     *         одного — имя файла текущего языка (для сообщения о недоступной справке)
     */
    public static String documentResource(String name, String extension) {
        for (String candidate : TextCatalog.documentCandidates(name, extension, LANGUAGE)) {
            if (Texts.class.getResource(RESOURCE_DIR + candidate) != null) {
                return RESOURCE_DIR + candidate;
            }
        }
        return RESOURCE_DIR + documentFileName(name, extension);
    }

    /**
     * Документ справки на языке каталога: {@code <имя>_<язык>.<расширение>} с запасным
     * {@code <имя>.<расширение>}, строго в UTF-8.
     *
     * @param name      имя документа без суффикса языка, например {@code help-format}
     * @param extension расширение без точки, например {@code md}
     * @return текст без BOM или пусто, если документа нет или он не читается (справка не критична)
     * @see TextCatalog#loadDocument(String, String, String, TextCatalog.ResourceSource)
     */
    public static Optional<String> document(String name, String extension) {
        try {
            return TextCatalog.loadDocument(name, extension, LANGUAGE, Texts::openResource);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static java.io.InputStream openResource(String fileName) {
        // Ресурс ищется классом ядра: пакет ресурса принадлежит модулю ядра, поэтому инкапсуляция модулей не мешает.
        return Texts.class.getResourceAsStream(RESOURCE_DIR + fileName);
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return List.copyOf(all);
    }

    /** Ленивый держатель каталога: загрузка при первом обращении, потокобезопасно средствами JVM. */
    private static final class Holder {
        static final TextCatalog CATALOG = TextCatalog.load(AREAS, LANGUAGE, Texts::openResource);
    }
}
