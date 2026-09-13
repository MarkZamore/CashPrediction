package ru.cashprediction.core.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Тесты раскладки папки CashMemory.
 */
class CashMemoryLayoutTest {

    @TempDir
    Path dir;

    @Test
    void fileNames() {
        CashMemoryLayout layout = new CashMemoryLayout(dir);
        assertEquals(dir.toAbsolutePath().normalize(), layout.dir());
        assertEquals(dir.resolve("settings.md"), layout.settingsFile());
        assertEquals(dir.resolve("session-fx.xml"), layout.sessionXml("fx"));
        assertEquals(dir.resolve("session-swing.xml"), layout.sessionXml("swing"));
        assertEquals("session-web.xml", CashMemoryLayout.sessionXmlFileName("web"));
        assertEquals(dir.resolve("web-session.md"), layout.webSession());
        assertEquals(dir.resolve("web-session.plan.md"), layout.webSessionPlan());
        assertEquals(layout.dir(), layout.plans().dir());
    }

    @Test
    void rejectsSuspiciousClientIds() {
        CashMemoryLayout layout = new CashMemoryLayout(dir);
        assertThrows(IllegalArgumentException.class, () -> layout.sessionXml("../fx"));
        assertThrows(IllegalArgumentException.class, () -> layout.sessionXml("FX"));
        assertThrows(IllegalArgumentException.class, () -> layout.sessionXml(""));
        assertThrows(IllegalArgumentException.class, () -> layout.sessionXml(null));
    }

    @Test
    void reservedPlanNames() {
        for (String name : new String[] {"settings", "SETTINGS", "web-session", "Web-Session.Plan", "session-fx", " session-swing "}) {
            assertTrue(CashMemoryLayout.isReservedPlanName(name), name);
        }
        for (String name : new String[] {"settings.md", "session-web", "Семейный бюджет", "web-session.plan.md", ""}) {
            assertFalse(CashMemoryLayout.isReservedPlanName(name), name);
        }
        assertFalse(CashMemoryLayout.isReservedPlanName(null));
    }

    @Test
    void probeWritableLeavesNoFiles() throws IOException {
        assertTrue(new CashMemoryLayout(dir).probeWritable());
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void probeWritableFailsForMissingFolder() {
        assertFalse(new CashMemoryLayout(dir.resolve("нет").resolve("папки")).probeWritable());
    }

    @Test
    void openDefaultCreatesFolderAndRemovesStaleTemporaryFiles() throws IOException {
        String previous = System.getProperty("cashprediction.home");
        System.setProperty("cashprediction.home", dir.toString());
        try {
            Path cashMemory = dir.resolve(AppPaths.CASH_MEMORY_DIR);
            Files.createDirectories(cashMemory);
            Path stale = Files.writeString(cashMemory.resolve("plan.md.123.456.tmp"), "обрывок");
            Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(1, ChronoUnit.HOURS)));
            Path plan = Files.writeString(cashMemory.resolve("план.md"), "# План: план\n");

            CashMemoryLayout layout = CashMemoryLayout.openDefault();
            assertEquals(cashMemory.toAbsolutePath().normalize(), layout.dir());
            assertFalse(Files.exists(stale), "старый временный файл удалён");
            assertTrue(Files.exists(plan), "планы не трогаются");
        } finally {
            if (previous == null) {
                System.clearProperty("cashprediction.home");
            } else {
                System.setProperty("cashprediction.home", previous);
            }
        }
    }
}
