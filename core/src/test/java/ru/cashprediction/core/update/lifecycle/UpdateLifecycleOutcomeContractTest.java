package ru.cashprediction.core.update.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.update.install.InstallCoordinator;
import ru.cashprediction.core.update.model.UpdateProblem;

/** Реальные локальные отказы lifecycle имеют data-only исход и не вызывают native помощника. */
class UpdateLifecycleOutcomeContractTest {
    @TempDir Path temporary;

    /** Ошибка настоящего lock сохраняет прежний fail-open, но не выглядит здоровым барьером. */
    @Test void barrierFailureIsTypedCachedAndRetainsPolicy() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("copy")).toRealPath();
        Path memory = Files.createDirectory(root.resolve("CashMemory"));
        Files.writeString(memory.resolve("Updates"), "blocker");
        InstallCoordinator coordinator = new InstallCoordinator(root, memory, "fx", new String[0], 1);
        UpdateLifecycle lifecycle = local(root, coordinator);
        var first = lifecycle.beforeUiResult();
        assertTrue(first.allowed());
        assertEquals(UpdateProblem.Code.BARRIER_FAILED, first.problem().orElseThrow().code());
        assertSame(first, lifecycle.beforeUiResult());
        assertTrue(lifecycle.beforeUi());
        lifecycle.close();
        lifecycle.close();
        assertEquals(UpdateSessionLifecycle.Stage.CLOSED, lifecycle.status().stage());
        assertEquals(UpdateSessionLifecycle.Decision.CLOSED, lifecycle.beforeUiResult().decision());
        assertEquals("blocker", Files.readString(memory.resolve("Updates")));
    }

    /** Ошибка inventory возникает до HTTP, lifecycle публикует её, закрытый snapshot не меняется. */
    @Test void backgroundLocalFailureIsObservableWithoutNetworkOrNative() throws Exception {
        Path copy = Files.createDirectory(temporary.resolve("copy")).toRealPath();
        Path memory = copy.resolve("CashMemory");
        InstallCoordinator coordinator = new InstallCoordinator(copy, memory, "fx", new String[0], 1);
        Path blocker = temporary.resolve("tree-blocker");
        Files.writeString(blocker, "blocker");
        UpdateLifecycle lifecycle = local(blocker, coordinator);
        assertTrue(lifecycle.beforeUiResult().allowed());
        lifecycle.afterUiReady();
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (lifecycle.status().stage() == UpdateSessionLifecycle.Stage.PREPARING) Thread.onSpinWait();
        });
        assertEquals(UpdateSessionLifecycle.Stage.NOT_PREPARED, lifecycle.status().stage());
        assertEquals(UpdateProblem.Code.PREPARATION_FAILED, lifecycle.status().problem().orElseThrow().code());
        lifecycle.close();
        var closed = lifecycle.status();
        lifecycle.afterUiReady();
        assertEquals(closed, lifecycle.status());
        assertEquals("blocker", Files.readString(blocker));
    }

    /** Неопределённое решение адаптера не разрешает UI; его данные не содержат toolkit/Throwable. */
    @Test void unknownDecisionIsConservativeAndPayloadsAreNeutral() throws Exception {
        assertFalse(new UpdateSessionLifecycle.StartupResult(UpdateSessionLifecycle.Decision.UNKNOWN, Optional.empty()).allowed());
        assertFalse(new UpdateSessionLifecycle.StartupResult(UpdateSessionLifecycle.Decision.BLOCKED, Optional.empty()).allowed());
        // Архитектура принадлежит compiled descriptor, а не unnamed runtime штатного Surefire.
        var origin = UpdateLifecycle.class.getProtectionDomain().getCodeSource();
        assertNotNull(origin, "compiled core location required");
        var location = Path.of(origin.getLocation().toURI());
        var descriptor = java.lang.module.ModuleFinder.of(location).find("ru.cashprediction.core")
                .orElseThrow(() -> new AssertionError("compiled core module-info.class required")).descriptor();
        for (var dependency : descriptor.requires()) {
            String name = dependency.name();
            assertFalse(name.equals("java.desktop") || name.startsWith("javafx.")
                    || name.startsWith("ru.cashprediction.fx") || name.startsWith("ru.cashprediction.swing")
                    || name.startsWith("ru.cashprediction.web"), name);
        }
        for (Class<?> type : List.of(UpdateSessionLifecycle.StartupResult.class, UpdateSessionLifecycle.Status.class,
                UpdateProblem.class)) {
            assertTrue(type.isRecord(), type.getName());
            for (var component : type.getRecordComponents()) {
                assertFalse(Throwable.class.isAssignableFrom(component.getType()));
                assertFalse(component.getGenericType().getTypeName().contains("javafx"));
                assertFalse(component.getGenericType().getTypeName().contains("javax.swing"));
            }
        }
    }

    /** Использует существующий private wiring constructor без mock InstallCoordinator или изменения API фабрики. */
    private static UpdateLifecycle local(Path root, InstallCoordinator coordinator) throws Exception {
        var constructor = UpdateLifecycle.class.getDeclaredConstructor(Path.class, InstallCoordinator.class, URI.class);
        constructor.setAccessible(true);
        return constructor.newInstance(root, coordinator, null);
    }
}
