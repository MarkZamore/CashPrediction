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
import org.junit.jupiter.params.provider.ValueSource;
import ru.cashprediction.core.diagnostics.Severity;
import ru.cashprediction.core.markdown.MarkdownParseException;
import ru.cashprediction.core.markdown.PlanMarkdownReader;
import ru.cashprediction.core.markdown.PlanMarkdownWriter;
import ru.cashprediction.core.markdown.PlanSamples;
import ru.cashprediction.core.markdown.ReadResult;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.text.Texts;

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
                "web-session.plan.md", "session-fx.md", "SESSION-SWING.md", "notes.txt", "x.md.123.456.tmp", ".md",
                "cashprediction-tmp-123-00000000-0000-0000-0000-000000000000.md",
                "CASHPREDICTION-TMP-123-00000000-0000-0000-0000-000000000000.rename.MD")) {
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

    /** Временные копии и их реальные алиасы нельзя открыть как планы или перезаписать через репозиторий. */
    @Test
    void temporaryFilesAndAliasesAreProtected() throws IOException {
        var repository = new PlanRepository(dir);
        for (String suffix : List.of("md", "xml", "rename.md")) {
            Path temporary = AtomicFiles.temporaryPath(dir.resolve("Budget.md"), suffix);
            Files.writeString(temporary, PlanSamples.FAMILY_BUDGET);
            Path alias = Files.createLink(dir.resolve("Alias-" + suffix + ".md"), temporary);
            assertThrows(IOException.class, () -> repository.load(temporary, TODAY));
            assertThrows(IOException.class, () -> repository.load(alias, TODAY));
            assertThrows(IOException.class, () -> repository.save(Plan.empty("Budget", TODAY), alias));
            assertThrows(IOException.class, () -> repository.rename(alias, "Renamed"));
            assertEquals(PlanSamples.FAMILY_BUDGET, Files.readString(temporary));
        }
        assertTrue(repository.list().isEmpty());
        String reserved = "CASHPREDICTION-TMP-personal";
        Path safe = repository.pathFor(reserved);
        assertFalse(CashMemoryLayout.isServiceFileName(safe.getFileName().toString()));
        repository.save(Plan.empty(reserved, TODAY), safe);
        assertEquals(List.of(safe), repository.list().stream().map(PlanFileInfo::path).toList());
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

    /** Raw recent/file chooser не обходят защиту служебного имени даже при корректном Markdown внутри. */
    @ParameterizedTest
    @ValueSource(strings = {"settings.md", "web-session.md", "web-session.plan.md", "session-fx.xml",
            "session-swing.xml", "web-reconnect.md", "web-reconnect-lock.md", "web-reconnect-tmp-731.md",
            "WEB-RECONNECT.md"})
    void rejectsServiceSourceAndTargetBeforeReadingOrWriting(String name) throws IOException {
        PlanRepository repository = new PlanRepository(dir);
        Path service = dir.resolve(name);
        String secret = "private-service-sentinel-731";
        Files.writeString(service, secret);
        Plan plan = Plan.empty("Ordinary", TODAY);
        IOException load = assertThrows(IOException.class, () -> repository.load(service, TODAY));
        assertEquals(Texts.get("diagnostic.name.reservedByApp", name), load.getMessage());
        assertFalse(load.getMessage().contains(secret));
        assertThrows(IOException.class, () -> repository.save(plan, service));
        assertThrows(IOException.class, () -> repository.rename(service, "Ordinary"));
        assertEquals(secret, Files.readString(service));
        assertFalse(Files.exists(dir.resolve("Ordinary.md")));
    }

    /** Защита действует и до появления файла, не только после записи служебного секрета. */
    @ParameterizedTest
    @ValueSource(strings = {"settings.md", "web-reconnect.md", "web-reconnect-lock.md", "web-reconnect-tmp-732.md"})
    void refusesCreatingProtectedUserTarget(String name) {
        var repository = new PlanRepository(dir);
        Path target = dir.resolve(name);
        assertThrows(IOException.class, () -> repository.save(Plan.empty("Ordinary", TODAY), target));
        assertFalse(Files.exists(target));
    }

    /** Настоящие hard-link алиасы служебных файлов нельзя открыть, перезаписать или переименовать. */
    @ParameterizedTest
    @ValueSource(strings = {"settings.md", "web-reconnect.md", "web-session.plan.md", "session-fx.xml",
            "web-reconnect-tmp-734.md"})
    void protectsRealAliasesIncludingExternalRecentPath(String name) throws IOException {
        Path memory = Files.createDirectory(dir.resolve("CashMemory"));
        var repository = new PlanRepository(memory);
        Path service = memory.resolve(name);
        String secret = "private-alias-sentinel-732";
        Files.writeString(service, secret);
        Path alias = Files.createLink(dir.resolve("external-recent.md"), service);
        assertTrue(Files.isSameFile(alias, service), "Test must use a real filesystem alias");
        assertThrows(IOException.class, () -> repository.load(alias, TODAY));
        assertThrows(IOException.class, () -> repository.save(Plan.empty("External", TODAY), alias));
        assertThrows(IOException.class, () -> repository.rename(alias, "Renamed"));
        assertEquals(secret, Files.readString(service));
        assertEquals(secret, Files.readString(alias));
        assertFalse(Files.exists(dir.resolve("Renamed.md")));
    }

    /** Проверка rename защищает существующий целевой алиас до чтения или изменения исходного плана. */
    @Test
    void renameRejectsProtectedTargetAliasAndLeavesSourceIntact() throws IOException {
        Path memory = Files.createDirectory(dir.resolve("CashMemory"));
        var repository = new PlanRepository(memory);
        Path service = memory.resolve("web-reconnect.md");
        Files.writeString(service, "private-target-sentinel-733");
        Path targetAlias = Files.createLink(memory.resolve("Destination.md"), service);
        Path source = memory.resolve("Source.md");
        repository.save(Plan.empty("Source", TODAY), source);
        String original = Files.readString(source);
        IOException failure = assertThrows(IOException.class, () -> repository.rename(source, "Destination"));
        assertEquals(Texts.get("diagnostic.name.reservedByApp", "Destination.md"), failure.getMessage());
        assertEquals(original, Files.readString(source));
        assertEquals("private-target-sentinel-733", Files.readString(service));
        assertTrue(Files.isSameFile(targetAlias, service));
    }

    /** Защита папки CashMemory не запрещает пользовательский план в отдельной внешней папке. */
    @Test
    void normalExternalPlanCanBeSavedLoadedAndRenamed() throws IOException {
        Path memory = Files.createDirectory(dir.resolve("CashMemory"));
        var repository = new PlanRepository(memory);
        Path external = Files.createDirectory(dir.resolve("External"));
        Path file = external.resolve("Budget.md");
        Plan plan = Plan.empty("Budget", TODAY);
        repository.save(plan, file);
        assertEquals(plan, repository.load(file, TODAY).plan());
        Path renamed = repository.rename(file, "BudgetNext");
        assertEquals(external.resolve("BudgetNext.md"), renamed);
        assertFalse(Files.exists(file));
        assertEquals("BudgetNext", repository.load(renamed, TODAY).plan().name());
    }

    /** Нормализация пути не позволяет спрятать служебную цель за сегментами родительской папки. */
    @Test
    void normalizedRecentPathCannotReachServiceFile() throws IOException {
        var repository = new PlanRepository(dir);
        Files.createDirectory(dir.resolve("Subfolder"));
        Path service = dir.resolve("settings.md");
        Files.writeString(service, "private-normalized-sentinel-735");
        Path path = dir.resolve("Subfolder").resolve("..").resolve("settings.md");
        assertThrows(IOException.class, () -> repository.load(path, TODAY));
        assertThrows(IOException.class, () -> repository.save(Plan.empty("Budget", TODAY), path));
        assertThrows(IOException.class, () -> repository.rename(path, "Budget"));
        assertEquals("private-normalized-sentinel-735", Files.readString(service));
    }
}
