package ru.cashprediction.core.app.session.capture;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.flow.ExternalChangeGuard;
import ru.cashprediction.core.app.flow.FlowContext;
import ru.cashprediction.core.app.flow.SessionBridge;
import ru.cashprediction.core.diagnostics.Diagnostic;
import ru.cashprediction.core.markdown.MarkdownParseException;
import ru.cashprediction.core.markdown.PlanMarkdownReader;
import ru.cashprediction.core.markdown.PlanMarkdownWriter;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.service.storage.FilePlanStorage;
import ru.cashprediction.core.service.storage.PlanStorage;
import ru.cashprediction.core.session.MainWindowState;
import ru.cashprediction.core.session.PlanState;
import ru.cashprediction.core.session.RestoreCoordinator;
import ru.cashprediction.core.session.RestoreReport;
import ru.cashprediction.core.session.SessionMarker;
import ru.cashprediction.core.session.SessionRecorder;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.session.store.RegistrySessionStore;
import ru.cashprediction.core.ui.text.UiText;

/** Проверяет порт хранения в восстановлении плана и защиту исходного dirty-снимка от неудачного разбора. */
class SessionBridgeStorageTest {
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");
    @TempDir Path home;

    @Test void cleanPlanReadsInjectedStorageWithOpaqueReferenceWithoutAFile() {
        Fixture fixture = fixture(home);
        Path file = fixture.ui.environment.cashMemory().resolve("nested/Stored.md");
        var reference = FilePlanStorage.reference(file);
        Plan stored = Plan.empty("Stored", CaptureContext.TODAY);
        var version = new PlanStorage.Version("stored-version");
        fixture.storage.readResult = PlanStorage.Result.success(new PlanStorage.Snapshot(reference, version, stored, List.of()));
        fixture.storage.currentVersion = version;
        List<String> warnings = new ArrayList<>();

        fixture.bridge.loadPlan(PlanState.CLEAN, "nested/Stored.md", warnings::add);

        assertEquals(List.of("read"), fixture.storage.calls);
        assertEquals(reference, fixture.storage.readReference);
        assertEquals(CaptureContext.TODAY, fixture.storage.readToday);
        assertEquals(Optional.empty(), fixture.storage.readExpected);
        assertEquals(stored, fixture.ui.document.plan());
        assertEquals(file, fixture.ui.document.file().orElseThrow());
        assertFalse(fixture.ui.document.isDirty());
        assertEquals(version, fixture.guard.expectedVersion(reference, PlanStorage.Version.ABSENT));
        assertTrue(warnings.isEmpty());
        assertFalse(Files.exists(file));
        assertEquals(PlanState.CLEAN, fixture.bridge.capturePlan());
        assertEquals(Path.of("nested/Stored.md").toString(), fixture.bridge.captureMain().planPath());
    }

    @Test void cleanDiagnosticsAndUndoResetUseReturnedSnapshotWithoutObservingANewerVersion() {
        Fixture fixture = fixture(home);
        seedHistory(fixture);
        Path file = home.resolve("external.md").toAbsolutePath().normalize();
        var reference = FilePlanStorage.reference(file);
        var readVersion = new PlanStorage.Version("read-version");
        var outsideVersion = new PlanStorage.Version("outside-version");
        Plan stored = Plan.empty("external", CaptureContext.TODAY);
        List<Diagnostic> diagnostics = List.of(Diagnostic.warning("fixture"));
        fixture.storage.readResult = PlanStorage.Result.success(new PlanStorage.Snapshot(reference, readVersion, stored, diagnostics));
        fixture.storage.currentVersion = outsideVersion;
        List<String> warnings = new ArrayList<>();

        fixture.bridge.loadPlan(PlanState.CLEAN, file.toString(), warnings::add);

        assertEquals(List.of("read"), fixture.storage.calls);
        assertEquals(stored, fixture.ui.document.plan());
        assertEquals(diagnostics, fixture.ui.document.loadDiagnostics());
        assertFalse(fixture.ui.document.isDirty());
        assertFalse(fixture.ui.document.canUndo());
        assertFalse(fixture.ui.document.canRedo());
        assertEquals(List.of(UiText.get("restore.warn.planDiag", file)), warnings);
        assertEquals(readVersion, fixture.guard.expectedVersion(reference, outsideVersion));
        assertTrue(fixture.guard.changedExternally(file));
        assertEquals(file.toString(), fixture.bridge.captureMain().planPath());
    }

    @Test void typedCleanFailuresKeepLocalizedWarningsAndSafeEmptyFallback() {
        for (PlanStorage.Code code : PlanStorage.Code.values()) {
            Fixture fixture = fixture(home.resolve(code.name()));
            seedHistory(fixture);
            Path file = fixture.ui.environment.cashMemory().resolve("Stored.md");
            var reference = FilePlanStorage.reference(file);
            var oldVersion = new PlanStorage.Version("old-version");
            fixture.guard.remember(reference, oldVersion);
            String detail = code == PlanStorage.Code.CONFLICT ? "" : "fixture detail";
            var problem = new PlanStorage.Problem(code, code == PlanStorage.Code.CONFLICT
                    ? PlanStorage.Conflict.VERSION_CHANGED : PlanStorage.Conflict.NONE, detail);
            fixture.storage.readResult = PlanStorage.Result.failure(problem);
            List<String> warnings = new ArrayList<>();

            fixture.bridge.loadPlan(PlanState.CLEAN, "Stored.md", warnings::add);

            String first = switch (code) {
                case MISSING -> UiText.get("restore.warn.planMissing", file);
                case CORRUPT -> UiText.get("restore.warn.notPlan", file, detail);
                case IO_ERROR -> UiText.get("restore.warn.readPlan", file, detail);
                case CONFLICT -> UiText.get("restore.warn.readPlan", file, UiText.get("alert.external.header", "Stored"));
            };
            assertEquals(List.of(first, UiText.get("restore.warn.emptyPlan", file)), warnings);
            assertEquals(Plan.empty(UiText.get("plan.defaultName"), CaptureContext.TODAY), fixture.ui.document.plan());
            assertTrue(fixture.ui.document.file().isEmpty());
            assertFalse(fixture.ui.document.isDirty());
            assertFalse(fixture.ui.document.canUndo());
            assertFalse(fixture.ui.document.canRedo());
            assertTrue(fixture.ui.document.loadDiagnostics().isEmpty());
            assertEquals(PlanStorage.Version.ABSENT, fixture.guard.expectedVersion(reference, PlanStorage.Version.ABSENT));
            assertEquals(List.of("read"), fixture.storage.calls);
        }
    }

    @Test void unexpectedStorageExceptionDoesNotReplaceDocumentHistoryOrGuard() {
        Fixture fixture = fixture(home);
        seedHistory(fixture);
        Plan before = fixture.ui.document.plan();
        var reference = FilePlanStorage.reference(fixture.ui.environment.cashMemory().resolve("Current.md"));
        var version = new PlanStorage.Version("current-version");
        fixture.guard.remember(reference, version);
        fixture.storage.readFailure = new IllegalStateException("fixture failure");
        List<String> warnings = new ArrayList<>();

        assertSame(fixture.storage.readFailure, assertThrows(IllegalStateException.class,
                () -> fixture.bridge.loadPlan(PlanState.CLEAN, "Other.md", warnings::add)));

        assertSame(before, fixture.ui.document.plan());
        assertEquals(fixture.ui.environment.cashMemory().resolve("Current.md"), fixture.ui.document.file().orElseThrow());
        assertTrue(fixture.ui.document.isDirty());
        assertTrue(fixture.ui.document.canUndo());
        assertTrue(fixture.ui.document.canRedo());
        assertEquals(version, fixture.guard.expectedVersion(reference, PlanStorage.Version.ABSENT));
        assertTrue(warnings.isEmpty());
        assertEquals(List.of("read"), fixture.storage.calls);
    }

    @Test void dirtyRawTextWinsOverStorageAndKeepsNamePathDiagnosticsAndRawBlocks() {
        Fixture fixture = fixture(home);
        seedHistory(fixture);
        Path file = fixture.ui.environment.cashMemory().resolve("Stored.md");
        var reference = FilePlanStorage.reference(file);
        var diskVersion = new PlanStorage.Version("disk-version");
        fixture.storage.currentVersion = diskVersion;
        fixture.storage.readFailure = new IllegalStateException("Dirty restore must not read storage");
        String raw = PlanMarkdownWriter.write(Plan.empty("Snapshot name", CaptureContext.TODAY))
                + "\n## Unknown block\nraw-snapshot-marker\n";
        var parsed = PlanMarkdownReader.read(raw, "Stored", CaptureContext.TODAY);
        List<String> warnings = new ArrayList<>();

        fixture.bridge.loadPlan(PlanState.dirty(raw), "Stored.md", warnings::add);

        assertEquals(parsed.plan(), fixture.ui.document.plan());
        assertEquals("Snapshot name", fixture.ui.document.plan().name());
        assertEquals(file, fixture.ui.document.file().orElseThrow());
        assertTrue(fixture.ui.document.isDirty());
        assertFalse(fixture.ui.document.canUndo());
        assertFalse(fixture.ui.document.canRedo());
        assertEquals(parsed.diagnostics(), fixture.ui.document.loadDiagnostics());
        assertEquals(List.of(UiText.get("restore.warn.dirtyPlanDiag")), warnings);
        assertEquals(List.of("version"), fixture.storage.calls);
        assertEquals(diskVersion, fixture.guard.expectedVersion(reference, PlanStorage.Version.ABSENT));
        assertTrue(fixture.bridge.capturePlan().markdown().contains("raw-snapshot-marker"));
        assertFalse(Files.exists(file));
    }

    @Test void failedDirtyParseKeepsOldPlanDirtyUndoRedoAndGuardWithoutStorageCalls() {
        Fixture fixture = fixture(home);
        seedHistory(fixture);
        Plan before = fixture.ui.document.plan();
        var reference = FilePlanStorage.reference(fixture.ui.environment.cashMemory().resolve("Current.md"));
        var version = new PlanStorage.Version("current-version");
        fixture.guard.remember(reference, version);
        List<String> warnings = new ArrayList<>();

        assertThrows(MarkdownParseException.class,
                () -> fixture.bridge.loadPlan(PlanState.dirty("not a plan"), "Other.md", warnings::add));

        assertSame(before, fixture.ui.document.plan());
        assertEquals(fixture.ui.environment.cashMemory().resolve("Current.md"), fixture.ui.document.file().orElseThrow());
        assertTrue(fixture.ui.document.isDirty());
        assertTrue(fixture.ui.document.canUndo());
        assertTrue(fixture.ui.document.canRedo());
        assertEquals(version, fixture.guard.expectedVersion(reference, PlanStorage.Version.ABSENT));
        assertTrue(fixture.storage.calls.isEmpty());
        assertTrue(warnings.isEmpty());
    }

    @Test void blankAndInvalidPathsRetainRawRestoreAndNeverReadStorage() {
        for (String path : List.of("", "\0invalid.md")) {
            Fixture fixture = fixture(home);
            String raw = PlanMarkdownWriter.write(Plan.empty("Snapshot name", CaptureContext.TODAY));
            List<String> warnings = new ArrayList<>();

            fixture.bridge.loadPlan(PlanState.dirty(raw), path, warnings::add);

            assertEquals("Snapshot name", fixture.ui.document.plan().name());
            assertTrue(fixture.ui.document.isDirty());
            assertTrue(fixture.ui.document.file().isEmpty());
            assertTrue(fixture.storage.calls.isEmpty());
            assertEquals(path.isEmpty() ? List.of() : List.of(UiText.get("restore.warn.badPath", path)), warnings);
        }
    }

    @Test void failedDirtyRestoreDoesNotStartRecorderOrOverwriteOriginalSnapshotAndMarker() throws Exception {
        Fixture fixture = fixture(home);
        seedHistory(fixture);
        Plan before = fixture.ui.document.plan();
        PlanState raw = PlanState.dirty("unreadable original snapshot");
        var snapshot = SessionSnapshot.of(NOW, "fx", MainWindowState.empty().withPlanPath("Stored.md"), raw, List.of());
        var marker = SessionMarker.running(1, NOW, "fx");
        RegistrySessionStore store = RegistrySessionStore.inMemory("fx", fixture.ui.environment.cashMemory());
        store.markDirty(marker);
        store.save(snapshot);
        SessionRecorder recorder = new SessionRecorder("fx", List.of(store), fixture.ui.port.executor(), fixture.bridge,
                fixture.ui.port.scheduler(), Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC), 2);
        List<RestoreReport> reports = new ArrayList<>();

        new RestoreCoordinator().restore(snapshot, fixture.bridge,
                (state, owner, shown, failed) -> fail("No windows expected"), recorder, reports::add);
        fixture.ui.port.manualScheduler().advance(Duration.ofSeconds(10));

        assertFalse(recorder.isStarted());
        assertEquals(1, reports.size());
        assertTrue(reports.getFirst().warnings().contains(RestoreCoordinator.RECORDER_NOT_STARTED));
        assertEquals(snapshot, store.load().orElseThrow());
        assertEquals(marker, store.readMarker().orElseThrow());
        assertEquals(raw.markdown(), store.load().orElseThrow().plan().markdown());
        assertSame(before, fixture.ui.document.plan());
        assertTrue(fixture.ui.document.isDirty());
        assertTrue(fixture.ui.document.canUndo());
        assertTrue(fixture.ui.document.canRedo());
        assertTrue(fixture.storage.calls.isEmpty());
    }

    /** Создаёт одновременно доступные undo и redo для проверки сохранения истории при ошибке разбора. */
    private void seedHistory(Fixture fixture) {
        LocalDate start = fixture.ui.document.plan().startDate();
        fixture.ui.document.markSaved(fixture.ui.environment.cashMemory().resolve("Current.md"));
        fixture.ui.document.edit("first", plan -> plan.withStart(start.minusDays(1), plan.startBalance()));
        fixture.ui.document.edit("second", plan -> plan.withStart(start.minusDays(2), plan.startBalance()));
        fixture.ui.document.undo();
        assertTrue(fixture.ui.document.isDirty());
        assertTrue(fixture.ui.document.canUndo());
        assertTrue(fixture.ui.document.canRedo());
    }

    /** Внедряет сервис через настоящий FlowContext, не меняя общий тестовый контекст других задач. */
    private Fixture fixture(Path directory) {
        CaptureContext ui = new CaptureContext(directory, ClientProfile.fx("25"));
        TrackingStorage storage = new TrackingStorage();
        ExternalChangeGuard guard = new ExternalChangeGuard(storage);
        FlowContext context = (FlowContext) Proxy.newProxyInstance(FlowContext.class.getClassLoader(),
                new Class<?>[] { FlowContext.class }, (proxy, method, args) -> switch (method.getName()) {
                    case "planStorage" -> storage;
                    case "externalChanges" -> guard;
                    default -> ui.invoke(proxy, method, args);
                });
        return new Fixture(ui, storage, guard, new SessionBridge(context));
    }

    /** Участники одного сценария восстановления. */
    private record Fixture(CaptureContext ui, TrackingStorage storage, ExternalChangeGuard guard, SessionBridge bridge) { }

    /** Хранилище без файлов: разрешает только чтение и наблюдение версии, запрещает любые изменения данных. */
    private static final class TrackingStorage implements PlanStorage {
        private final List<String> calls = new ArrayList<>();
        private Result<Snapshot> readResult;
        private RuntimeException readFailure;
        private Version currentVersion = Version.ABSENT;
        private Reference readReference;
        private LocalDate readToday;
        private Optional<Version> readExpected;

        /** {@inheritDoc} Не используется восстановлением одного плана. */
        @Override public Result<List<Entry>> list(Collection collection) { throw new AssertionError("Unexpected list"); }
        /** {@inheritDoc} Наблюдение версии dirty-плана не читает и не перезаписывает его содержимое. */
        @Override public Result<Version> version(Reference reference) {
            calls.add("version");
            return Result.success(currentVersion);
        }
        /** {@inheritDoc} Возвращает ровно заданный исход и запоминает аргументы настоящего потребителя. */
        @Override public Result<Snapshot> read(Reference reference, LocalDate today, Optional<Version> expectedVersion) {
            calls.add("read");
            readReference = reference;
            readToday = today;
            readExpected = expectedVersion;
            if (readFailure != null) throw readFailure;
            if (readResult == null) throw new AssertionError("Unexpected read");
            return readResult;
        }
        /** {@inheritDoc} Восстановление не имеет права записывать файл плана. */
        @Override public Result<Stored> write(Reference reference, Plan plan, Version expectedVersion) {
            throw new AssertionError("Unexpected write");
        }
        /** {@inheritDoc} Восстановление не имеет права переименовывать файл плана. */
        @Override public Result<Stored> rename(Reference reference, String name, Version expectedVersion) {
            throw new AssertionError("Unexpected rename");
        }
    }
}
