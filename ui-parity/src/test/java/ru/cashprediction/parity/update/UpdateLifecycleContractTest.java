package ru.cashprediction.parity.update;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.net.URLClassLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.io.AppInfo;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет frozen lifecycle API на инертной dev-сборке, не выдавая это за запуск GUI. */
final class UpdateLifecycleContractTest {
    @TempDir Path temp;

    @Test void developmentBuildIsInertForAllThreeClients() throws Exception {
        // Отдельный classloader задаёт dev-ресурс даже при сборке main с настоящим app.release.
        try (DevCoreLoader loader = new DevCoreLoader(AppInfo.class.getProtectionDomain().getCodeSource().getLocation())) {
            Class<?> info = loader.loadClass("ru.cashprediction.core.io.AppInfo");
            assertEquals(0, info.getMethod("release").invoke(null));
            Class<?> api = loader.loadClass("ru.cashprediction.core.update.lifecycle.UpdateLifecycle");
            for (String client : UpdateEvidence.CLIENTS) {
                Path root = temp.resolve(client);
                Object lifecycle = api.getMethod("create", Path.class, Path.class, String.class, String[].class)
                        .invoke(null, root, root.resolve("CashMemory"), client, new String[0]);
                try (AutoCloseable owned = (AutoCloseable) lifecycle) {
                    assertEquals(true, api.getMethod("beforeUi").invoke(lifecycle));
                    assertEquals(true, api.getMethod("beforeUi").invoke(lifecycle));
                    api.getMethod("afterUiReady").invoke(lifecycle); api.getMethod("afterUiReady").invoke(lifecycle);
                    assertFalse(Files.exists(root));
                }
                assertFalse(Files.exists(root));
            }
        }
        try (var paths = Files.list(temp)) { assertEquals(List.of(), paths.toList()); }
    }

    /** Изолирует только тестовый dev-ресурс, используя настоящие классы ядра. */
    private static final class DevCoreLoader extends URLClassLoader {
        DevCoreLoader(URL core) { super(new URL[]{core}, ClassLoader.getPlatformClassLoader()); }
        /** Подставляет release0 только в собственной фикстуре, без изменения jar main. */
        @Override public InputStream getResourceAsStream(String name) {
            return name.equals("ru/cashprediction/core/app.properties")
                    ? new ByteArrayInputStream("release=0\ncommit=local\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    : super.getResourceAsStream(name);
        }
    }
}
