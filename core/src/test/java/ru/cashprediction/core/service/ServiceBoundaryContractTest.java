package ru.cashprediction.core.service;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.util.*;
import ru.cashprediction.web.fixture.ClientControl;
import ru.cashprediction.core.forecast.service.*;
import ru.cashprediction.core.session.RecoverySnapshots;
import ru.cashprediction.core.update.lifecycle.UpdateSessionLifecycle;
import ru.cashprediction.core.update.model.UpdateProblem;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.service.plan.*;
import ru.cashprediction.core.service.storage.*;

/** Проверяет отсутствие клиентских типов в выбранных портах и достижимых данных, не всю SOA. */
class ServiceBoundaryContractTest {
    // Локальные адаптеры намеренно исключены: контролируемый collaborator не является DTO порта.
    private static final List<Class<?>> BOUNDARIES = List.of(
            PlanCommands.class, PlanCommand.class, PlanCommandRequest.class, PlanCommandResult.class,
            PlanCommandSnapshot.class, PlanCommandProblem.class, PlanCommandError.class,
            PlanCommandEffect.class, PlanStorage.class, PlanStorageException.class,
            ForecastService.class, ForecastRequest.class, ForecastFailure.class,
            RecoverySnapshots.class, RecoverySnapshots.Problem.class, RecoverySnapshots.Result.class,
            UpdateSessionLifecycle.StartupResult.class, UpdateSessionLifecycle.Status.class, UpdateProblem.class);

    /** Реальные текущие контракты, вложенные команды и достижимые DTO не раскрывают клиент. */
    @Test void currentServiceContractsDoNotExposeClientTypes() {
        for (Class<?> boundary : BOUNDARIES)
            assertEquals(List.of(), leaks(boundary), boundary.getName());
    }

    /** Настоящий Swing тип обнаруживается reflection без импорта toolkit в модуль core и без создания UI. */
    @Test void realSwingTypeIsRejectedWithoutCompileTimeToolkitDependency() throws Exception {
        Class<?> swing = Class.forName("javax.swing.JButton");
        assertFalse(leaks(swing).isEmpty());
        assertTrue(forbidden(swing.getName()));
    }

    /** Реальный скомпилированный descriptor ядра сохраняет архитектуру даже при classpath запуске JUnit. */
    @Test void compiledCoreDescriptorDoesNotRequireClientToolkits() throws Exception {
        var origin = RecoverySnapshots.class.getProtectionDomain().getCodeSource();
        assertNotNull(origin, "compiled core location required");
        var location = java.nio.file.Path.of(origin.getLocation().toURI());
        var descriptor = java.lang.module.ModuleFinder.of(location).find("ru.cashprediction.core")
                .orElseThrow(() -> new AssertionError("compiled core module-info.class required")).descriptor();
        for (var dependency : descriptor.requires()) {
            String name = dependency.name();
            assertFalse(name.equals("java.desktop") || name.startsWith("javafx.")
                    || name.startsWith("ru.cashprediction.fx") || name.startsWith("ru.cashprediction.swing")
                    || name.startsWith("ru.cashprediction.web"), name);
        }
    }

    /** Границы имени пакета исключают совпадение с похожим, но иным пакетом. */
    @Test void clientPackagePolicyHasPositiveAndNegativeControls() {
        for (String name : List.of("javafx.scene.Node", "javax.swing.JButton", "ru.cashprediction.web.fixture.ClientControl", "java.awt.Window",
                "ru.cashprediction.fx.FxMain", "ru.cashprediction.swing.SwingMain",
                "ru.cashprediction.web.ui.WebUiPort")) assertTrue(forbidden(name), name);
        for (String name : List.of("java.lang.String", "ru.cashprediction.core.web.reconnect.ReconnectCredential",
                "ru.cashprediction.core.ui.UiText", "javafxish.Value", "ru.cashprediction.webish.Dto"))
            assertFalse(forbidden(name), name);
    }

    /** Допустимые generic, циклические DTO и массивы не дают ложного нарушения. */
    @Test void neutralRecursiveGenericDtoIsAccepted() {
        assertEquals(List.of(), leaks(Neutral.class));
    }

    /** Обычный параметр public метода также является контрактом. */
    @Test void directParameterIsRejected() { rejects(BadParameter.class, "parameter"); }

    /** Вложенные generic и wildcard return не скрывают Swing тип за List. */
    @Test void nestedGenericReturnIsRejected() { rejects(BadReturn.class, "return"); }

    /** Private поле DTO проверяется без доступа к его значению или создания UI. */
    @Test void dtoFieldIsRejected() { rejects(BadField.class, "field"); }

    /** Generic массив не скрывает запрещённый аргумент компонента. */
    @Test void genericArrayIsRejected() { rejects(BadArray.class, "generic-array"); }

    /** Ограничение переменной типа класса проверяется отдельно от её использования. */
    @Test void classTypeBoundIsRejected() { rejects(BadClassBound.class, "type-variable"); }

    /** Generic параметр метода сохраняет bound даже при стирании return до Object. */
    @Test void methodTypeBoundIsRejected() { rejects(BadMethodBound.class, "type-variable"); }

    /** Наследуемая public сигнатура не теряется при обходе getMethods. */
    @Test void inheritedSignatureIsRejected() { rejects(InheritedBad.class, "return"); }

    /** Публичный конструктор DTO входит в проверяемый контракт. */
    @Test void publicConstructorIsRejected() { rejects(BadConstructor.class, "constructor"); }

    /** Sealed варианты обходятся без изменения sealed предметной модели. */
    @Test void permittedDtoIsRejected() { rejects(BadSealed.class, "field"); }

    /** Generic throws проверяет достижимые поля предметного исключения. */
    @Test void exceptionDtoIsRejected() { rejects(BadThrows.class, "throws"); }

    /** Сопоставляет обнаружение с конкретным запрещённым типом и видом сигнатуры. */
    private static void rejects(Class<?> type, String location) {
        List<String> found = leaks(type);
        assertTrue(found.stream().anyMatch(value -> value.contains(location)
                && value.contains("ru.cashprediction.web.fixture.ClientControl")), () -> type.getName() + ": " + found);
    }

    /** Собирает диагностические пути; состояние обхода принадлежит одному вызову. */
    private static List<String> leaks(Class<?> root) {
        List<String> result = new ArrayList<>();
        visit(root, root.getName(), new HashSet<>(), result);
        return List.copyOf(result);
    }

    /** Проверяет полные package prefixes, не произвольные подстроки. */
    private static boolean forbidden(String name) {
        return List.of("javafx.", "javax.swing.", "java.awt.", "ru.cashprediction.fx.",
                "ru.cashprediction.swing.", "ru.cashprediction.web.").stream().anyMatch(name::startsWith);
    }

    /** Обходит все формы reflection Type; неизвестная форма не считается безопасной. */
    private static void visit(Type type, String path, Set<Type> seen, List<String> result) {
        if (type == null || !seen.add(type)) return;
        if (type instanceof Class<?> value) {
            if (value.isArray()) { visit(value.getComponentType(), path + " array", seen, result); return; }
            if (forbidden(value.getName())) { result.add(path + " -> " + value.getName()); return; }
            // Внутренности JDK не являются DTO приложения; generic аргументы обрабатываются отдельно.
            if (!value.getName().startsWith("ru.cashprediction.core.")) return;
            for (TypeVariable<?> variable : value.getTypeParameters())
                visit(variable, path + " type-variable", seen, result);
            visit(value.getGenericSuperclass(), path + " superclass", seen, result);
            for (Type parent : value.getGenericInterfaces()) visit(parent, path + " interface", seen, result);
            for (Class<?> nested : value.getDeclaredClasses())
                if (Modifier.isPublic(nested.getModifiers())) visit(nested, path + " nested", seen, result);
            if (value.isSealed()) for (Class<?> variant : value.getPermittedSubclasses())
                visit(variant, path + " permitted", seen, result);
            for (Field field : value.getDeclaredFields())
                if (!field.isSynthetic() && (!Modifier.isStatic(field.getModifiers())
                        || Modifier.isPublic(field.getModifiers())))
                    visit(field.getGenericType(), path + " field " + field.getName(), seen, result);
            if (value.isRecord()) for (RecordComponent component : value.getRecordComponents())
                visit(component.getGenericType(), path + " record " + component.getName(), seen, result);
            for (Constructor<?> constructor : value.getConstructors()) {
                for (TypeVariable<?> variable : constructor.getTypeParameters())
                    visit(variable, path + " constructor type-variable", seen, result);
                for (Type parameter : constructor.getGenericParameterTypes())
                    visit(parameter, path + " constructor parameter", seen, result);
                for (Type exception : constructor.getGenericExceptionTypes())
                    visit(exception, path + " constructor throws", seen, result);
            }
            for (Method method : value.getMethods()) {
                String member = path + " method " + method.getName();
                for (TypeVariable<?> variable : method.getTypeParameters())
                    visit(variable, member + " type-variable", seen, result);
                visit(method.getGenericReturnType(), member + " return", seen, result);
                for (Type parameter : method.getGenericParameterTypes())
                    visit(parameter, member + " parameter", seen, result);
                for (Type exception : method.getGenericExceptionTypes())
                    visit(exception, member + " throws", seen, result);
            }
        } else if (type instanceof ParameterizedType value) {
            visit(value.getRawType(), path + " raw", seen, result);
            visit(value.getOwnerType(), path + " owner", seen, result);
            for (Type argument : value.getActualTypeArguments()) visit(argument, path + " argument", seen, result);
        } else if (type instanceof GenericArrayType value) {
            visit(value.getGenericComponentType(), path + " generic-array", seen, result);
        } else if (type instanceof WildcardType value) {
            for (Type bound : value.getUpperBounds()) visit(bound, path + " upper-bound", seen, result);
            for (Type bound : value.getLowerBounds()) visit(bound, path + " lower-bound", seen, result);
        } else if (type instanceof TypeVariable<?> value) {
            for (Type bound : value.getBounds()) visit(bound, path + " bound", seen, result);
        } else throw new AssertionError("Unrecognized reflection Type: " + type);
    }

    /** Нейтральные рекурсивные данные для проверки отсутствия ложных срабатываний. */
    private record Neutral<T extends Number>(List<? extends T[]> values, Neutral<T> next) { }
    /** Недопустимый обычный параметр. */
    private interface BadParameter { /** Принимает клиентский тип. */ void accept(ClientControl value); }
    /** Недопустимый generic return. */
    private interface BadReturn { /** Возвращает скрытый generic клиент. */ Map<String, List<? super ClientControl[]>> values(); }
    /** Недопустимое приватное поле DTO. */
    private static class BadField { private List<ClientControl> value; }
    /** Недопустимый аргумент компонента generic массива DTO. */
    private static class BadArray { private List<ClientControl>[] value; }
    /** Недопустимый bound переменной типа класса. */
    private static class BadClassBound<T extends ClientControl> { }
    /** Недопустимый bound параметра метода. */
    private interface BadMethodBound { /** Выдаёт клиент через bound. */ <T extends ClientControl> T value(); }
    /** Контроль унаследованного метода. */
    private interface InheritedBad extends BadReturn { }
    /** Контроль public конструктора. */
    private static class BadConstructor { /** Принимает клиентский тип. */ public BadConstructor(ClientControl value) { } }
    /** Контроль sealed границы. */
    private sealed interface BadSealed permits BadVariant { }
    /** Недопустимый вариант sealed DTO. */
    private record BadVariant(ClientControl value) implements BadSealed { }
    /** Недопустимые данные предметного исключения. */
    private static class BadException extends Exception { private ClientControl value; }
    /** Контроль исключения в сигнатуре порта. */
    private interface BadThrows { /** Объявляет исключение с клиентским полем. */ void run() throws BadException; }
}
