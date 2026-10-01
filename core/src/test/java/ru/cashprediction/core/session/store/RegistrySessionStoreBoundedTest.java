package ru.cashprediction.core.session.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.session.PlanState;
import ru.cashprediction.core.session.SessionFixtures;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.session.SessionStoreException;
import ru.cashprediction.core.session.codec.JsonSnapshotCodec;

/** Проверяет безопасное чтение недоверенных размеров и сохранение формата снимков. */
class RegistrySessionStoreBoundedTest {

    @TempDir
    Path directory;

    /** Повреждение реестра не исчерпывает память и не мешает восстановлению из XML. */
    @Test
    void hugeMetadataStillAllowsXmlRecoveryInBoundedJvm() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        // Источники классов работают и при модульном запуске Surefire, и при обычном classpath.
        String classpath = List.of(RegistrySessionStoreBoundedProbe.class, RegistrySessionStore.class,
                SessionFixtures.class).stream()
                .map(type -> {
                    try {
                        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }).distinct().collect(Collectors.joining(File.pathSeparator));
        Path output = directory.resolve("probe.log");
        Process process = new ProcessBuilder(java, "-Xmx32m", "-XX:-UsePerfData", "-cp", classpath,
                RegistrySessionStoreBoundedProbe.class.getName(), directory.resolve("CashMemory").toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "bounded recovery probe timed out");
            assertEquals(0, process.exitValue(), () -> {
                try {
                    return Files.readString(output);
                } catch (Exception e) {
                    return e.toString();
                }
            });
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "probe did not stop");
            }
        }
    }

    /** Границы кусков и большой настоящий снимок остаются допустимыми. */
    @Test
    void legitimateChunkBoundariesAndLargeSnapshotRoundTrip() throws Exception {
        SessionSnapshot base = SessionFixtures.simple("fx");
        SessionSnapshot emptyPlan = new SessionSnapshot(base.schemaVersion(), base.savedAt(), base.client(),
                base.main(), PlanState.dirty(""), base.windows());
        int overhead = new JsonSnapshotCodec().encode(emptyPlan).length();
        for (int size : new int[] {4095, 4096, 4097, 2 * 1024 * 1024}) {
            SessionSnapshot snapshot = new SessionSnapshot(base.schemaVersion(), base.savedAt(), base.client(),
                    base.main(), PlanState.dirty("x".repeat(size - overhead - 2) + "\ud83d\ude00"), base.windows());
            assertEquals(size, new JsonSnapshotCodec().encode(snapshot).length());
            InMemoryRegistryBackend backend = new InMemoryRegistryBackend();
            RegistrySessionStore store = new RegistrySessionStore(backend, "fx");
            store.save(snapshot);
            assertEquals(Optional.of(snapshot), store.load());
        }
    }

    /** Пустые, слишком длинные и превышающие общую длину куски отвергаются до склейки. */
    @Test
    void invalidActualChunksAreCorruption() throws Exception {
        InMemoryRegistryBackend backend = new InMemoryRegistryBackend();
        RegistrySessionStore store = new RegistrySessionStore(backend, "fx");
        for (String chunk : List.of("", "x".repeat(4097), "xx")) {
            store.save(SessionFixtures.simple("fx"));
            backend.put(RegistrySessionStore.KEY_SNAPSHOT_LENGTH, "1");
            backend.put(RegistrySessionStore.KEY_SNAPSHOT_COUNT, "1");
            backend.put("snapshot.0", chunk);
            assertThrows(SessionStoreException.class, store::load);
        }
    }

    /** Число кусков ограничивается длиной, включая край диапазона int. */
    @Test
    void inconsistentHugeCountsAreCorruption() throws Exception {
        InMemoryRegistryBackend backend = new InMemoryRegistryBackend();
        RegistrySessionStore store = new RegistrySessionStore(backend, "fx");
        store.save(SessionFixtures.simple("fx"));
        backend.put(RegistrySessionStore.KEY_SNAPSHOT_LENGTH, String.valueOf(Integer.MAX_VALUE));
        backend.put(RegistrySessionStore.KEY_SNAPSHOT_COUNT, String.valueOf(Integer.MAX_VALUE));
        SessionStoreException failure = assertThrows(SessionStoreException.class, store::load);
        assertTrue(failure.getMessage().contains(String.valueOf(Integer.MAX_VALUE)));
    }
}
