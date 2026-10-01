package ru.cashprediction.core.app.session.capture;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.flow.SessionStores;
import ru.cashprediction.core.session.SessionStore;
import ru.cashprediction.core.session.store.*;

/** Проверяет выбор хранилищ и отсутствие записи файлов при их создании. */
class SessionStoresTest {
    @TempDir Path home;

    /** Реестр в памяти первым у desktop, единственный Markdown у web. */
    @Test void clientStoresAreOrderedAndLazy() {
        for (ClientProfile profile : List.of(ClientProfile.fx("25"), ClientProfile.swing(), ClientProfile.web())) {
            CaptureContext fake = new CaptureContext(home, profile);
            List<SessionStore> stores = SessionStores.forClient(profile, fake.environment);
            if (profile.equals(ClientProfile.web())) {
                assertEquals(1, stores.size());
                MarkdownSessionStore store = assertInstanceOf(MarkdownSessionStore.class, stores.getFirst());
                assertEquals(fake.environment.cashMemory().resolve("web-session.md"), store.sessionFile());
            } else {
                assertEquals(List.of("registry", "xml"), stores.stream().map(SessionStore::id).toList());
                assertInstanceOf(RegistrySessionStore.class, stores.getFirst());
                XmlSessionStore xml = assertInstanceOf(XmlSessionStore.class, stores.get(1));
                assertEquals(fake.environment.cashMemory().resolve("session-" + profile.snapshotClient() + ".xml"), xml.file());
            }
            assertFalse(Files.exists(fake.environment.cashMemory()));
            assertThrows(UnsupportedOperationException.class, () -> stores.add(stores.getFirst()));
        }
    }
}
