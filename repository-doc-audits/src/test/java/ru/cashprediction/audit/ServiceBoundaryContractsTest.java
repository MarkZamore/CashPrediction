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
 * Проверяет исходниковые границы служб планирования, хранения и прогноза и их настоящие точки использования.
 *
 * <p>Читает текущие исходники независимо от собранного core. Исключает комментарии и литералы, допускает
 * изменение пробелов и имён параметров. Это лексические контракты, а не разрешение типов или доказательство
 * поведения: ревизии, дедупликацию, конфликты записи и равенство прогнозов проверяют тесты владельцев служб.
 * Проверки не утверждают готовность удалённого транспорта или завершение S6.</p>
 */
final class ServiceBoundaryContractsTest {
    private static final String CORE = "ru.cashprediction.core.";
    private static final String SOURCE_ROOT = "core/src/main/java/ru/cashprediction/core/";
    private static final List<String> SERVICE_AREAS = List.of("service/plan/", "service/storage/", "forecast/service/");
    private static final Map<String, String> IMPLEMENTATIONS = Map.of(
            "service/plan/LocalPlanCommands.java", "PlanCommands",
            "service/storage/FilePlanStorage.java", "PlanStorage",
            "forecast/service/EngineForecastService.java", "ForecastService");
    private static final List<String> REQUIRED_CONTRACTS = List.of(
            "service/plan/PlanCommands.java", "service/plan/PlanCommand.java",
            "service/plan/PlanCommandRequest.java", "service/plan/PlanCommandResult.java",
            "service/plan/PlanCommandSnapshot.java", "service/plan/PlanCommandEffect.java",
            "service/plan/PlanCommandProblem.java", "service/plan/PlanCommandError.java",
            "service/storage/PlanStorage.java", "service/storage/PlanStorageException.java",
            "forecast/service/ForecastService.java", "forecast/service/ForecastRequest.java",
            "forecast/service/ForecastFailure.java");
    private static final Pattern REFERENCE = Pattern.compile(
            "\\b(?:java|javax|javafx|ru\\.cashprediction|org|com)\\.[\\w$]+(?:\\.[\\w$]+)*(?:\\.\\*)?");
    private static final Pattern NON_VALUE = Pattern.compile(
            "\\b(?:Object|Runnable|Callable|Thread|PlanDocument|PlanRepository|FlowContext|UiPort|AppState"
                    + "|FormSession|Path|File|FileTime|BasicFileAttributes|LocalPlanCommands|FilePlanStorage"
                    + "|EngineForecastService)\\b");
    private static final Map<String, String> SOURCES = new LinkedHashMap<>();

    /** Читает обязательные исходники; отсутствие служб или потребителей не превращается в успешный пропуск. */
    @BeforeAll
    static void readCurrentSources() throws IOException {
        Path directory = RepositoryDocuments.root().resolve(SOURCE_ROOT);
        assertTrue(Files.isDirectory(directory), directory.toString());
        SOURCES.clear();
        try (var files = Files.walk(directory)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                SOURCES.put(directory.relativize(file).toString().replace('\\', '/'),
                        Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        REQUIRED_CONTRACTS.forEach(ServiceBoundaryContractsTest::source);
        IMPLEMENTATIONS.keySet().forEach(ServiceBoundaryContractsTest::source);
        for (String file : List.of("app/flow/FlowContext.java", "app/AppController.java",
                "app/flow/EditFlow.java", "app/flow/ToolsFlow.java", "app/flow/FileFlow.java",
                "app/flow/StartupFlow.java", "app/flow/ExternalChangeGuard.java",
                "app/flow/FormCatalog.java", "app/flow/CoreWindowFactory.java", "app/flow/SessionBridge.java",
                "document/PlanDocument.java", "ui/form/FormContext.java", "ui/forms/plan/GoalCalculatorForm.java")) {
            source(file);
        }
    }

    /** Контракты можно реализовать независимо; закрытый набор предметных команд не закрывает саму службу. */
    @Test
    void servicePortsRemainSeparateFromTheirLocalImplementations() {
        for (String file : List.of("service/plan/PlanCommands.java", "service/storage/PlanStorage.java",
                "forecast/service/ForecastService.java")) {
            String name = typeName(file);
            String text = code(source(file));
            require(text, "\\bpublic\\s+interface\\s+" + name + "\\b", file);
            assertFalse(Pattern.compile("\\bsealed\\s+interface\\s+" + name + "\\b").matcher(text).find(), file);
        }
        IMPLEMENTATIONS.forEach((file, contract) -> require(code(source(file)),
                "\\bclass\\s+" + typeName(file) + "\\b[^{}]*\\bimplements\\b[^{}]*\\b" + contract + "\\b", file));
    }

    /** DTO и ошибки не раскрывают изменяемых владельцев, UI, файловые локаторы, функции или адаптеры. */
    @Test
    void boundaryValuesDoNotDependOnUiOwnersInfrastructureOrCallbacks() {
        List<String> failures = new ArrayList<>();
        SOURCES.forEach((file, raw) -> {
            if (isService(file) && !IMPLEMENTATIONS.containsKey(file)) {
                for (String violation : valueBoundaryViolations(raw, packageName(file))) {
                    failures.add(SOURCE_ROOT + file + ": " + violation);
                }
            }
        });
        assertEquals(List.of(), failures);
    }

    /** Реализации не зависят от интерфейса приложения, транспорта или чужого владельца состояния. */
    @Test
    void serviceImplementationsKeepOwnershipAndUiDependenciesInsideTheirBoundary() {
        List<String> failures = new ArrayList<>();
        SOURCES.forEach((file, raw) -> {
            if (!isService(file)) return;
            for (String reference : references(raw)) {
                boolean forbidden = startsWithAny(reference, List.of(CORE + "app.", CORE + "ui.", CORE + "session.",
                        "ru.cashprediction.fx.", "ru.cashprediction.swing.", "ru.cashprediction.web.",
                        "javafx.", "javax.swing.", "java.awt.", "java.net.", "java.rmi.", "javax.net."));
                if (file.startsWith("service/plan/")) {
                    forbidden |= startsWithAny(reference, List.of(CORE + "io.", CORE + "markdown.", CORE + "service.storage."));
                } else if (file.startsWith("service/storage/")) {
                    forbidden |= startsWithAny(reference, List.of(CORE + "document.", CORE + "service.plan."));
                } else {
                    forbidden |= startsWithAny(reference, List.of(CORE + "document.", CORE + "io.", CORE + "markdown.", CORE + "service."));
                }
                if (forbidden) failures.add(SOURCE_ROOT + file + ": " + reference);
            }
        });
        assertEquals(List.of(), failures);
    }

    /** Запросы несут явные данные, версию и предметную команду; исходы и ожидаемые ошибки остаются типизированными. */
    @Test
    void requestsResultsAndExpectedFailuresStayTyped() {
        String commands = code(source("service/plan/PlanCommands.java"));
        for (String operation : List.of("execute", "preview")) {
            require(commands, "\\bPlanCommandResult\\s+" + operation
                    + "\\s*\\(\\s*PlanCommandRequest\\s+\\w+\\s*\\)", operation);
        }
        String request = recordHeader(source("service/plan/PlanCommandRequest.java"), "PlanCommandRequest");
        for (String type : List.of("UUID", "long", "PlanCommand")) require(request, "\\b" + type + "\\s+\\w+", type);
        require(recordHeader(source("service/plan/PlanCommandResult.java"), "PlanCommandResult"),
                "\\bList\\s*<\\s*PlanCommandProblem\\s*>\\s+\\w+", "PlanCommandResult.problems");
        require(recordHeader(source("service/plan/PlanCommandProblem.java"), "PlanCommandProblem"),
                "\\bPlanCommandError\\s+\\w+", "PlanCommandProblem.code");
        String storage = code(source("service/storage/PlanStorage.java"));
        for (String operation : List.of("write", "rename")) {
            require(storage, "\\bResult\\s*<\\s*Stored\\s*>\\s+" + operation
                    + "\\s*\\([^)]*\\bVersion\\s+\\w+\\s*\\)", "PlanStorage." + operation);
        }
        require(recordHeader(storage, "Problem"), "\\bCode\\s+\\w+", "PlanStorage.Problem.code");
        require(recordHeader(storage, "Result"), "\\bProblem\\s+\\w+", "PlanStorage.Result.problem");
        String forecast = recordHeader(source("forecast/service/ForecastRequest.java"), "ForecastRequest");
        for (String type : List.of("Plan", "WhatIf", "LocalDate", "boolean")) require(forecast, "\\b" + type + "\\s+\\w+", type);
        require(code(source("forecast/service/ForecastService.java")),
                "\\bForecast\\s+calculate\\s*\\(\\s*ForecastRequest\\s+\\w+\\s*\\)", "ForecastService.calculate");
        require(code(source("forecast/service/ForecastFailure.java")), "\\bKind\\s+kind\\s*\\(\\s*\\)", "ForecastFailure.kind");
    }

    /** Потребители берут службу команд из контекста и не создают собственную историю или черновой документ. */
    @Test
    void editAndToolsUseTheCommandPortWithoutMutatingTheDocumentDirectly() {
        for (String file : List.of("app/flow/EditFlow.java", "app/flow/ToolsFlow.java")) {
            String text = code(source(file));
            require(text, "\\.\\s*planCommands\\s*\\(\\s*\\)\\s*\\.\\s*execute\\s*\\(", file);
            assertFalse(Pattern.compile("\\bnew\\s+(?:[\\w$]+\\.)*(?:LocalPlanCommands|PlanDocument)\\s*\\(")
                    .matcher(text).find(), file);
            assertFalse(Pattern.compile("\\.\\s*document\\s*\\(\\s*\\)\\s*\\.\\s*"
                    + "(?:edit|undo|redo|actualize|reconcile|applyWhatIfToPlan|removeOrphanAdjustments)\\s*\\(")
                    .matcher(text).find(), file);
        }
        require(code(source("app/flow/EditFlow.java")),
                "\\bboolean\\s+edit\\s*\\(\\s*String\\s+\\w+\\s*,\\s*String\\s+\\w+\\s*,\\s*PlanCommand\\s+\\w+\\s*\\)",
                "EditFlow.edit accepts PlanCommand");
    }

    /** Проверяет также сохранение и восстановление: прежние лямбды не подходят типизированному EditFlow.edit. */
    @Test
    void allFlowCallsToEditUseDataInsteadOfExecutableChanges() {
        List<String> failures = new ArrayList<>();
        SOURCES.forEach((file, raw) -> {
            if (!file.startsWith("app/flow/")) return;
            String text = code(raw);
            Matcher calls = Pattern.compile("\\.\\s*edits\\s*\\(\\s*\\)\\s*\\.\\s*edit\\s*\\(").matcher(text);
            while (calls.find()) {
                String arguments = enclosed(text, calls.end() - 1, '(', ')');
                if (arguments.contains("->") || arguments.contains("::")) {
                    failures.add(location(file, text, calls.start()) + ": executable argument to EditFlow.edit");
                }
            }
        });
        assertEquals(List.of(), failures);
    }

    /** Файл, запуск и внешняя версия обращаются к внедрённому интерфейсу хранения, а не к своему адаптеру. */
    @Test
    void fileStartupAndExternalChangeFlowsCallTheirInjectedStoragePort() {
        Map<String, List<String>> calls = Map.of(
                "app/flow/FileFlow.java", List.of("list", "read", "write", "rename", "version"),
                "app/flow/StartupFlow.java", List.of("list", "read", "version"),
                "app/flow/ExternalChangeGuard.java", List.of("version"));
        calls.forEach((file, operations) -> {
            String text = code(source(file));
            String field = serviceField(text, "PlanStorage", file);
            require(text, "\\b" + typeName(file) + "\\s*\\([^)]*\\bPlanStorage\\s+\\w+", file + " injection");
            for (String operation : operations) {
                require(text, "\\b" + Pattern.quote(field) + "\\s*\\.\\s*" + operation + "\\s*\\(", file + ": " + operation);
            }
            if (!file.endsWith("/ExternalChangeGuard.java")) {
                assertFalse(Pattern.compile("\\bnew\\s+(?:[\\w$]+\\.)*FilePlanStorage\\s*\\(").matcher(text).find(), file);
            }
        });
    }

    /** Восстановление списка и чистого плана тоже входит в хранение; чистые преобразования имён допустимы. */
    @Test
    void applicationFlowsDoNotCreateARepositoryBehindTheStoragePort() {
        List<String> failures = new ArrayList<>();
        SOURCES.forEach((file, raw) -> {
            if (!file.startsWith("app/flow/")) return;
            String text = code(raw);
            Matcher bypasses = Pattern.compile("\\bnew\\s+(?:[\\w$]+\\.)*PlanRepository\\s*\\(").matcher(text);
            while (bypasses.find()) failures.add(location(file, text, bypasses.start()) + ": new PlanRepository");
        });
        assertEquals(List.of(), failures);
    }

    /** Предпросмотр восстановленного подтверждения не выбирает отдельный документ с расчётом по умолчанию. */
    @Test
    void restoredConfirmationsDoNotConstructAnotherPlanOwner() {
        List<String> failures = new ArrayList<>();
        for (String file : List.of("app/flow/FormCatalog.java", "app/flow/CoreWindowFactory.java")) {
            String text = code(source(file));
            Matcher drafts = Pattern.compile("\\bnew\\s+(?:[\\w$]+\\s*\\.\\s*)*PlanDocument\\s*\\(").matcher(text);
            while (drafts.find()) failures.add(location(file, text, drafts.start()) + ": private PlanDocument preview");
        }
        assertEquals(List.of(), failures);
    }

    /** Документ и калькулятор вызывают внедрённый расчёт; прямой движок остаётся только внутри его адаптера. */
    @Test
    void forecastConsumersUseTheServiceAndOnlyItsAdapterCallsTheEngine() {
        List<String> bypasses = new ArrayList<>();
        SOURCES.forEach((file, raw) -> {
            if (file.equals("forecast/ForecastEngine.java") || file.equals("forecast/service/EngineForecastService.java")) return;
            String text = code(raw);
            Matcher engine = Pattern.compile("\\bForecastEngine\\b").matcher(text);
            while (engine.find()) bypasses.add(location(file, text, engine.start()) + ": ForecastEngine");
        });
        assertEquals(List.of(), bypasses);
        String document = code(source("document/PlanDocument.java"));
        String field = serviceField(document, "ForecastService", "PlanDocument");
        require(document, "\\bPlanDocument\\s*\\([^)]*\\bForecastService\\s+\\w+", "PlanDocument injection");
        require(document, "\\b" + Pattern.quote(field) + "\\s*\\.\\s*calculate\\s*\\(\\s*new\\s+ForecastRequest\\s*\\(",
                "PlanDocument calculation");
        require(recordHeader(source("ui/form/FormContext.java"), "FormContext"), "\\bForecastService\\s+\\w+", "FormContext");
        String goal = code(source("ui/forms/plan/GoalCalculatorForm.java"));
        require(goal, "\\.\\s*forecastService\\s*\\(\\s*\\)\\s*\\.\\s*calculate\\s*\\(\\s*new\\s+ForecastRequest\\s*\\(", "GoalCalculatorForm");
        assertFalse(Pattern.compile("\\bEngineForecastService\\b").matcher(goal).find(), "GoalCalculatorForm");
    }

    /** Сборка приложения передаёт guard тот же интерфейс хранения и явную разрешённую область записи. */
    @Test
    void flowContextAndControllerExposeOwnedServicePorts() {
        String context = code(source("app/flow/FlowContext.java"));
        String controller = code(source("app/AppController.java"));
        for (var service : Map.of("PlanCommands", "planCommands", "PlanStorage", "planStorage").entrySet()) {
            require(context, "\\b" + service.getKey() + "\\s+" + service.getValue() + "\\s*\\(\\s*\\)", "FlowContext." + service.getValue());
            String field = serviceField(controller, service.getKey(), "AppController");
            String body = bodyAfter(controller, "\\b" + service.getKey() + "\\s+" + service.getValue()
                    + "\\s*\\(\\s*\\)\\s*\\{");
            require(body, "\\breturn\\s+(?:this\\s*\\.\\s*)?" + Pattern.quote(field) + "\\s*;",
                    "AppController stable " + service.getValue());
            assertFalse(Pattern.compile("\\bnew\\b").matcher(body).find(), "AppController service accessor constructs a value");
        }
        assertFalse(NON_VALUE.matcher(context).results().anyMatch(match -> Set.of(
                "LocalPlanCommands", "FilePlanStorage", "EngineForecastService").contains(match.group())), "FlowContext adapters");
        String storage = serviceField(controller, "PlanStorage", "AppController");
        String environment = serviceField(controller, "AppEnvironment", "AppController");
        require(controller, sharedScopedGuard(storage, environment), "AppController shared storage and write scope");
        assertFalse(Pattern.compile("\\bnew\\s+ExternalChangeGuard\\s*\\(\\s*\\)").matcher(controller).find(),
                "AppController must not select the compatibility storage default");
    }

    /** Изоляция storage и авторизация пути не заменяются compatibility-конструктором или чужим окружением. */
    @Test
    void controllerGuardMutationCannotDropOrReplaceItsOwnedWriteScope() {
        Pattern construction = Pattern.compile(sharedScopedGuard("ownedStorage", "ownedEnvironment"));
        for (String valid : List.of(
                "new ExternalChangeGuard(ownedStorage, ownedEnvironment.cashMemory())",
                "new ExternalChangeGuard(this.ownedStorage, this.ownedEnvironment.cashMemory())",
                "new ExternalChangeGuard(planStorage(), ownedEnvironment.cashMemory())")) {
            assertTrue(construction.matcher(valid).find(), valid);
        }
        for (String invalid : List.of("new ExternalChangeGuard()", "new ExternalChangeGuard(ownedStorage)",
                "new ExternalChangeGuard(new FilePlanStorage(path), ownedEnvironment.cashMemory())",
                "new ExternalChangeGuard(ownedStorage, otherEnvironment.cashMemory())",
                "new ExternalChangeGuard(ownedStorage, null)",
                "new ExternalChangeGuard(ownedStorage, ownedEnvironment.home())")) {
            assertFalse(construction.matcher(invalid).find(), invalid);
        }
    }

    /** Требует явную CashMemory того же окружения, сохраняя проверку общего экземпляра storage. */
    private static String sharedScopedGuard(String storage, String environment) {
        return "\\bnew\\s+ExternalChangeGuard\\s*\\(\\s*(?:(?:this\\s*\\.\\s*)?"
                + Pattern.quote(storage) + "|planStorage\\s*\\(\\s*\\))\\s*,\\s*(?:this\\s*\\.\\s*)?"
                + Pattern.quote(environment) + "\\s*\\.\\s*cashMemory\\s*\\(\\s*\\)\\s*\\)";
    }

    /** Отрицательные примеры проверяют охрану границы, включая полные имена, static-import и Unicode-escape. */
    @Test
    void sourceGuardsRejectRealCouplingAndIgnoreDocumentationAndLiterals() {
        String own = CORE + "service.plan";
        for (String forbidden : List.of("import ru.cashprediction.core.ui.form.FormSession;",
                "import static ru.cashprediction.core.service.plan.LocalPlanCommands.*;",
                "record X(ru.cashprediction.core.document.PlanDocument document) {}",
                "record X(java.nio.file.Path path) {}", "record X(java.util.function.UnaryOperator<Plan> edit) {}",
                "record X(Object command) {}", "import ru.cashprediction.fx.ui.FxUiPort;")) {
            assertFalse(valueBoundaryViolations(forbidden, own).isEmpty(), forbidden);
        }
        String examples = "// import ru.cashprediction.core.ui.form.FormSession;\r"
                + "/* new PlanRepository(dir).list(); */\n"
                + "class X { String s = \"java.nio.file.Path\"; char c = '\"'; "
                + "String t = \"\"\"\nru.cashprediction.core.document.PlanDocument\n\"\"\"; }";
        assertEquals(List.of(), valueBoundaryViolations(examples, own));
        assertFalse(valueBoundaryViolations("import ru /* comment */ . cashprediction . core . ui . form . FormSession;", own).isEmpty());
        assertFalse(valueBoundaryViolations("import ru.cashprediction.core" + "\\u002e" + "ui.form.FormSession;", own).isEmpty());
        assertEquals(List.of(), valueBoundaryViolations("record X(Plan plan, java.time.LocalDate today) {}", own));
        String callback = code("host.edits().edit(text(\"x,y\"), \"\", plan -> { return plan; });");
        int begin = callback.indexOf(".edit(") + ".edit".length();
        assertTrue(enclosed(callback, begin, '(', ')').contains("->"));
    }

    /** Возвращает обязательный исходник без обращения к старому target или поиску альтернативного корня. */
    private static String source(String relative) {
        String raw = SOURCES.get(relative);
        assertTrue(raw != null, "Required source missing: " + SOURCE_ROOT + relative);
        return raw;
    }

    /** Определяет область служб, включая вновь добавленные DTO и подпакеты. */
    private static boolean isService(String file) {
        return SERVICE_AREAS.stream().anyMatch(file::startsWith);
    }

    /** Возвращает имя верхнего типа из имени файла. */
    private static String typeName(String file) {
        return file.substring(file.lastIndexOf('/') + 1, file.length() - ".java".length());
    }

    /** Возвращает пакет исходника относительно неизменного корня ядра. */
    private static String packageName(String file) {
        return CORE + file.substring(0, file.lastIndexOf('/')).replace('/', '.');
    }

    /** Требует структуру кода, не фиксируя пробелы, локальные имена или весь текст метода. */
    private static void require(String text, String expression, String description) {
        assertTrue(Pattern.compile(expression).matcher(text).find(), description + ": " + expression);
    }

    /** Выбирает имя закрытого стабильного поля с типом интерфейса. */
    private static String serviceField(String text, String type, String file) {
        Matcher field = Pattern.compile("\\bprivate\\s+final\\s+(?:[\\w$]+\\.)*" + Pattern.quote(type)
                + "\\s+(\\w+)\\s*(?=;|=)").matcher(text);
        assertTrue(field.find(), file + ": private final " + type + " field");
        return field.group(1);
    }

    /** Извлекает компоненты record, включая record с параметром типа. */
    private static String recordHeader(String raw, String name) {
        String text = code(raw);
        Matcher record = Pattern.compile("\\brecord\\s+" + Pattern.quote(name) + "\\s*(?:<[^>]+>\\s*)?\\(").matcher(text);
        assertTrue(record.find(), "Required record: " + name);
        return enclosed(text, record.end() - 1, '(', ')');
    }

    /** Извлекает тело метода; служебные проверки потока не меняют контракт стабильной ссылки. */
    private static String bodyAfter(String text, String signature) {
        Matcher declaration = Pattern.compile(signature).matcher(text);
        assertTrue(declaration.find(), "Required method: " + signature);
        return enclosed(text, declaration.end() - 1, '{', '}');
    }

    /** Извлекает сбалансированные аргументы или компоненты после исключения текстов и комментариев. */
    private static String enclosed(String text, int begin, char open, char close) {
        assertTrue(begin >= 0 && begin < text.length() && text.charAt(begin) == open, "Opening delimiter");
        int depth = 1;
        for (int i = begin + 1; i < text.length(); i++) {
            if (text.charAt(i) == open) depth++;
            else if (text.charAt(i) == close && --depth == 0) return text.substring(begin + 1, i);
        }
        throw new AssertionError("Unclosed source delimiter at " + begin);
    }

    /** Указывает строку обнаруженного кода; это место в исходнике, а не результат исполнения. */
    private static String location(String file, String text, int offset) {
        long line = text.substring(0, offset).chars().filter(c -> c == '\n').count() + 1;
        return SOURCE_ROOT + file + ":" + line;
    }

    /** Собирает импорты и явно полные имена; не пытается разрешать короткие имена как компилятор. */
    private static Set<String> references(String raw) {
        Set<String> result = new LinkedHashSet<>();
        Matcher reference = REFERENCE.matcher(code(raw).replaceAll("\\s*\\.\\s*", "."));
        while (reference.find()) result.add(reference.group());
        return result;
    }

    /** Находит зависимости, несовместимые с данными на границе службы. */
    private static List<String> valueBoundaryViolations(String raw, String ownPackage) {
        List<String> failures = new ArrayList<>();
        for (String reference : references(raw)) {
            boolean value = startsWithAny(reference, List.of("java.lang.", "java.time.", "java.math.", "java.util.",
                    CORE + "model.", CORE + "forecast.", CORE + "diagnostics.", ownPackage + "."))
                    || reference.equals(ownPackage);
            if (!value || startsWithAny(reference, List.of("java.util.function.", "java.util.concurrent.", "java.util.prefs."))) {
                failures.add(reference);
            }
        }
        Matcher mutable = NON_VALUE.matcher(code(raw));
        while (mutable.find()) failures.add(mutable.group());
        return failures;
    }

    /** Сравнивает зависимости по границе пакета, а не по произвольному вхождению его имени. */
    private static boolean startsWithAny(String reference, List<String> prefixes) {
        return prefixes.stream().anyMatch(prefix -> reference.startsWith(prefix)
                || reference.equals(prefix.substring(0, prefix.length() - 1)));
    }

    /** Переводит допустимые Unicode-escape до удаления комментариев и литералов, учитывая чётность слешей. */
    private static String translateUnicode(String raw) {
        StringBuilder translated = new StringBuilder();
        int slashes = 0;
        boolean escaped = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean fromEscape = false;
            if (c == '\\' && (slashes % 2 == 0 || escaped)) {
                int end = i + 1;
                while (end < raw.length() && raw.charAt(end) == 'u') end++;
                if (end > i + 1 && end + 4 <= raw.length() && raw.substring(end, end + 4).matches("[0-9a-fA-F]{4}")) {
                    c = (char) Integer.parseInt(raw.substring(end, end + 4), 16);
                    i = end + 3;
                    fromEscape = true;
                }
            }
            translated.append(c);
            slashes = c == '\\' ? slashes + 1 : 0;
            escaped = fromEscape;
        }
        return translated.toString();
    }

    /** Заменяет комментарии, строки, text block и символы пробелами, сохраняя переводы строк для диагностики. */
    private static String code(String raw) {
        String text = translateUnicode(raw);
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
}
