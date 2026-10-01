package ru.cashprediction.core.app.file;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.cashprediction.core.app.AppClock;
import ru.cashprediction.core.app.AppController;
import ru.cashprediction.core.app.AppEnvironment;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.LaunchOptions;
import ru.cashprediction.core.app.fake.FakeUiPort;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.io.PlanRepository;
import ru.cashprediction.core.markdown.PlanMarkdownWriter;
import ru.cashprediction.core.markdown.SettingsMarkdown;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.ui.form.FormSession;
import ru.cashprediction.core.ui.form.Problem;
import ru.cashprediction.core.ui.text.UiText;

/** Проверяет защиту от потери внешних правок через настоящий запуск контроллера и форму переименования. */
class StartupRenameConflictRegressionTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    @TempDir Path home;

    /** Последний файл и первый файл папки получают защиту как для ручной, так и для автоматической записи. */
    @ParameterizedTest
    @CsvSource({"true,false", "false,false", "true,true", "false,true"})
    void startupLoadedFileRequiresConflictDecision(boolean lastPlan, boolean automatic) throws Exception {
        try (Fixture fixture = new Fixture(home, lastPlan, automatic)) {
            String external = fixture.changeDisk();
            fixture.app.document().edit("test", plan -> plan.withCushion(Money.parse("17")));
            Plan draft = fixture.app.document().plan();
            AtomicBoolean continued = new AtomicBoolean();
            if (automatic) fixture.port.manualScheduler().advance(java.time.Duration.ofSeconds(1));
            else fixture.app.files().save(() -> continued.set(true));
            assertEquals("externalChange", fixture.port.pendingAlerts().getLast().spec().purpose());
            assertEquals(external, Files.readString(fixture.file));
            fixture.port.pendingAlerts().getLast().press("cancel");
            assertFalse(continued.get());
            assertEquals(draft, fixture.app.document().plan());
            assertTrue(fixture.app.document().isDirty());
            assertTrue(fixture.app.externalChanges().changedExternally(fixture.file));
        }
    }

    /** Конфликт не закрывает свежую форму и не меняет файл, текущий план, историю и введённое имя. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void freshRenamePreservesCleanOrDirtyDocumentAndDraftOnExternalChange(boolean dirty) throws Exception {
        try (Fixture fixture = new Fixture(home, true, false)) {
            if (dirty) fixture.app.document().edit("test", plan -> plan.withCushion(Money.parse("17")));
            Plan current = fixture.app.document().plan();
            var history = fixture.app.document().undoDescription();
            String external = fixture.changeDisk();
            fixture.app.files().rename();
            FormSession form = fixture.port.forms().getLast().session();
            form.shown();
            form.fieldChanged("value", "Renamed", true, 1);
            form.buttonPressed("rename");
            assertFalse(form.isClosed());
            assertEquals("Renamed", form.state().value("value"));
            assertEquals(Problem.Severity.ERROR, form.view().problem().severity());
            assertEquals(UiText.get("alert.external.header", "Current"), form.view().problem().text());
            assertEquals(current, fixture.app.document().plan());
            assertEquals(dirty, fixture.app.document().isDirty());
            assertEquals(history, fixture.app.document().undoDescription());
            assertEquals(fixture.file, fixture.app.document().file().orElseThrow());
            assertEquals(external, Files.readString(fixture.file));
            assertFalse(Files.exists(fixture.file.resolveSibling("Renamed.md")));
            assertTrue(fixture.app.externalChanges().changedExternally(fixture.file));
            form.closeRequested();
            fixture.app.files().save(null);
            assertEquals("externalChange", fixture.port.pendingAlerts().getLast().spec().purpose());
            assertEquals(external, Files.readString(fixture.file));
        }
    }

    /** Публичный путь для восстановленной формы отказывает без записи и без снятия внешнего конфликта. */
    @Test
    void reusableRenameRejectsConflictBeforeAnyMutation() throws Exception {
        try (Fixture fixture = new Fixture(home, true, false)) {
            fixture.app.document().edit("test", plan -> plan.withCushion(Money.parse("17")));
            Plan draft = fixture.app.document().plan();
            AppSettings settings = fixture.app.state().settings();
            String external = fixture.changeDisk();
            assertThrows(IllegalArgumentException.class, () -> fixture.app.files().renameTo("Renamed"));
            assertEquals(draft, fixture.app.document().plan());
            assertTrue(fixture.app.document().isDirty());
            assertEquals(settings, fixture.app.state().settings());
            assertEquals(fixture.file, fixture.app.document().file().orElseThrow());
            assertEquals(external, Files.readString(fixture.file));
            assertFalse(Files.exists(fixture.file.resolveSibling("Renamed.md")));
            assertTrue(fixture.app.externalChanges().changedExternally(fixture.file));
        }
    }

    /** Обычное переименование не записывает черновик на диск и запоминает метку только принятого файла. */
    @Test
    void reusableRenamePreservesDirtyEditsWhenDiskHasNotChanged() throws Exception {
        try (Fixture fixture = new Fixture(home, true, false)) {
            Plan disk = fixture.app.document().plan();
            fixture.app.document().edit("test", plan -> plan.withCushion(Money.parse("17")));
            Plan draft = fixture.app.document().plan();
            fixture.app.files().renameTo("Renamed");
            Path renamed = fixture.file.resolveSibling("Renamed.md");
            assertFalse(Files.exists(fixture.file));
            assertEquals(renamed, fixture.app.document().file().orElseThrow());
            assertEquals(draft.withName("Renamed"), fixture.app.document().plan());
            assertTrue(fixture.app.document().isDirty());
            assertEquals(disk.withName("Renamed"), new PlanRepository(renamed.getParent()).load(renamed, TODAY).plan());
            assertFalse(fixture.app.externalChanges().changedExternally(renamed));
        }
    }

    /** Пропавший исходный файл не приводит к созданию нового файла из устаревшего документа. */
    @Test
    void reusableRenameRejectsDeletedFileAndPreservesDraft() throws Exception {
        try (Fixture fixture = new Fixture(home, true, false)) {
            Plan current = fixture.app.document().plan();
            Files.delete(fixture.file);
            assertThrows(IllegalArgumentException.class, () -> fixture.app.files().renameTo("Renamed"));
            assertEquals(current, fixture.app.document().plan());
            assertEquals(fixture.file, fixture.app.document().file().orElseThrow());
            assertFalse(Files.exists(fixture.file.resolveSibling("Renamed.md")));
            assertTrue(fixture.app.externalChanges().changedExternally(fixture.file));
        }
    }

    /** Изолирует настройки, файлы, реестр в памяти и виртуальные таймеры каждого настоящего контроллера. */
    private static final class Fixture implements AutoCloseable {
        private final Path file;
        private final FakeUiPort port = new FakeUiPort(ClientProfile.swing());
        private final AppController app;

        /** Создаёт план и настройки до запуска, чтобы файл открывался именно через StartupFlow. */
        private Fixture(Path home, boolean lastPlan, boolean autosave) throws Exception {
            Path memory = Files.createDirectories(home.resolve("CashMemory"));
            file = memory.resolve("Current.md");
            new PlanRepository(memory).save(Plan.empty("Current", TODAY), file);
            SettingsMarkdown.save(memory.resolve("settings.md"), AppSettings.defaults().withAutosave(autosave)
                    .withLastPlan(lastPlan ? file.toString() : "missing.md"));
            app = new AppController(port, new AppEnvironment(LaunchOptions.parse("--registry", "memory"),
                    home, memory, AppClock.fixedToday(TODAY)));
            app.start();
            assertEquals(file, app.document().file().orElseThrow());
            assertTrue(port.pendingAlerts().isEmpty());
        }

        /** Вносит отличающуюся правку диска с гарантированно новой меткой, не меняя план контроллера. */
        private String changeDisk() throws Exception {
            FileTime previous = Files.getLastModifiedTime(file);
            String external = PlanMarkdownWriter.write(Plan.empty("Current", TODAY).withCushion(Money.parse("91")));
            Files.writeString(file, external);
            Files.setLastModifiedTime(file, FileTime.fromMillis(previous.toMillis() + 5000));
            return external;
        }

        /** Завершает только локальную запись и виртуальный планировщик теста. */
        @Override public void close() {
            if (app.recorder() != null) app.recorder().shutdownClean();
            port.scheduler().shutdown();
        }
    }
}
