package ru.cashprediction.core.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.diagnostics.Diagnostic;
import ru.cashprediction.core.markdown.PlanSamples;

/**
 * Этап S0.5: тексты пакета файлов (версия, имя файла по умолчанию, сообщения о файлах планов) берутся из каталога
 * ({@code io_ru.properties}) и совпадают с прежними русскими строками кода буква в букву.
 */
class IoTextsTest {

    @TempDir
    Path dir;

    /** Текст версии: локальная сборка без номера релиза или «Версия N». */
    @Test
    void displayVersion() {
        if (AppInfo.isDevelopmentBuild()) {
            assertEquals("Сборка разработчика", AppInfo.displayVersion());
        } else {
            assertTrue(AppInfo.displayVersion().startsWith("Версия " + AppInfo.release()), AppInfo.displayVersion());
        }
    }

    /** Имя файла по умолчанию — «План», а не имя нового плана интерфейса. */
    @Test
    void defaultFileName() {
        assertEquals("План", PlanRepository.DEFAULT_FILE_NAME);
        assertEquals("План", PlanRepository.sanitizeFileName(" ... "));
        assertEquals("План", PlanRepository.sanitizeFileName(null));
    }

    /** Сведения о расхождении имени в заголовке и имени файла. */
    @Test
    void nameMismatchDiagnostic() throws IOException {
        Path file = dir.resolve("Переименован в Проводнике.md");
        Files.writeString(file, PlanSamples.FAMILY_BUDGET);
        List<Diagnostic> diagnostics = new PlanRepository(dir).load(file, PlanSamples.TODAY).diagnostics();
        assertEquals(List.of("Имя плана в заголовке («Семейный бюджет 2026») не совпадает с именем файла; "
                + "используется имя файла «Переименован в Проводнике»"), diagnostics.stream().map(Diagnostic::message).toList());
    }

    /** Причина отказа переименования в занятое имя. */
    @Test
    void renameConflictReason() throws IOException {
        Path from = dir.resolve("Первый.md");
        Files.writeString(from, "# План: Первый\n");
        Files.writeString(dir.resolve("Второй.md"), "# План: Второй\n");
        FileAlreadyExistsException e = assertThrows(FileAlreadyExistsException.class,
                () -> new PlanRepository(dir).rename(from, "Второй"));
        assertEquals("План с таким именем уже существует", e.getReason());
    }
}
