package ru.cashprediction.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Структурные границы текущего ядра: направления зависимостей, закрытое состояние и использование стратегий.
 *
 * <p>Читает настоящие исходники, без зависимости от собранного core и без запуска компилятора. Лексический
 * разбор исключает комментарии и литералы; проверки не доказывают поведение, разрешение типов или готовность
 * к удалённым сервисам. Пробелы сервисных контрактов перечислены в отчёте soa-oop-audit.md.</p>
 */
final class ArchitectureRequirementsTest {
    private static final String CORE = "ru.cashprediction.core.";
    private static final String JAVA_ROOT = "core/src/main/java/";
    private static final Pattern REFERENCE = Pattern.compile(
            "\\b(?:java|javax|javafx|ru\\.cashprediction|org|com)\\.[\\w$]+(?:\\.[\\w$]+)*(?:\\.\\*)?");
    private static final Map<String, String> SOURCES = new LinkedHashMap<>();

    /** Отсутствие каталога или обязательного источника является ошибкой, а не пропуском аудита. */
    @BeforeAll
    static void readCurrentCore() throws IOException {
        Path root = RepositoryDocuments.root();
        Path directory = root.resolve(JAVA_ROOT);
        assertTrue(Files.isDirectory(directory), directory.toString());
        SOURCES.clear();
        try (var files = Files.walk(directory)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                SOURCES.put(root.relativize(file).toString().replace('\\', '/'),
                        Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        assertFalse(SOURCES.isEmpty(), "Core source inventory must not be empty");
        source("model/Plan.java");
        source("document/PlanDocument.java");
        source("session/SessionStore.java");
    }

    /** Всё ядро, включая новые пакеты, не зависит от трёх клиентов, графических API или сторонних библиотек. */
    @Test
    void coreDoesNotDependOnRenderersToolkitsOrExternalLibraries() {
        List<String> failures = new ArrayList<>();
        SOURCES.forEach((path, raw) -> {
            for (String reference : references(raw)) {
                boolean standard = reference.startsWith("java.") || reference.startsWith("javax.xml.")
                        || reference.startsWith("javax.crypto.")
                        || reference.startsWith("org.w3c.dom.") || reference.startsWith("org.xml.sax.");
                if (toolkit(reference) || !(standard || reference.startsWith(CORE)
                        || reference.equals("ru.cashprediction.core"))) {
                    failures.add(path + ": " + reference);
                }
            }
            if (Pattern.compile("\\brequires\\s+(?:transitive\\s+|static\\s+)*java\\.desktop\\b")
                    .matcher(code(raw)).find()) failures.add(path + ": requires java.desktop");
        });
        assertEquals(List.of(), failures);
    }

    /** Значения, диагностика, общие форматы и расчёт не обращаются к состоянию приложения, UI или адаптерам ввода. */
    @Test
    void domainAndCalculationDependenciesPointDownward() {
        Map<String, Set<String>> allowed = Map.of(
                "model", Set.of("model", "format", "text", "util"),
                "diagnostics", Set.of("diagnostics", "model", "format", "text", "util"),
                "format", Set.of("format", "text", "util"),
                "recurrence", Set.of("recurrence", "model", "text", "util"),
                "forecast", Set.of("forecast", "diagnostics", "model", "recurrence", "text", "util"));
        allowed.forEach((area, dependencies) -> {
            Map<String, String> files = area(area + "/");
            assertFalse(files.isEmpty(), area);
            files.forEach((path, raw) -> assertEquals(List.of(), boundaryViolations(raw, dependencies), path));
        });
    }

    /** Сеанс зависит от абстракций; форматирование снимка не получает доступ к диску, реестру или клиентам. */
    @Test
    void sessionOrchestrationAndCodecsStayIndependentOfStorageAdapters() {
        Map<String, String> session = area("session/");
        assertFalse(session.isEmpty());
        session.forEach((path, raw) -> {
            if (!path.substring((JAVA_ROOT + "ru/cashprediction/core/session/").length()).contains("/")) {
                for (String reference : references(raw)) {
                    assertFalse(reference.startsWith(CORE + "session.store.")
                            || reference.startsWith(CORE + "session.codec.")
                            || reference.startsWith(CORE + "app.") || reference.startsWith(CORE + "ui."),
                            path + ": " + reference);
                }
            }
        });
        Map<String, String> codecs = area("session/codec/");
        assertFalse(codecs.isEmpty());
        codecs.forEach((path, raw) -> {
            assertEquals(List.of(), boundaryViolations(raw, Set.of("session", "json", "text", "format")), path);
            for (String reference : references(raw)) {
                assertFalse(reference.startsWith(CORE + "session.store."), path + ": " + reference);
            }
        });
    }

    /** Срезы для UI не несут живой документ, рекордер, репозиторий или порт с возможностью изменения. */
    @Test
    void readModelsDoNotExposeMutableOwnersOrAdapters() {
        for (String file : List.of("app/AppState.java", "app/DocumentView.java")) {
            String raw = source(file);
            assertTrue(code(raw).contains("public record "), file);
            assertFalse(Pattern.compile("\\b(?:PlanDocument|SessionRecorder|PlanRepository|UiPort|FlowContext)\\b")
                    .matcher(code(raw)).find(), file);
        }
    }

    /** Закрытые поля защищают историю и состояние формы; публичные примитивные константы остаются допустимыми. */
    @Test
    void mutableOwnersKeepTheirFieldsPrivate() {
        for (String file : List.of("document/PlanDocument.java", "ui/form/FormSession.java")) {
            String type = file.substring(file.lastIndexOf('/') + 1, file.length() - 5);
            List<String> fields = fields(source(file), type);
            assertFalse(fields.isEmpty(), file);
            assertEquals(List.of(), exposedFields(fields), file);
        }
    }

    /** Реальная точка использования стратегии получает интерфейс и вызывает его, не выбирая конкретный класс. */
    @Test
    void recorderAndFormsConsumeInjectedStrategies() {
        String recorder = compact(source("session/SessionRecorder.java"));
        assertTrue(recorder.contains("privatefinalList<SessionStore>stores;"));
        assertTrue(recorder.contains("List<SessionStore>stores,UiExecutorui,SnapshotSourcesource"));
        assertTrue(recorder.contains("store.save(snapshot)"));
        assertTrue(recorder.contains("store.markDirty(current)"));
        String form = compact(source("ui/form/FormSession.java"));
        assertTrue(form.contains("privatefinalFormLogiclogic;"));
        assertTrue(form.contains("FormLogiclogic,FormContextcontext,Hosthost"));
        for (String call : List.of("logic.spec(", "logic.defaults(", "logic.evaluate(", "logic.onButton(")) {
            assertTrue(form.contains(call), call);
        }
        for (String reference : references(source("ui/form/FormSession.java"))) {
            assertFalse(reference.startsWith(CORE + "ui.forms."), reference);
        }
        for (String contract : List.of("session/SessionStore.java", "session/codec/SnapshotCodec.java",
                "session/store/RegistryBackend.java", "ui/form/FormLogic.java", "app/UiPort.java")) {
            String name = contract.substring(contract.lastIndexOf('/') + 1, contract.length() - 5);
            String declaration = code(source(contract));
            assertTrue(Pattern.compile("\\bpublic\\s+interface\\s+" + name + "\\b").matcher(declaration).find(), contract);
            assertFalse(Pattern.compile("\\bsealed\\s+interface\\b").matcher(declaration).find(), contract);
        }
    }

    /** Реестр хранит интерфейс бэкенда, а две различные реализации реализуют тот же контракт. */
    @Test
    void registryBackendIsAnActualReplaceableCollaborator() {
        String registry = compact(source("session/store/RegistrySessionStore.java"));
        assertTrue(registry.contains("privatefinalRegistryBackendbackend;"));
        assertTrue(registry.contains("RegistrySessionStore(RegistryBackendbackend,Stringclient"));
        for (String call : List.of("backend.get(", "backend.put(", "backend.flush(")) {
            assertTrue(registry.contains(call), call);
        }
        for (String implementation : List.of("PreferencesRegistryBackend", "InMemoryRegistryBackend")) {
            assertTrue(Pattern.compile("\\bclass\\s+" + implementation + "\\s+implements\\s+RegistryBackend\\b")
                    .matcher(code(source("session/store/" + implementation + ".java"))).find(), implementation);
        }
        assertEquals(List.of(), boundaryViolations(source("session/store/RegistryBackend.java"), Set.of("session")));
    }

    /** Закрытый набор повторов имеет реальные варианты и полный диспетчер; горизонт вызывается полиморфно. */
    @Test
    void sealedRecurrenceHasCompleteDomainDispatch() {
        String recurrence = code(source("model/Recurrence.java"));
        Matcher permits = Pattern.compile("sealed\\s+interface\\s+Recurrence\\s+permits\\s+([^\\{]+)\\{")
                .matcher(recurrence);
        assertTrue(permits.find());
        Set<String> variants = new LinkedHashSet<>();
        for (String type : permits.group(1).split(",")) variants.add(type.strip());
        assertEquals(Set.of("Recurrence.Monthly", "Recurrence.Weekly", "Recurrence.EveryNDays", "Recurrence.Yearly"), variants);
        for (String variant : variants) {
            assertTrue(Pattern.compile("\\brecord\\s+" + variant.substring(11)
                    + "\\s*\\([^)]*\\)\\s+implements\\s+Recurrence\\b").matcher(recurrence).find(), variant);
        }
        String dispatch = blockAfter(code(source("recurrence/OccurrenceGenerator.java")),
                "switch\\s*\\(\\s*rule\\.recurrence\\(\\)\\s*\\)");
        Set<String> cases = new LinkedHashSet<>();
        Matcher branches = Pattern.compile("\\bcase\\s+(Recurrence\\.\\w+)\\b").matcher(dispatch);
        while (branches.find()) cases.add(branches.group(1));
        assertEquals(variants, cases);
        assertFalse(Pattern.compile("\\bdefault\\b").matcher(dispatch).find());
        assertTrue(compact(source("model/Plan.java")).contains("horizon.endDate(startDate)"));
    }

    /** Списки плана копируются на входе; массив результата защищён и на входе, и при чтении. */
    @Test
    void immutableSnapshotsProtectCollectionOwnership() {
        String plan = compact(source("model/Plan.java"));
        for (String field : List.of("rules", "oneTimes", "adjustments", "rawBlocks")) {
            assertTrue(plan.contains(field + "=" + field + "==null?List.of():List.copyOf(" + field + ");"), field);
        }
        String forecast = compact(source("forecast/Forecast.java"));
        assertTrue(forecast.contains("dailyBalance=Objects.requireNonNull(dailyBalance,).clone();"));
        assertTrue(forecast.contains("publiclong[]dailyBalance(){returndailyBalance.clone();}"));
        assertTrue(compact(source("app/DocumentView.java")).contains("List.copyOf(loadDiagnostics)"));
    }

    /** Отрицательные примеры доказывают обнаружение импортов, полных имён и утечки поля, а не наличие названий. */
    @Test
    void guardsRejectStructuralMutationsAndIgnoreQuotedExamples() {
        Set<String> allowed = Set.of("diagnostics", "model", "text", "format", "util");
        for (String leak : List.of("import ru.cashprediction.core.app.AppState;",
                "import static ru.cashprediction.core.app.AppClock.*;",
                "import ru.cashprediction.core.io.CashMemoryLayout;",
                "import static ru.cashprediction.core.io.CashMemoryLayout.isReservedPlanName;",
                "class X { boolean reserved = ru.cashprediction.core.io.CashMemoryLayout.isReservedPlanName(null); }",
                "class X { ru.cashprediction.core.io.PlanRepository repository; }",
                "import java.nio.file.Files;", "import java.util.prefs.Preferences;")) {
            assertFalse(boundaryViolations(leak, allowed).isEmpty(), leak);
        }
        String quoted = "// import java.nio.file.Files;\n/* ru.cashprediction.core.app.AppState */\n"
                + "class X { String s = \"java.nio.file.Files\"; char c = '\"'; "
                + "String t = \"\"\"\nru.cashprediction.core.io.PlanRepository\n\"\"\"; }";
        assertEquals(List.of(), boundaryViolations(quoted, allowed));
        assertEquals(1, exposedFields(fields("class X { public Object state; private Object hidden; }", "X")).size());
        assertEquals(1, exposedFields(fields("class X { Object state; void f() { Object local; } }", "X")).size());
        assertEquals(List.of(), exposedFields(fields("class X { private Object state; public Object value() { return state; } }", "X")));
        assertTrue(toolkit(references("import javax /* example */ . swing . *;").iterator().next()));
        assertTrue(toolkit(references("import java" + "\\u002e" + "awt.Color;").iterator().next()));
        assertEquals(List.of("java.nio.file.Files"), boundaryViolations("// example\rimport java.nio.file.Files;", allowed));
        assertEquals(List.of(), boundaryViolations("class X { String s = \"" + "\\\\u0022" + "java.nio.file.Files\"; }", allowed));
    }

    /** Возвращает обязательный исходник текущего ядра. */
    private static String source(String relative) {
        String path = JAVA_ROOT + "ru/cashprediction/core/" + relative;
        String raw = SOURCES.get(path);
        assertTrue(raw != null, "Required source missing: " + path);
        return raw;
    }

    /** Выбирает все файлы пакета вместе с подпакетами. */
    private static Map<String, String> area(String relative) {
        Map<String, String> result = new LinkedHashMap<>();
        String prefix = JAVA_ROOT + "ru/cashprediction/core/" + relative;
        SOURCES.forEach((path, raw) -> { if (path.startsWith(prefix)) result.put(path, raw); });
        return result;
    }

    /** Собирает импорты и полные имена, сохраняя границы пакетов и игнорируя примеры в текстах. */
    private static Set<String> references(String raw) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = REFERENCE.matcher(code(raw).replaceAll("\\s*\\.\\s*", "."));
        while (matcher.find()) result.add(matcher.group());
        return result;
    }

    /** Находит прямые нарушения заданной границы; транзитивные циклы этим методом не доказываются. */
    private static List<String> boundaryViolations(String raw, Set<String> allowedCore) {
        List<String> failures = new ArrayList<>();
        for (String reference : references(raw)) {
            if (reference.startsWith(CORE) && !allowedCore.contains(reference.substring(CORE.length()).split("\\.")[0])
                    || toolkit(reference) || reference.startsWith("java.nio.file.")
                    || reference.startsWith("java.util.prefs.") || reference.startsWith("java.net.")
                    || reference.matches("java\\.io\\.(?:File|FileInputStream|FileOutputStream|FileReader|FileWriter|RandomAccessFile)(?:\\..*)?")
                    || reference.startsWith("java.lang.Process")) failures.add(reference);
        }
        return failures;
    }

    /** Проверяет API графических клиентов, включая часто забываемый ImageIO. */
    private static boolean toolkit(String reference) {
        return reference.startsWith("javafx.") || reference.startsWith("java.awt.")
                || reference.startsWith("javax.swing.") || reference.startsWith("javax.imageio.")
                || reference.startsWith("java.beans.") || reference.startsWith("javax.sound.")
                || reference.startsWith("ru.cashprediction.fx.") || reference.startsWith("ru.cashprediction.swing.")
                || reference.startsWith("ru.cashprediction.web.");
    }

    /** Оставляет только код, сначала обрабатывая допустимые Unicode-escape по чётности обратных слешей. */
    private static String code(String raw) {
        StringBuilder translated = new StringBuilder();
        int slashes = 0;
        boolean escaped = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean fromEscape = false;
            if (c == '\\' && (slashes % 2 == 0 || escaped)) {
                int end = i + 1;
                while (end < raw.length() && raw.charAt(end) == 'u') end++;
                if (end > i + 1 && end + 4 <= raw.length()
                        && raw.substring(end, end + 4).matches("[0-9a-fA-F]{4}")) {
                    c = (char) Integer.parseInt(raw.substring(end, end + 4), 16);
                    i = end + 3;
                    fromEscape = true;
                }
            }
            translated.append(c);
            slashes = c == '\\' ? slashes + 1 : 0;
            escaped = fromEscape;
        }
        String text = translated.toString();
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length();) {
            int end = i;
            if (text.startsWith("//", i)) {
                end = i + 2;
                while (end < text.length() && text.charAt(end) != '\n' && text.charAt(end) != '\r') end++;
            } else if (text.startsWith("/*", i)) {
                end = text.indexOf("*/", i + 2);
                end = end < 0 ? text.length() : end + 2;
            } else if (text.charAt(i) == '"' || text.charAt(i) == '\'') {
                char quote = text.charAt(i);
                boolean block = text.startsWith("\"\"\"", i);
                end = i + (block ? 3 : 1);
                while (end < text.length()) {
                    if (text.charAt(end) == '\\') end = Math.min(text.length(), end + 2);
                    else if (block && text.startsWith("\"\"\"", end)) { end += 3; break; }
                    else if (!block && text.charAt(end) == quote) { end++; break; }
                    else end++;
                }
            }
            if (end > i) {
                for (; i < end; i++) result.append(text.charAt(i) == '\n' || text.charAt(i) == '\r' ? text.charAt(i) : ' ');
            } else result.append(text.charAt(i++));
        }
        return result.toString();
    }

    /** Убирает пробелы после исключения комментариев и литералов. */
    private static String compact(String raw) {
        return code(raw).replaceAll("\\s+", "");
    }

    /** Извлекает сбалансированное тело после обязательной сигнатуры. */
    private static String blockAfter(String text, String signature) {
        Matcher match = Pattern.compile(signature).matcher(text);
        assertTrue(match.find(), signature);
        int begin = text.indexOf('{', match.end());
        assertTrue(begin >= 0, signature);
        int depth = 1;
        for (int end = begin + 1; end < text.length(); end++) {
            if (text.charAt(end) == '{') depth++;
            if (text.charAt(end) == '}' && --depth == 0) return text.substring(begin + 1, end);
        }
        throw new AssertionError("Unclosed source block: " + signature);
    }

    /** Извлекает поля выбранного владельца, исключая методы, вложенные классы и локальные переменные. */
    private static List<String> fields(String raw, String type) {
        String body = blockAfter(code(raw), "\\b(?:class|record)\\s+" + Pattern.quote(type) + "\\b");
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = 0;
        boolean initializer = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '{') {
                if (depth == 0) initializer = body.substring(start, i).contains("=");
                depth++;
            } else if (c == '}') {
                if (--depth == 0 && !initializer) start = i + 1;
            } else if (c == ';' && depth == 0) {
                String member = body.substring(start, i + 1).strip();
                int equal = member.indexOf('=');
                int paren = member.indexOf('(');
                if (paren < 0 || equal >= 0 && equal < paren) result.add(member);
                start = i + 1;
            }
        }
        return result;
    }

    /** Считает утечкой любое неприватное поле кроме неизменяемых скалярных констант. */
    private static List<String> exposedFields(List<String> fields) {
        return fields.stream().filter(field -> !Pattern.compile("\\bprivate\\b").matcher(field).find()
                && !Pattern.compile("\\bpublic\\s+static\\s+final\\s+(?:int|long|boolean|String)\\b")
                .matcher(field).find()).toList();
    }
}
