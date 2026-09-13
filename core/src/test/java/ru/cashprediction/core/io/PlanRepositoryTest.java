package ru.cashprediction.core.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.cashprediction.core.diagnostics.Severity;
import ru.cashprediction.core.markdown.MarkdownParseException;
import ru.cashprediction.core.markdown.PlanMarkdownReader;
import ru.cashprediction.core.markdown.PlanMarkdownWriter;
import ru.cashprediction.core.markdown.PlanSamples;
import ru.cashprediction.core.markdown.ReadResult;
import ru.cashprediction.core.model.Plan;

/**
 * Тесты репозитория файлов планов на временной папке.
 */
class PlanRepositoryTest {

    private static final LocalDate TODAY = PlanSamples.TODAY;

    @TempDir
    Path dir;

    @Test
    void listShowsOnlyPlanFilesSortedByName() throws IOException {
        for (String name : List.of("Б план.md", "а план.md", "Zeta.MD", "settings.md", "Web-Session.md",
                "web-session.plan.md", "session-fx.md", "SESSION-SWING.md", "notes.txt", "x.md.123.456.tmp", ".md")) {
            Files.writeString(dir.resolve(name), "# План: x\n");
        }
        Files.createDirectory(dir.resolve("папка.md"));
        List<PlanFileInfo> list = new PlanRepository(dir).list();
        assertEquals(List.of("Zeta", "а план", "Б план"), list.stream().map(PlanFileInfo::name).toList());
        assertEquals(dir.resolve("а план.md"), list.get(1).path());
        assertEquals(Files.getLastModifiedTime(dir.resolve("а план.md")), list.get(1).lastModified());
    }

    @Test
    void listOfMissingFolderIsEmpty() {
        assertEquals(List.of(), new PlanRepository(dir.resolve("нет такой")).list());
    }

    @Test
    void saveAndLoadRoundTrip() throws IOException {
        PlanRepository repository = new PlanRepository(dir);
        Plan plan = PlanMarkdownReader.read(PlanSamples.FAMILY_BUDGET, "x", TODAY).plan();
        Path file = repository.pathFor(plan.name());
        assertEquals(dir.resolve("Семейный бюджет 2026.md"), file);
        repository.save(plan, file);
        assertEquals(PlanSamples.FAMILY_BUDGET, Files.readString(file, StandardCharsets.UTF_8));
        ReadResult loaded = repository.load(file, TODAY);
        assertEquals(List.of(), loaded.diagnostics());
        assertEquals(plan, loaded.plan());
        assertEquals(Files.getLastModifiedTime(file), repository.lastModified(file));
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(1, files.count(), "временные файлы не остаются");
        }
    }

    @Test
    void fileNameWinsOverTitle() throws IOException {
        PlanRepository repository = new PlanRepository(dir);
        Path file = dir.resolve("Переименован в Проводнике.md");
        Files.writeString(file, PlanSamples.FAMILY_BUDGET);
        ReadResult loaded = repository.load(file, TODAY);
        assertEquals("Переименован в Проводнике", loaded.plan().name());
        assertEquals(1, loaded.diagnostics().size());
        assertEquals(Severity.INFO, loaded.diagnostics().get(0).severity());
    }

    @Test
    void sanitizedTitleMatchingFileNameIsKept() throws IOException {
        PlanRepository repository = new PlanRepository(dir);
        Plan plan = Plan.empty("a/b", TODAY);
        Path file = repository.pathFor(plan.name());
        assertEquals("a_b.md", file.getFileName().toString());
        repository.save(plan, file);
        assertEquals("a/b", repository.load(file, TODAY).plan().name());

        Plan reserved = Plan.empty("Settings", TODAY);
        Path reservedFile = repository.pathFor(reserved.name());
        assertEquals("Settings_.md", reservedFile.getFileName().toString());
        repository.save(reserved, reservedFile);
        assertEquals("Settings", repository.load(reservedFile, TODAY).plan().name());
    }

    @Test
    void loadAcceptsBomAndRejectsForeignFiles() throws IOException {
        PlanRepository repository = new PlanRepository(dir);
        Path bom = dir.resolve("Семейный бюджет 2026.md");
        Files.writeString(bom, "﻿" + PlanSamples.FAMILY_BUDGET.replace("\n", "\r\n"));
        assertEquals(List.of(), repository.load(bom, TODAY).diagnostics());

        Path foreign = dir.resolve("чужой.md");
        Files.writeString(foreign, "# Список покупок\n\n- хлеб\n");
        assertThrows(MarkdownParseException.class, () -> repository.load(foreign, TODAY));
    }

    @Test
    void renameMovesFileAndRewritesTitle() throws IOException {
        PlanRepository repository = new PlanRepository(dir);
        Path from = dir.resolve("Семейный бюджет 2026.md");
        Files.writeString(from, PlanSamples.FAMILY_BUDGET);
        Path to = repository.rename(from, "Бюджет: 2027");
        assertEquals(dir.resolve("Бюджет_ 2027.md"), to);
        assertFalse(Files.exists(from));
        String text = Files.readString(to);
        assertEquals(PlanSamples.FAMILY_BUDGET.replace("# План: Семейный бюджет 2026", "# План: Бюджет: 2027"), text);
        assertEquals("Бюджет: 2027", repository.load(to, TODAY).plan().name());
    }

    @Test
    void renameConflictFailsAndKeepsSource() throws IOException {
        PlanRepository repository = new PlanRepository(dir);
        Path from = dir.resolve("Первый.md");
        Path other = dir.resolve("Второй.md");
        Files.writeString(from, "# План: Первый\n");
        Files.writeString(other, "# План: Второй\n");
        assertThrows(FileAlreadyExistsException.class, () -> repository.rename(from, "Второй"));
        assertEquals("# План: Первый\n", Files.readString(from));
        assertEquals("# План: Второй\n", Files.readString(other));
    }

    @Test
    void renameChangingOnlyLetterCase() throws IOException {
        PlanRepository repository = new PlanRepository(dir);
        Path from = dir.resolve("бюджет.md");
        Files.writeString(from, "# План: бюджет\r\n\r\n## Регулярные операции\r\n");
        Path to = repository.rename(from, "Бюджет");
        assertEquals("Бюджет.md", to.getFileName().toString());
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of("Бюджет.md"), files.map(p -> p.getFileName().toString()).toList());
        }
        assertEquals("# План: Бюджет\r\n\r\n## Регулярные операции\r\n", Files.readString(to), "CRLF сохранены");
    }

    @Test
    void renameAddsTitleWhenMissing() throws IOException {
        PlanRepository repository = new PlanRepository(dir);
        Path from = dir.resolve("старое.md");
        Files.writeString(from, "## Регулярные операции\n\n# План: это не заголовок\n");
        Path to = repository.rename(from, "новое");
        assertEquals("# План: новое\n\n## Регулярные операции\n\n# План: это не заголовок\n", Files.readString(to));
    }

    @ParameterizedTest(name = "«{0}» → «{1}»")
    @CsvSource(delimiter = '|', value = {
        "a/b:c                | a_b_c",
        "a*b?c\"d<e>f\\g       | a_b_c_d_e_f_g",
        "CON                  | CON_",
        "con                  | con_",
        "LPT9                 | LPT9_",
        "Com1                 | Com1_",
        "COM10                | COM10",
        "con.backup           | con_.backup",
        "CONTROL              | CONTROL",
        "План.                | План",
        "'  Отпуск ... '      | Отпуск",
        "Семейный бюджет 2026 | Семейный бюджет 2026"
    })
    void sanitizesFileNames(String name, String expected) {
        assertEquals(expected, PlanRepository.sanitizeFileName(name));
    }

    @Test
    void sanitizeEdgeCases() {
        assertEquals("План", PlanRepository.sanitizeFileName("  "));
        assertEquals("План", PlanRepository.sanitizeFileName(""));
        assertEquals("План", PlanRepository.sanitizeFileName(null));
        assertEquals("План", PlanRepository.sanitizeFileName("..."));
        assertEquals("tab_here_x", PlanRepository.sanitizeFileName("tab\therex"));
        assertEquals("a_b", PlanRepository.sanitizeFileName("a|b"), "вертикальная черта запрещена в Windows");
        assertEquals(80, PlanRepository.sanitizeFileName("я".repeat(100)).length());
        assertEquals("a".repeat(79), PlanRepository.sanitizeFileName("a".repeat(79) + ".bbbb"), "точка на границе обрезки срезается");
        assertEquals("settings_", PlanRepository.fileBaseName("settings"));
        assertEquals("web-session.plan_", PlanRepository.fileBaseName("Web-Session.plan").toLowerCase());

        // Регрессия: суффикс имени устройства добавлялся после обрезки, и имя выходило длиной 81.
        String device = PlanRepository.sanitizeFileName("CON." + "x".repeat(80));
        assertEquals(PlanRepository.MAX_FILE_NAME_LENGTH, device.codePointCount(0, device.length()), device);
        assertTrue(device.startsWith("CON_."), device);
        assertEquals("CON_", PlanRepository.sanitizeFileName("CON" + " ".repeat(77) + "хвост"));
    }

    @Test
    void pathForUsesFolder() {
        PlanRepository repository = new PlanRepository(dir);
        assertEquals(dir.toAbsolutePath().normalize(), repository.dir());
        assertEquals(dir.resolve("a_b.md"), repository.pathFor("a/b"));
        assertEquals(dir.resolve("settings_.md"), repository.pathFor("settings"));
        assertTrue(repository.pathFor("x").startsWith(dir));
    }

    @Test
    void writerOutputIsWhatSaveWrites() throws IOException {
        PlanRepository repository = new PlanRepository(dir);
        Plan plan = Plan.empty("Новый", LocalDate.of(2026, 9, 1));
        Path file = dir.resolve("вложенная").resolve("Новый.md");
        repository.save(plan, file);
        assertEquals(PlanMarkdownWriter.write(plan), Files.readString(file));
    }
}
