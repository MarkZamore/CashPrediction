package ru.cashprediction.core.text;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Каталог текстов, не зависящий от языка и от слоя ядра: набор файлов {@code .properties} по областям.
 *
 * <p><b>Файлы и язык.</b> Для каждой области ищется сначала {@code <область>_<язык>.properties}
 * (например {@code menu_ru.properties}), а если его нет — {@code <область>.properties}. Файлы читаются строго
 * в UTF-8: байты не в UTF-8 (например, файл, сохранённый в cp1251) — проблема загрузки, а не молча испорченный
 * текст. Кириллица пишется в файлах как есть, без {@code \}{@code uXXXX}.</p>
 *
 * <p><b>Ключи.</b> Один ключ живёт ровно в одном файле. Повтор ключа в том же файле или в другой области
 * записывается в {@link #duplicates()} (побеждает первый загруженный текст) и ловится тестом каталога.</p>
 *
 * <p><b>Подстановки.</b> {@code {0}}, {@code {1}}, … заменяются аргументами за один проход через
 * {@link String#valueOf(Object)}: текст аргумента повторно не разбирается. {@link java.text.MessageFormat}
 * намеренно не используется — он по-своему трактует апострофы и форматирует числа по локали JVM, а суммы и даты
 * ядро форматирует само. Фигурные скобки без номера остаются как есть.</p>
 *
 * <p><b>Отсутствующий ключ.</b> При системном свойстве {@value #STRICT_PROPERTY}{@code =true} (так запускаются
 * тесты ядра) {@link #get(String, Object...)} бросает {@link IllegalStateException}, а номер подстановки без
 * аргумента — {@link IllegalArgumentException}. Без свойства (у пользователя) возвращается {@code !ключ!}:
 * пропуск виден, но программа не падает. Сообщения этих исключений адресованы разработчику и пишутся латиницей:
 * в коде ядра нет русских строковых литералов (решение L13).</p>
 *
 * <p>Экземпляр неизменяем и потокобезопасен.</p>
 */
public final class TextCatalog {

    /** Системное свойство строгого режима: отсутствующий ключ или аргумент — исключение. */
    public static final String STRICT_PROPERTY = "cashprediction.ui.strictText";

    /**
     * Источник файлов каталога по имени файла.
     *
     * <p>Позволяет загружать каталог из ресурсов модуля ядра, из ресурсов теста или из памяти.</p>
     */
    @FunctionalInterface
    public interface ResourceSource {

        /**
         * Открывает файл каталога.
         *
         * @param fileName имя файла без папки, например {@code menu_ru.properties}
         * @return поток или {@code null}, если такого файла нет
         * @throws IOException если файл есть, но не открывается
         */
        InputStream open(String fileName) throws IOException;
    }

    private final String language;
    private final Map<String, String> values;
    private final Map<String, String> areaOf;
    private final Map<String, String> files;
    private final Set<String> sortedKeys;
    private final List<String> duplicates;
    private final List<String> problems;

    private TextCatalog(String language, Map<String, String> values, Map<String, String> areaOf,
                        Map<String, String> files, List<String> duplicates, List<String> problems) {
        this.language = language;
        this.values = Collections.unmodifiableMap(values);
        this.areaOf = Collections.unmodifiableMap(areaOf);
        this.files = Collections.unmodifiableMap(files);
        this.sortedKeys = Collections.unmodifiableSet(new TreeSet<>(values.keySet()));
        this.duplicates = List.copyOf(duplicates);
        this.problems = List.copyOf(problems);
    }

    /**
     * Загружает каталог: по файлу на область с запасным файлом без суффикса языка.
     *
     * @param areas    области в порядке загрузки (порядок важен только для сообщений о повторах)
     * @param language код языка, например {@code ru}; пустая строка — только файлы без суффикса
     * @param source   источник файлов
     * @return неизменяемый каталог; проблемы загрузки — в {@link #loadProblems()}
     */
    public static TextCatalog load(List<String> areas, String language, ResourceSource source) {
        Objects.requireNonNull(areas, "areas");
        Objects.requireNonNull(source, "source");
        String lang = Objects.requireNonNullElse(language, "").strip();
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> areaOf = new LinkedHashMap<>();
        Map<String, String> files = new LinkedHashMap<>();
        List<String> duplicates = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (String area : areas) {
            List<String> candidates = lang.isEmpty()
                    ? List.of(area + ".properties")
                    : List.of(area + "_" + lang + ".properties", area + ".properties");
            RecordingProperties properties = new RecordingProperties();
            String loaded = null;
            for (String candidate : candidates) {
                try (InputStream in = source.open(candidate)) {
                    if (in == null) {
                        continue;
                    }
                    loaded = candidate;
                    // Строгий декодер: файл не в UTF-8 даёт ошибку, а не текст с «кракозябрами».
                    try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT))) {
                        properties.load(reader);
                    }
                } catch (IOException | IllegalArgumentException e) {
                    problems.add("Cannot read text file " + candidate + ": " + e);
                    loaded = null;
                }
                // Существующий (даже битый) файл языка не подменяется запасным: иначе ошибка кодировки скрылась бы.
                break;
            }
            if (loaded == null) {
                if (problems.stream().noneMatch(p -> p.contains(" " + candidates.getFirst() + ":"))) {
                    problems.add("Missing text file for area '" + area + "': tried " + String.join(", ", candidates));
                }
                continue;
            }
            files.put(area, loaded);
            for (String key : properties.repeated) {
                duplicates.add(key + ": " + area + ", " + area);
            }
            for (String key : properties.orderedKeys) {
                String previous = areaOf.get(key);
                if (previous != null) {
                    duplicates.add(key + ": " + previous + ", " + area);
                    continue;
                }
                values.put(key, properties.getProperty(key));
                areaOf.put(key, area);
            }
        }
        return new TextCatalog(lang, values, areaOf, files, duplicates, problems);
    }

    /**
     * Имена файлов документа справки в порядке поиска.
     *
     * @param name      имя документа без суффикса языка, например {@code help-format}
     * @param extension расширение без точки, например {@code md}
     * @param language  код языка; пустая строка — только файл без суффикса
     * @return например {@code [help-format_ru.md, help-format.md]}
     */
    public static List<String> documentCandidates(String name, String extension, String language) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(extension, "extension");
        String lang = Objects.requireNonNullElse(language, "").strip();
        String plain = name + "." + extension;
        return lang.isEmpty() ? List.of(plain) : List.of(name + "_" + lang + "." + extension, plain);
    }

    /**
     * Читает документ справки (длинный текст, которому не место в {@code .properties}) по тем же правилам, что и
     * области каталога: сначала {@code <имя>_<язык>.<расширение>}, а если его нет — {@code <имя>.<расширение>}.
     *
     * <p>Файл читается строго в UTF-8; BOM в начале (его добавляет Блокнот) отбрасывается. Существующий, но
     * нечитаемый файл языка не подменяется запасным: иначе ошибка кодировки скрылась бы.</p>
     *
     * @param name      имя документа без суффикса языка
     * @param extension расширение без точки
     * @param language  код языка; пустая строка — только файл без суффикса
     * @param source    источник файлов
     * @return текст документа или пусто, если ни одного файла нет
     * @throws IOException если файл есть, но не читается или записан не в UTF-8
     */
    public static Optional<String> loadDocument(String name, String extension, String language, ResourceSource source)
            throws IOException {
        Objects.requireNonNull(source, "source");
        for (String candidate : documentCandidates(name, extension, language)) {
            try (InputStream in = source.open(candidate)) {
                if (in == null) {
                    continue;
                }
                String text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(in.readAllBytes()))
                        .toString();
                return Optional.of(text.startsWith("﻿") ? text.substring(1) : text);
            }
        }
        return Optional.empty();
    }

    /**
     * Текст по ключу с подстановкой аргументов; строгость — по свойству {@value #STRICT_PROPERTY}.
     *
     * @param key  ключ, например {@code status.msg.saved}
     * @param args значения для {@code {0}}, {@code {1}}, …
     * @return готовый текст; без строгого режима для неизвестного ключа — {@code !key!}
     * @throws IllegalStateException    в строгом режиме, если ключа нет
     * @throws IllegalArgumentException в строгом режиме, если в тексте есть {@code {n}} без аргумента
     */
    public String get(String key, Object... args) {
        String template = values.get(key);
        boolean strict = isStrict();
        if (template == null) {
            if (strict) {
                throw new IllegalStateException("Missing text key: " + key);
            }
            return "!" + key + "!";
        }
        return substitute(template, args == null ? new Object[0] : args, strict, key);
    }

    /**
     * Есть ли ключ в каталоге.
     *
     * @param key ключ
     * @return {@code true}, если текст задан
     */
    public boolean has(String key) {
        return values.containsKey(key);
    }

    /** @return все ключи, отсортированные по алфавиту (неизменяемое множество) */
    public Set<String> keys() {
        return sortedKeys;
    }

    /**
     * Шаблон текста без подстановки (для тестов каталога и дампов).
     *
     * @param key ключ
     * @return шаблон или пусто
     */
    public Optional<String> template(String key) {
        return Optional.ofNullable(values.get(key));
    }

    /**
     * Область, в файле которой задан ключ.
     *
     * @param key ключ
     * @return имя области, например {@code menu}, или пусто
     */
    public Optional<String> area(String key) {
        return Optional.ofNullable(areaOf.get(key));
    }

    /** @return имя файла, из которого загружена каждая область: {@code menu → menu_ru.properties} */
    public Map<String, String> loadedFiles() {
        return files;
    }

    /** @return язык каталога, например {@code ru} */
    public String language() {
        return language;
    }

    /** @return повторы ключей вида {@code "menu.file.new: menu, toolbar"}; пустой список — повторов нет */
    public List<String> duplicates() {
        return duplicates;
    }

    /** @return проблемы загрузки (нет файла области, ошибка чтения или кодировки); пустой список — всё прочитано */
    public List<String> loadProblems() {
        return problems;
    }

    /**
     * Подставляет аргументы в произвольный шаблон по правилам каталога (без строгого режима).
     *
     * @param template шаблон с {@code {0}}, {@code {1}}, …
     * @param args     аргументы
     * @return текст; номер без аргумента остаётся как есть
     */
    public static String format(String template, Object... args) {
        return substitute(template, args == null ? new Object[0] : args, false, "");
    }

    /**
     * Номера подстановок шаблона.
     *
     * @param template шаблон
     * @return отсортированное множество номеров, например {@code [0, 1]} для «{1}: {0}»
     */
    public static Set<Integer> placeholders(String template) {
        Set<Integer> result = new TreeSet<>();
        int i = 0;
        while (i < template.length()) {
            int end = placeholderEnd(template, i);
            if (end > 0) {
                result.add(Integer.parseInt(template.substring(i + 1, end - 1)));
                i = end;
            } else {
                i++;
            }
        }
        return result;
    }

    /** @return включён ли строгий режим (свойство читается при каждом вызове, чтобы тесты могли его менять) */
    public static boolean isStrict() {
        return Boolean.parseBoolean(System.getProperty(STRICT_PROPERTY, "false"));
    }

    private static String substitute(String template, Object[] args, boolean strict, String key) {
        if (template.indexOf('{') < 0) {
            return template;
        }
        StringBuilder sb = new StringBuilder(template.length() + 16);
        int i = 0;
        while (i < template.length()) {
            int end = placeholderEnd(template, i);
            if (end < 0) {
                sb.append(template.charAt(i));
                i++;
                continue;
            }
            int index = Integer.parseInt(template.substring(i + 1, end - 1));
            if (index < args.length) {
                sb.append(args[index]);
            } else if (strict) {
                throw new IllegalArgumentException("Text '" + key + "' has no argument {" + index + "}");
            } else {
                sb.append(template, i, end);
            }
            i = end;
        }
        return sb.toString();
    }

    /**
     * Если с позиции {@code start} начинается подстановка {@code {цифры}}, возвращает индекс за закрывающей скобкой.
     *
     * @return индекс после «}» или -1
     */
    private static int placeholderEnd(String text, int start) {
        if (text.charAt(start) != '{') {
            return -1;
        }
        int j = start + 1;
        // Не больше трёх цифр ASCII: номеров больше 999 не бывает, а длинные числа переполнили бы int.
        while (j < text.length() && j - start <= 3 && text.charAt(j) >= '0' && text.charAt(j) <= '9') {
            j++;
        }
        return j > start + 1 && j < text.length() && text.charAt(j) == '}' ? j + 1 : -1;
    }

    /**
     * {@link Properties}, запоминающий порядок ключей и ключи, повторённые внутри одного файла
     * ({@link Properties#load(Reader)} вызывает {@code put} на каждую строку и молча перезаписывает повтор).
     */
    private static final class RecordingProperties extends Properties {
        private final List<String> repeated = new ArrayList<>();
        private final List<String> orderedKeys = new ArrayList<>();

        @Override
        public synchronized Object put(Object key, Object value) {
            String name = String.valueOf(key);
            if (containsKey(key)) {
                repeated.add(name);
            } else {
                orderedKeys.add(name);
            }
            return super.put(key, value);
        }
    }
}
