package ru.cashprediction.core.update.lifecycle;

import java.net.URI;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Проверяет, что локальный endpoint доступен только явно изолированному самотесту. */
class UpdateSelftestEndpointTest {
    private static final String NODE = "ru/cashprediction/selftest/12345678-1234-1234-1234-123456789abc";
    private static final String PROPERTY = "cashprediction.update.selftest.manifest";
    @TempDir Path root;

    @Test
    void explicitIsolatedTestMayUseOnlyItsLoopbackManifest() {
        Properties properties = properties("http://127.0.0.1:18765/update.json");
        assertEquals(URI.create(properties.getProperty(PROPERTY)), UpdateLifecycle.selftestManifest(root,
                arguments(root, NODE), properties));
        assertNull(UpdateLifecycle.selftestManifest(root, arguments(root, NODE), new Properties()));
    }

    @Test
    void ordinaryLaunchCannotRedirectProductionUpdates() {
        Properties properties = properties("http://127.0.0.1:18765/update.json");
        assertNull(UpdateLifecycle.selftestManifest(root, new String[0], properties));
        assertNull(UpdateLifecycle.selftestManifest(root, new String[]{"--home", root.toString(),
                "--registry-node", NODE}, properties));
        assertNull(UpdateLifecycle.selftestManifest(root, new String[]{"--test-api", "--home", root.toString()}, properties));
        assertNull(UpdateLifecycle.selftestManifest(root, arguments(root, "ru/cashprediction/session/fx"), properties));
        assertNull(UpdateLifecycle.selftestManifest(root, arguments(root, "ru/cashprediction/selftest/not-a-uuid"), properties));
        assertNull(UpdateLifecycle.selftestManifest(root, arguments(root.resolve("foreign"), NODE), properties));
    }

    @Test
    void unsafeEndpointsNeverBecomeAProductionOverride() {
        for (String endpoint : new String[]{"https://127.0.0.1:18765/update.json", "http://localhost:18765/update.json",
                "http://example.com:18765/update.json", "http://127.0.0.1/update.json", "http://127.0.0.1:0/update.json",
                "http://127.0.0.1:65536/update.json", "http://user@127.0.0.1:18765/update.json",
                "http://127.0.0.1:18765/update.json?override=1", "http://127.0.0.1:18765/update.json#fragment",
                "http://127.0.0.1:18765/other.json", "http://127.0.0.1:18765/%75pdate.json", "not a URI"}) {
            assertNull(UpdateLifecycle.selftestManifest(root, arguments(root, NODE), properties(endpoint)), endpoint);
        }
    }

    /** Формирует только явные параметры собственной копии и тестового реестра. */
    private static String[] arguments(Path home, String node) {
        return new String[]{"--test-api", "--home", home.toString(), "--registry-node", node};
    }

    /** Изолирует проверку свойств от параметров настоящего процесса. */
    private static Properties properties(String endpoint) {
        Properties properties = new Properties();
        properties.setProperty(PROPERTY, endpoint);
        return properties;
    }
}
