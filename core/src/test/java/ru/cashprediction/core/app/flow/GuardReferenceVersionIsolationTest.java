package ru.cashprediction.core.app.flow;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.service.storage.FakePlanStorage;
import ru.cashprediction.core.service.storage.FilePlanStorage;
import ru.cashprediction.core.service.storage.PlanStorage;

/** Проверяет владение парой reference/version и подстановку второго backend без файловых команд. */
class GuardReferenceVersionIsolationTest {
    /** Невалидный второй аргумент не должен привязать чужую сохранённую версию к новой ссылке. */
    @Test void invalidRememberCannotRebindForeignVersion() {
        var guard = new ExternalChangeGuard(new FakePlanStorage());
        var first = new PlanStorage.Reference("backend:A");
        var second = new PlanStorage.Reference("backend:B");
        var firstVersion = new PlanStorage.Version("opaque:A");
        var fallback = new PlanStorage.Version("opaque:fallback");
        guard.remember(first, firstVersion);
        assertThrows(NullPointerException.class, () -> guard.remember(second, null));
        assertEquals(fallback, guard.expectedVersion(second, fallback),
                "failed remember must not assign the previous object's version to another reference");
        assertEquals(firstVersion, guard.expectedVersion(first, fallback));
    }

    /** Отказ null reference сохраняет пару; валидная замена и прежний null Path сбрасывают её ожидаемо. */
    @Test void nullReferencePreservesPairAndValidReplacementAndNullPathKeepTheirSemantics() {
        var guard = new ExternalChangeGuard(new FakePlanStorage());
        var first = new PlanStorage.Reference("backend:A");
        var second = new PlanStorage.Reference("backend:B");
        var firstVersion = new PlanStorage.Version("opaque:A");
        var secondVersion = new PlanStorage.Version("opaque:B");
        var fallback = new PlanStorage.Version("opaque:fallback");
        guard.remember(first, firstVersion);
        assertThrows(NullPointerException.class, () -> guard.remember(null, secondVersion));
        assertEquals(firstVersion, guard.expectedVersion(first, fallback));
        assertEquals(fallback, guard.expectedVersion(second, fallback));
        guard.remember(second, secondVersion);
        assertEquals(fallback, guard.expectedVersion(first, fallback));
        assertEquals(secondVersion, guard.expectedVersion(second, fallback));
        guard.remember((Path) null);
        assertEquals(fallback, guard.expectedVersion(second, fallback));
    }

    /** Два guard с одним interface-прокси не принимают внешнюю правку за прочитанный снимок друг друга. */
    @Test void sharedAlternateBackendKeepsIndependentExpectedVersions() {
        var backing = new FakePlanStorage();
        PlanStorage alternate = (PlanStorage) Proxy.newProxyInstance(PlanStorage.class.getClassLoader(),
                new Class<?>[]{PlanStorage.class}, (proxy, method, args) -> {
                    try { return method.invoke(backing, args); }
                    catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
                });
        Path path = Path.of("CashMemory", "virtual.md");
        var reference = FilePlanStorage.reference(path);
        var original = Plan.empty("virtual", LocalDate.of(2026, 10, 4));
        var initial = backing.put(reference, original);
        var first = new ExternalChangeGuard(alternate);
        var second = new ExternalChangeGuard(alternate);
        first.remember(reference, initial);
        var external = backing.put(reference, original.withName("external"));
        second.remember(reference, external);
        assertSame(alternate, first.storage());
        assertTrue(first.changedExternally(path));
        assertFalse(second.changedExternally(path));
        assertEquals(initial, first.expectedVersion(reference, external));
        assertEquals(external, second.expectedVersion(reference, initial));
        var rejected = alternate.write(reference, original.withName("stale"), first.expectedVersion(reference, external));
        assertFalse(rejected.succeeded());
        assertEquals(PlanStorage.Code.CONFLICT, rejected.problem().code());
        assertEquals("external", backing.snapshot(reference).plan().name());
        first.forget();
        assertEquals(external, first.expectedVersion(reference, external));
        assertEquals(external, second.expectedVersion(reference, initial));
        assertEquals("virtual", original.name());
    }
}
