package ru.cashprediction.core.service.storage;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.markdown.PlanMarkdownWriter;
import ru.cashprediction.core.model.Plan;

/** Синхронизирует отдельный writer после observe; доказательство порядка строится на latch, не sleep. */
class GuardedMutationWindowTest {
    @TempDir Path directory;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

    @Test void saveRejectsEditorAfterObserve() throws Exception { exercise(false, false); }
    @Test void renameRejectsEditorAfterObserve() throws Exception { exercise(true, false); }
    @Test void createRejectsEditorAfterAbsentObserve() throws Exception { exercise(false, true); }

    /** Барьер отпускает приложение только после завершённой реальной внешней записи. */
    private void exercise(boolean rename, boolean absent) throws Exception {
        Path file = directory.resolve("A.md");
        Plan plan = Plan.empty("A", TODAY);
        if (!absent) Files.writeString(file, PlanMarkdownWriter.write(plan));
        CountDownLatch observed = new CountDownLatch(1), edited = new CountDownLatch(1);
        FilePlanStorage storage = new FilePlanStorage(directory, () -> {
            observed.countDown();
            try { assertTrue(edited.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException interruption) { Thread.currentThread().interrupt(); throw new AssertionError(interruption); }
        });
        var reference = FilePlanStorage.reference(file);
        var version = storage.version(reference).requireValue();
        String outside = PlanMarkdownWriter.write(plan.withStart(TODAY.minusDays(1), plan.startBalance())) + "\n<!-- editor -->\n";
        try (var executor = Executors.newSingleThreadExecutor()) {
            var writer = executor.submit(() -> {
                assertTrue(observed.await(10, TimeUnit.SECONDS));
                try { Files.writeString(file, outside); } finally { edited.countDown(); }
                return null;
            });
            var result = rename ? storage.rename(reference, "B", version) : storage.write(reference, plan, version);
            writer.get(10, TimeUnit.SECONDS);
            assertFalse(result.succeeded());
            assertEquals(PlanStorage.Code.CONFLICT, result.problem().code());
            assertEquals(outside, Files.readString(file));
            assertFalse(Files.exists(directory.resolve("B.md")));
            try (var children = Files.list(directory)) { assertEquals(1, children.count()); }
        }
    }
}
