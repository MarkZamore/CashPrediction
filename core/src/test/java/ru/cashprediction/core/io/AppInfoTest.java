package ru.cashprediction.core.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Тесты версии приложения: разбор подставленных сборкой значений и текст для интерфейса.
 */
class AppInfoTest {

    @Test
    void parsesReleaseNumberAndFallsBackToDevelopment() {
        assertEquals(12, AppInfo.parseRelease("12"));
        assertEquals(12, AppInfo.parseRelease(" 12 "));
        assertEquals(0, AppInfo.parseRelease("${app.release}"), "неподставленное свойство из IDE");
        assertEquals(0, AppInfo.parseRelease("-3"));
        assertEquals(0, AppInfo.parseRelease(""));
    }

    @Test
    void keepsOnlyRealCommitHashes() {
        assertEquals("a1b2c3d4e5", AppInfo.normalizeCommit("A1B2C3D4E5"));
        assertEquals("", AppInfo.normalizeCommit("${app.commit}"));
        assertEquals("", AppInfo.normalizeCommit("local"));
        assertEquals("", AppInfo.normalizeCommit(null));
    }

    @Test
    void displayVersionMatchesBuildKind() {
        // Тесты запускаются обычной сборкой: номер релиза либо не задан (0), либо задан CI.
        String text = AppInfo.displayVersion();
        if (AppInfo.isDevelopmentBuild()) {
            assertEquals("Сборка разработчика", text);
        } else {
            assertTrue(text.startsWith("Версия " + AppInfo.release()), text);
        }
        assertFalse(text.isBlank());
    }
}
