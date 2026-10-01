package ru.cashprediction.core.text;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.io.UncheckedIOException;
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
import javax.tools.ToolProvider;

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
 * <p>Конечные строковые выражения Java разбираются средствами JDK: литералы, локальные переменные,
 * конкатенация, условные выражения и стрелочные ветки switch. Вызовы AlertCatalog и локальные приватные
 * посредники дополняют ключ префиксом. Неизвестные значения не разворачиваются по всему каталогу.
 * Остальные динамические ключи тесты добавляют из таблиц ({@code UiTextCatalogTest.dynamicKeys()}).</p>
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
        LITERAL,
        /** Конкретный ключ выведен из выражения или вызова посредника. */
        DERIVED
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
            List<JavaSourceScanner.Literal> literals = JavaSourceScanner.literals(source.root(), source.syntax())
                    .stream().filter(literal -> !testSource(literal.file())).toList();
            for (JavaSourceScanner.Literal literal : literals) {
                reference(source, literal, catalogueKeys, keyNamespaces, notTextKey).ifPresent(result::add);
            }
            if (source.syntax() == JavaSourceScanner.Syntax.JAVA) {
                derived(source, literals.stream().map(JavaSourceScanner.Literal::file).distinct().toList(),
                        catalogueKeys, result);
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
            if (source.syntax() == JavaSourceScanner.Syntax.JAVA && literal.codeAfter().stripLeading().startsWith("+")) {
                return Optional.empty();
            }
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

    /** Исключает тесты даже при случайно слишком широком корне набора исходников. */
    private static boolean testSource(Path file) {
        String path = "/" + file.toString().replace('\\', '/') + "/";
        return path.contains("/src/test/");
    }

    /** Разбирает только синтаксис Java, не компилируя проект и не загружая его зависимости. */
    private static void derived(SourceSet source, List<Path> files, Set<String> keys, List<Reference> result) {
        if (files.isEmpty()) return;
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("JDK compiler required for text key scanning");
        try (var manager = compiler.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostic -> { },
                    List.of("-proc:none"), null, manager.getJavaFileObjectsFromPaths(files));
            Trees trees = Trees.instance(task);
            for (CompilationUnitTree unit : task.parse()) {
                new DynamicScanner(source.owner(), unit, trees, keys, result).scan(unit, null);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** Посредник с известным номером аргумента и префиксом каталога. */
    private record Forwarder(int argument, String prefix) { }

    /** Консервативный разбор конечных выражений и простых локальных посредников. */
    private static final class DynamicScanner extends TreeScanner<Void, Void> {
        private final String owner;
        private final CompilationUnitTree unit;
        private final Trees trees;
        private final Set<String> keys;
        private final List<Reference> result;
        private Map<String, Set<String>> values = new LinkedHashMap<>();
        private Map<String, Forwarder> forwarders = Map.of();

        private DynamicScanner(String owner, CompilationUnitTree unit, Trees trees,
                               Set<String> keys, List<Reference> result) {
            this.owner = owner;
            this.unit = unit;
            this.trees = trees;
            this.keys = keys;
            this.result = result;
        }

        /** Посредники относятся только к текущему классу; перегрузки не угадываются. */
        @Override public Void visitClass(ClassTree node, Void unused) {
            Map<String, Forwarder> previous = forwarders;
            Map<String, Forwarder> found = new LinkedHashMap<>();
            Set<String> ambiguous = new LinkedHashSet<>();
            Set<String> names = new LinkedHashSet<>();
            for (Tree member : node.getMembers()) {
                if (!(member instanceof MethodTree method)) continue;
                String name = method.getName().toString();
                if (!names.add(name)) ambiguous.add(name);
                Forwarder forwarding = forwarder(method);
                if (forwarding != null) found.put(name, forwarding);
            }
            ambiguous.forEach(found::remove);
            forwarders = found;
            Map<String, Set<String>> previousValues = values;
            values = new LinkedHashMap<>();
            super.visitClass(node, unused);
            values = previousValues;
            forwarders = previous;
            return null;
        }

        /** Локальные значения другого метода не могут служить доказательством ссылки. */
        @Override public Void visitMethod(MethodTree node, Void unused) {
            Map<String, Set<String>> previous = values;
            values = new LinkedHashMap<>();
            super.visitMethod(node, unused);
            values = previous;
            return null;
        }

        /** Область блока изолирует одноимённые локальные переменные. */
        @Override public Void visitBlock(BlockTree node, Void unused) {
            Map<String, Set<String>> previous = values;
            values = new LinkedHashMap<>(values);
            // Изменяемые переменные не считаются константами, в том числе внутри ветвей.
            new TreeScanner<Void, Void>() {
                @Override public Void visitAssignment(AssignmentTree assignment, Void ignored) {
                    values.put(assignment.getVariable().toString(), Set.of());
                    return super.visitAssignment(assignment, ignored);
                }
                @Override public Void visitCompoundAssignment(CompoundAssignmentTree assignment, Void ignored) {
                    values.put(assignment.getVariable().toString(), Set.of());
                    return super.visitCompoundAssignment(assignment, ignored);
                }
            }.scan(node, null);
            super.visitBlock(node, unused);
            values = previous;
            return null;
        }

        /** Запоминает только полностью известное начальное значение локальной переменной. */
        @Override public Void visitVariable(VariableTree node, Void unused) {
            String name = node.getName().toString();
            Set<String> initial = evaluate(node.getInitializer());
            if (!values.containsKey(name)) values.put(name, initial);
            else values.put(name, Set.of());
            return super.visitVariable(node, unused);
        }

        /** Сохраняет источник конкретного вызова, а не место объявления каталога. */
        @Override public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            if (!node.getArguments().isEmpty()) {
                String select = node.getMethodSelect().toString();
                String prefix = alertPrefix(select);
                int argument = 0;
                Forwarder forwarding = forwarders.get(select.startsWith("this.") ? select.substring(5) : select);
                if (prefix == null && forwarding != null) {
                    prefix = forwarding.prefix();
                    argument = forwarding.argument();
                }
                if (prefix != null && argument < node.getArguments().size()) {
                    for (String value : evaluate(node.getArguments().get(argument))) {
                        String key = prefix + value;
                        // Оба поиска AlertCatalog защищены has: отсутствующая необязательная часть не ошибка.
                        if (keys.contains(key)) add(node, key, select);
                        if (keys.contains(key + ".content")) add(node, key + ".content", select);
                    }
                } else if (select.matches("(?:UiText|Texts)\\.(?:get|has|template)")) {
                    for (String key : evaluate(node.getArguments().getFirst())) add(node, key, select);
                } else if (select.endsWith(".status") && node.getArguments().size() >= 2) {
                    // Конкретные ключи сообщений обязательны: отсутствующий ключ тоже попадает в проверку.
                    for (String key : evaluate(node.getArguments().get(1))) {
                        if (key.startsWith("status.msg.") || key.startsWith("status.hint.")) add(node, key, select);
                    }
                }
            }
            return super.visitMethodInvocation(node, unused);
        }

        /** Распознаёт приватный посредник, напрямую передающий параметр в AlertCatalog. */
        private Forwarder forwarder(MethodTree method) {
            if (!method.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.PRIVATE)) return null;
            List<Forwarder> found = new ArrayList<>();
            Set<String> modified = new LinkedHashSet<>();
            new TreeScanner<Void, Void>() {
                @Override public Void visitAssignment(AssignmentTree assignment, Void unused) {
                    modified.add(assignment.getVariable().toString());
                    return super.visitAssignment(assignment, unused);
                }
                @Override public Void visitCompoundAssignment(CompoundAssignmentTree assignment, Void unused) {
                    modified.add(assignment.getVariable().toString());
                    return super.visitCompoundAssignment(assignment, unused);
                }
                @Override public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
                    String prefix = alertPrefix(call.getMethodSelect().toString());
                    if (prefix != null && !call.getArguments().isEmpty()
                            && call.getArguments().getFirst() instanceof IdentifierTree id) {
                        for (int i = 0; i < method.getParameters().size(); i++) {
                            if (method.getParameters().get(i).getName().contentEquals(id.getName())) {
                                found.add(new Forwarder(i, prefix));
                            }
                        }
                    }
                    return super.visitMethodInvocation(call, unused);
                }
            }.scan(method.getBody(), null);
            if (found.size() != 1) return null;
            Forwarder forwarding = found.getFirst();
            return modified.contains(method.getParameters().get(forwarding.argument()).getName().toString())
                    ? null : forwarding;
        }

        /** Вычисляет конечное множество строк; неизвестная часть делает всё выражение неизвестным. */
        private Set<String> evaluate(Tree expression) {
            if (expression instanceof LiteralTree literal && literal.getValue() instanceof String value) {
                return Set.of(value);
            }
            if (expression instanceof IdentifierTree id) return values.getOrDefault(id.getName().toString(), Set.of());
            if (expression instanceof ParenthesizedTree brackets) return evaluate(brackets.getExpression());
            if (expression instanceof ConditionalExpressionTree conditional) {
                return alternatives(evaluate(conditional.getTrueExpression()), evaluate(conditional.getFalseExpression()));
            }
            if (expression instanceof BinaryTree binary && binary.getKind() == Tree.Kind.PLUS) {
                Set<String> left = evaluate(binary.getLeftOperand());
                Set<String> right = evaluate(binary.getRightOperand());
                Set<String> joined = new LinkedHashSet<>();
                for (String a : left) for (String b : right) joined.add(a + b);
                return joined;
            }
            if (expression instanceof SwitchExpressionTree selection) {
                Set<String> all = new LinkedHashSet<>();
                for (CaseTree branch : selection.getCases()) {
                    Set<String> branchValues = evaluate(branch.getBody());
                    if (branchValues.isEmpty()) return Set.of();
                    all.addAll(branchValues);
                }
                return all;
            }
            return Set.of();
        }

        /** Объединяет только полностью известные ветви. */
        private Set<String> alternatives(Set<String> first, Set<String> second) {
            if (first.isEmpty() || second.isEmpty()) return Set.of();
            Set<String> all = new LinkedHashSet<>(first);
            all.addAll(second);
            return all;
        }

        /** Возвращает префикс только известных фабрик сообщений. */
        private String alertPrefix(String select) {
            return switch (select) {
                case "AlertCatalog.error", "ru.cashprediction.core.ui.alert.AlertCatalog.error" -> "err.";
                case "AlertCatalog.info", "ru.cashprediction.core.ui.alert.AlertCatalog.info" -> "info.";
                default -> null;
            };
        }

        /** Добавляет ссылку с номером строки вызова; varargs посредника статически не проверяются. */
        private void add(Tree call, String key, String method) {
            long position = trees.getSourcePositions().getStartPosition(unit, call);
            Path file = Path.of(unit.getSourceFile().toUri());
            int line = (int) unit.getLineMap().getLineNumber(position);
            if (result.stream().noneMatch(reference -> reference.owner().equals(owner)
                    && reference.file().equals(file) && reference.line() == line && reference.key().equals(key))) {
                result.add(new Reference(owner, file, line, key, Via.DERIVED, method, OptionalInt.empty()));
            }
        }
    }
}
