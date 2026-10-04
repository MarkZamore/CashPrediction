package ru.cashprediction.parity.update;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.update.model.FileEntry;
import ru.cashprediction.core.update.tree.TreeDeltaEngine;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет независимую формулу, сортировку UTF-8 и границу Updates. */
final class FixtureAuthorityTest {
    @TempDir Path temp;

    @Test void independentKnownVectorAndEngineAgree() throws Exception {
        // Вектор пустого дерева фиксирует prefix и завершающий NUL независимо от списка production.
        assertEquals("35f76addb8f284a8c28f24336a8dc85658a67ef7bc401fb902f6f843defccd05", FixtureAuthority.treeHash(List.of()));
        var files = List.of(new FileEntry("app/a", 3, FixtureAuthority.sha(new byte[]{1, 2, 3}), false),
                new FileEntry("runtime/测试", 0, FixtureAuthority.sha(new byte[0]), true));
        assertEquals("857e8227a5c6668285af85f21263cbf61a7087b2627096fe5c23cec19797d5ef", FixtureAuthority.treeHash(files));
        assertEquals(FixtureAuthority.treeHash(files), TreeDeltaEngine.treeHash(files));
    }

    @Test void updatesIsExcludedButUserAndUnmanagedChangesAreDetected() throws Exception {
        Files.createDirectories(temp.resolve("CashMemory/Updates")); Files.createDirectories(temp.resolve("app"));
        Files.writeString(temp.resolve("CashMemory/user.md"), "original"); Files.writeString(temp.resolve("notes.txt"), "root");
        Files.writeString(temp.resolve("app/data"), "managed");
        var user = FixtureAuthority.user(temp); var managed = FixtureAuthority.managed(temp);
        Files.writeString(temp.resolve("CashMemory/Updates/download"), "temporary");
        assertEquals(user, FixtureAuthority.user(temp)); assertEquals(managed, FixtureAuthority.managed(temp));
        Files.writeString(temp.resolve("CashMemory/user.md"), "changed"); assertNotEquals(user, FixtureAuthority.user(temp));
        Files.writeString(temp.resolve("CashMemory/user.md"), "original"); Files.writeString(temp.resolve("notes.txt"), "changed");
        assertNotEquals(user, FixtureAuthority.user(temp));
    }
}
