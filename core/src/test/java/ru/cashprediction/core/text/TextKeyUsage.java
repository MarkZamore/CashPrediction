package ru.cashprediction.core.text;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Сбор ссылок на ключи каталога текстов из исходников: общий инструмент проверок «нет отсутствующих, нет лишних
 * ключей» (решение L13, «файлы локализации актуальны для всех клиентов»).
 *
 * <p><b>Наборы исходников.</b> Ссылки собираются по {@link SourceSet} — именованному корню исходников (ядро, рендереры
 * FX, Swing, web Java, web JS) со своим синтаксисом и своим шаблоном вызова поиска текста. Каждая ссылка помнит
 * владельца ({@link Reference#owner()}), поэтому этапы S1–S3 проверяют каждый клиент отдельно ({@link #byOwner(List)})
 * и все вместе ({@link #usedKeys(List)}), не меняя сам сбор.</p>
 *
 * <p><b>Что считается ссылкой.</b></p>
 * <ul>
 *   <li>{@link Via#LOOKUP} — литерал сразу в вызове поиска текста (для Java — {@link #JAVA_LOOKUP_CALL}:
 *   {@code UiText/Texts.get/has/template("ключ", …)}). Такой ключ обязан существовать; у {@code get} запоминается
 *   число аргументов, если оно видно статически ({@link JavaSourceScanner#argumentsAfter(String)}).</li>
 *   <li>{@link Via#LITERAL} — любой другой литерал, который либо уже есть в каталоге (ключ из таблицы, поля или
 *   ветки {@code switch}, переданный в поиск позже), либо имеет форму ключа и начинается с пространства имён,
 *   за которое отвечает проверка ({@code keyNamespaces}, например {@code money}, {@code window}). Второй случай
 *   ловит опечатку в ключе, который передаётся не прямо в {@code Texts.get}.</li>
 * </ul>
 *
 * <p>Ключи, которые код строит во время работы, этот класс не видит: тесты добавляют их из таблиц
 * ({@code UiTextCatalogTest.dynamicKeys()}).</p>
 */
public final class TextKeyUsage {

    /** Вызов поиска текста в Java прямо перед литералом-ключом; группа 1 — имя метода. */
    public static final Pattern JAVA_LOOKUP_CALL =
            Pattern.compile("(?:UiText|Texts)\\s*\\.\\s*(get|has|template)\\s*\\(\\s*$");

    /** Форма ключа: латиница, цифры, дефисы и подчёркивания, не меньше двух частей через точку. */
    private static final Pattern KEY_SHAPE = Pattern.compile("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)+");

    /** Как литерал связан с каталогом. */
    public enum Via {
        /** Литерал — первый аргумент вызова поиска текста. */
        LOOKUP,
        /** Литерал вне вызова поиска: ключ из таблицы, поля, ветки {@code switch} или аргумента метода. */
        LITERAL
    }

    /**
     * Именованный набор исходников, в котором ищутся ссылки на ключи.
     *
     * @param owner      владелец для отчётов и проверок по клиентам: {@code core}, {@code ui-fx}, {@code web-js}
     * @param root       папка или файл исходников
     * @param syntax     язык исходников
     * @param lookupCall шаблон кода прямо перед литералом-ключом (должен совпадать с концом кода); группа 1 — имя
     *                   метода, если оно есть
     * @param required   обязан ли корень существовать (корни будущих рендереров клиентов появляются на этапах S1–S3)
     */
    public record SourceSet(String owner, Path root, JavaSourceScanner.Syntax syntax, Pattern lookupCall,
                            boolean required) {

        /** Проверяет обязательные поля. */
        public SourceSet {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(syntax, "syntax");
            Objects.requireNonNull(lookupCall, "lookupCall");
        }

        /**
         * Набор исходников Java с вызовами {@code UiText}/{@code Texts}.
         *
         * @param owner    владелец
         * @param root     папка исходников
         * @param required обязан ли корень существовать
         * @return набор
         */
        public static SourceSet java(String owner, Path root, boolean required) {
            return new SourceSet(owner, root, JavaSourceScanner.Syntax.JAVA, JAVA_LOOKUP_CALL, required);
        }

        /** @return существует ли корень */
        public boolean exists() {
            return root.toFile().exists();
        }
    }

    /**
     * Ссылка на ключ каталога в исходнике.
     *
     * @param owner     владелец набора исходников
     * @param file      файл
     * @param line      строка литерала (с 1)
     * @param key       ключ
     * @param via       как литерал связан с каталогом
     * @param method    метод поиска ({@code get}, {@code has}, {@code template}); пустая строка для {@link Via#LITERAL}
     *                  и для шаблонов без имени метода
     * @param arguments число аргументов подстановок после ключа у {@code get}, если оно видно статически
     */
    public record Reference(String owner, Path file, int line, String key, Via via, String method,
                            OptionalInt arguments) {

        /** @return {@code путь:строка ключ} для сообщений тестов */
        public String location() {
            return file + ":" + line + " " + key;
        }
    }

    private TextKeyUsage() {
    }

    /**
     * Собирает ссылки на ключи во всех наборах исходников.
     *
     * @param sources       наборы исходников; отсутствующий необязательный корень пропускается
     * @param catalogueKeys все ключи каталога
     * @param keyNamespaces первые части ключей, литералы с которыми считаются ссылками, даже если ключа нет в каталоге
     * @param notTextKey    литералы формы ключа, которые ключами каталога не являются (например, имена
     *                      {@code FormatWords}); проверяется только для литералов вне каталога
     * @return ссылки по порядку наборов, файлов и позиций
     */
    public static List<Reference> collect(List<SourceSet> sources, Set<String> catalogueKeys,
                                          Set<String> keyNamespaces, Predicate<String> notTextKey) {
        List<Reference> result = new ArrayList<>();
        for (SourceSet source : sources) {
            for (JavaSourceScanner.Literal literal : JavaSourceScanner.literals(source.root(), source.syntax())) {
                reference(source, literal, catalogueKeys, keyNamespaces, notTextKey).ifPresent(result::add);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Ключи, на которые есть хотя бы одна ссылка.
     *
     * @param references ссылки
     * @return множество ключей в порядке первой ссылки
     */
    public static Set<String> usedKeys(List<Reference> references) {
        Set<String> keys = new LinkedHashSet<>();
        references.forEach(reference -> keys.add(reference.key()));
        return keys;
    }

    /**
     * Используемые ключи по владельцам наборов исходников — основа проверок «каждый клиент ссылается только на
     * существующие ключи» и «одни и те же тексты во всех клиентах».
     *
     * @param references ссылки
     * @return владелец → ключи (порядок владельцев — порядок первых ссылок)
     */
    public static Map<String, Set<String>> byOwner(List<Reference> references) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (Reference reference : references) {
            result.computeIfAbsent(reference.owner(), owner -> new LinkedHashSet<>()).add(reference.key());
        }
        return result;
    }

    /**
     * Ссылки на ключи, которых нет в каталоге.
     *
     * @param references ссылки
     * @param exists     есть ли ключ в каталоге
     * @return {@code путь:строка ключ} каждой такой ссылки
     */
    public static List<String> missing(List<Reference> references, Predicate<String> exists) {
        return references.stream()
                .filter(reference -> !exists.test(reference.key()))
                .map(reference -> reference.owner() + " " + reference.location())
                .toList();
    }

    /**
     * Вызовы {@code get}, у которых число аргументов не совпадает с числом подстановок текста (наибольший номер
     * {@code {n}} плюс один). Вызовы, где число аргументов статически не видно, и отсутствующие ключи пропускаются.
     *
     * @param references ссылки
     * @param template   шаблон текста по ключу
     * @return {@code путь:строка ключ: аргументов a, подстановок p} каждого расхождения
     */
    public static List<String> argumentMismatches(List<Reference> references,
                                                  Function<String, Optional<String>> template) {
        List<String> bad = new ArrayList<>();
        for (Reference reference : checkedCalls(references)) {
            Optional<String> text = template.apply(reference.key());
            if (text.isEmpty()) {
                continue;
            }
            int expected = expectedArguments(text.get());
            if (reference.arguments().getAsInt() != expected) {
                bad.add(reference.owner() + " " + reference.location() + ": arguments "
                        + reference.arguments().getAsInt() + ", placeholders " + expected);
            }
        }
        return bad;
    }

    /**
     * Вызовы {@code get} с числом аргументов, видимым статически.
     *
     * @param references ссылки
     * @return такие ссылки
     */
    public static List<Reference> checkedCalls(List<Reference> references) {
        return references.stream()
                .filter(reference -> reference.via() == Via.LOOKUP && reference.arguments().isPresent())
                .toList();
    }

    /**
     * Сколько аргументов ждёт шаблон.
     *
     * @param template шаблон с {@code {0}}, {@code {1}}, …
     * @return наибольший номер подстановки плюс один; 0 — подстановок нет
     */
    public static int expectedArguments(String template) {
        Set<Integer> placeholders = TextCatalog.placeholders(template);
        return placeholders.isEmpty() ? 0 : Collections.max(placeholders) + 1;
    }

    private static Optional<Reference> reference(SourceSet source, JavaSourceScanner.Literal literal,
                                                 Set<String> catalogueKeys, Set<String> keyNamespaces,
                                                 Predicate<String> notTextKey) {
        String raw = literal.raw();
        Matcher call = source.lookupCall().matcher(literal.codeBefore());
        if (call.find()) {
            String method = call.groupCount() >= 1 && call.group(1) != null ? call.group(1) : "";
            OptionalInt arguments = method.equals("get") || method.isEmpty()
                    ? JavaSourceScanner.argumentsAfter(literal.codeAfter())
                    : OptionalInt.empty();
            return Optional.of(new Reference(source.owner(), literal.file(), literal.line(), raw, Via.LOOKUP,
                    method, arguments));
        }
        boolean known = catalogueKeys.contains(raw);
        boolean namespaced = !known && KEY_SHAPE.matcher(raw).matches()
                && keyNamespaces.contains(raw.substring(0, raw.indexOf('.')))
                && !notTextKey.test(raw);
        if (known || namespaced) {
            return Optional.of(new Reference(source.owner(), literal.file(), literal.line(), raw, Via.LITERAL, "",
                    OptionalInt.empty()));
        }
        return Optional.empty();
    }
}
