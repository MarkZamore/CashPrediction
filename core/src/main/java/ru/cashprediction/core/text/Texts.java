package ru.cashprediction.core.text;

import java.util.ArrayList;
import java.util.List;

/**
 * Общий каталог текстов программы — единственный источник русских строк для всех слоёв ядра и трёх клиентов
 * (решение L13).
 *
 * <p><b>Где лежат тексты.</b> Ресурсы ядра {@code ru/cashprediction/core/ui/text/<область>_ru.properties}
 * (UTF-8), по файлу на область ({@link #AREAS}). Язык зафиксирован: {@value #LANGUAGE}; переключения языка и
 * английских файлов пока нет, но поиск уже устроен как {@code <область>_<язык>} с запасным {@code <область>}.</p>
 *
 * <p><b>Кто пользуется.</b> Этот класс не зависит ни от одного пакета ядра, поэтому им пользуются и
 * {@code core.model} ({@code Money.parse}), и {@code core.session}, и прогноз, и Markdown; модели интерфейса
 * обращаются к тем же текстам через {@code core.ui.text.UiText}. Новый код ядра не содержит русских строковых
 * литералов: всё, что видит пользователь, берётся отсюда по ключу.</p>
 *
 * <p><b>Не тексты.</b> Ключевые слова формата файлов плана и web-сеанса — грамматика данных, а не интерфейс:
 * на этапе S0.5 они переезжают в отдельный нелокализуемый ресурс без суффикса языка, чтобы сохранённые файлы
 * читались при любом языке интерфейса.</p>
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
     * Все области каталога в порядке загрузки: {@link #UI_AREAS} и области слоёв ядра без интерфейса
     * ({@code model} — сообщения доменной модели; этап S0.5 добавляет сюда области прогноза, Markdown и сеанса).
     */
    public static final List<String> AREAS = concat(UI_AREAS, List.of("model"));

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

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return List.copyOf(all);
    }

    /** Ленивый держатель каталога: загрузка при первом обращении, потокобезопасно средствами JVM. */
    private static final class Holder {
        // Ресурс ищется классом ядра: пакет ресурса принадлежит модулю ядра, поэтому инкапсуляция модулей не мешает.
        static final TextCatalog CATALOG = TextCatalog.load(AREAS, LANGUAGE,
                name -> Texts.class.getResourceAsStream(RESOURCE_DIR + name));
    }
}
