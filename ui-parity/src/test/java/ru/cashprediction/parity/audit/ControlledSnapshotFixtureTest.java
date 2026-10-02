package ru.cashprediction.parity.audit;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.parity.registry.RegistryNodeCleaner;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет реальные изолированные stores/holder без native GUI и подменённых дампов. */
final class ControlledSnapshotFixtureTest {
    @TempDir Path temp;

    /** Один архив детерминирован во всех stores; markers обоих клиентов отключают запись, cleanup обязателен. */
    @Test void preparedRealStoresAreIdenticalAndCleaned() throws Exception { verify(true); }

    /** Отсутствие снимка подготовлено до старта, а не через запрещённый Clear после отключения recorder. */
    @Test void absentRealStoresStayAbsentAndCleaned() throws Exception { verify(false); }

    /** Выполняет только real-store подготовку и проверки; UI в этих тестах отсутствует. */
    private void verify(boolean prepared) throws Exception {
        String node; Process holder;
        try (var fixture = ControlledSnapshotFixture.open(temp.resolve(prepared ? "prepared" : "absent"), prepared)) {
            node = fixture.node; holder = fixture.holder;
            fixture.requireAlreadyRunning(); fixture.requireUnchanged();
            assertEquals(prepared, fixture.snapshot != null);
            assertEquals("fx", fixture.environment.registryStore("fx").readMarker().orElseThrow().client());
            assertEquals("swing", fixture.environment.registryStore("swing").readMarker().orElseThrow().client());
            assertArrayEquals(java.nio.file.Files.readAllBytes(fixture.environment.xmlStore("fx").file()),
                    java.nio.file.Files.readAllBytes(fixture.environment.xmlStore("swing").file()));
        }
        assertFalse(holder.isAlive()); assertFalse(RegistryNodeCleaner.exists(node));
    }
}
