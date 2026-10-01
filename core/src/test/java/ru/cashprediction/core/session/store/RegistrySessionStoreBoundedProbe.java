package ru.cashprediction.core.session.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import ru.cashprediction.core.session.CrashDetector;
import ru.cashprediction.core.session.SessionFixtures;
import ru.cashprediction.core.session.SessionMarker;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.session.SessionStoreException;
import ru.cashprediction.core.text.Texts;

/** Отдельный процесс с малой кучей: метаданные реестра не должны перекрывать XML-восстановление. */
public final class RegistrySessionStoreBoundedProbe {

    private RegistrySessionStoreBoundedProbe() {
    }

    /**
     * Проверяет гигантские объявленные размеры без записи в настоящий реестр.
     *
     * @param args единственный аргумент - изолированная папка CashMemory
     * @throws Exception если регрессия воспроизведена или XML-файл недоступен
     */
    public static void main(String[] args) throws Exception {
        Path cashMemory = Path.of(args[0]);
        Files.createDirectories(cashMemory);
        SessionSnapshot snapshot = SessionFixtures.tricky("fx");
        XmlSessionStore xml = XmlSessionStore.inCashMemory(cashMemory, "fx");
        SessionMarker marker = SessionMarker.running(ProcessHandle.current().pid(), SessionFixtures.STARTED, "fx");
        xml.markDirty(marker);
        xml.save(snapshot);

        for (int length : new int[] {1073741824, Integer.MAX_VALUE}) {
            for (String firstChunk : new String[] {null, "x".repeat(4096), ""}) {
                InMemoryRegistryBackend backend = new InMemoryRegistryBackend();
                RegistrySessionStore registry = new RegistrySessionStore(backend, "fx");
                registry.markDirty(marker);
                backend.put(RegistrySessionStore.KEY_SNAPSHOT_TIME, snapshot.savedAt().toString());
                backend.put(RegistrySessionStore.KEY_SNAPSHOT_LENGTH, String.valueOf(length));
                backend.put(RegistrySessionStore.KEY_SNAPSHOT_COUNT,
                        String.valueOf(((long) length + 4095) / 4096));
                backend.put(RegistrySessionStore.KEY_SNAPSHOT_CRC, "00000000");
                if (firstChunk != null) {
                    backend.put("snapshot.0", firstChunk);
                }
                try {
                    registry.load();
                    throw new AssertionError("corrupt registry was accepted");
                } catch (SessionStoreException expected) {
                    // При максимальной длине обязаны дойти до кусков, а не отвергнуть её из-за переполнения.
                    if (firstChunk == null && !expected.getMessage().equals(Texts.get("session.registry.corrupted",
                            Texts.get("session.registry.corrupt.noChunk", "snapshot.0")))) {
                        throw new AssertionError("metadata arithmetic overflow", expected);
                    }
                }
                CrashDetector.Detection detection = CrashDetector.detect(List.of(registry, xml), "fx");
                if (detection.status() != CrashDetector.Status.CRASHED
                        || detection.stores().get("registry").restorable()
                        || detection.stores().get("registry").problem().isEmpty()
                        || !detection.stores().get("xml").restorable()
                        || !detection.anyRestorable()
                        || !xml.load().equals(Optional.of(snapshot))) {
                    throw new AssertionError("valid XML recovery was lost");
                }
            }
        }
    }
}
