package ru.cashprediction.core.update.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.io.AppInfo;

/** Проверка полной инертности локальной сборки и идемпотентности публичного lifecycle. */
class UpdateLifecycleTest {
    @TempDir Path temporary;

    @Test void developmentBuildDoesNotCreateCashMemoryOrStartBackgroundWork() throws Exception {
        assumeTrue(AppInfo.release() <= 0 || !AppInfo.commit().matches("[0-9a-f]{40}"));
        Path root = Files.createDirectory(temporary.resolve("inert"));
        UpdateLifecycle lifecycle = UpdateLifecycle.create(root, root.resolve("CashMemory"), "web",
                new String[]{"--no-browser", "--no-window"});
        assertTrue(lifecycle.beforeUi());
        assertTrue(lifecycle.beforeUi());
        lifecycle.afterUiReady();
        lifecycle.afterUiReady();
        lifecycle.close();
        lifecycle.close();
        assertFalse(Files.exists(root.resolve("CashMemory")));
    }
}
