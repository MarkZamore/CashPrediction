package ru.cashprediction.fx.ui;

import java.util.concurrent.FutureTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Отдельная opt-in проверка настоящего Scene.snapshot и callbacks показанной сцены.
 * Запускается владельцем интеграции с -Dfx.captureJournalSnapshots=true;
 * факт snapshot не подменяет pulse или отсутствие hook краски узла.
 */
@EnabledIfSystemProperty(named = "fx.captureJournalSnapshots", matches = "true")
class FxCaptureJournalSnapshotTest {
    /** Запускает toolkit только для явно включённого нативного теста. */
    @BeforeAll static void startToolkit() {
        Platform.startup(() -> Platform.setImplicitExit(false));
    }

    /** Закрывает toolkit отдельного opt-in запуска. */
    @AfterAll static void stopToolkit() { Platform.exit(); }

    /** Snapshot даёт реальные пиксели и отметку поколения без записи generation или эпохи краски. */
    @Test void snapshotCompletionIsDistinctFromNodePaintAndPulse() throws Exception {
        onFx(() -> {
            Rectangle rectangle = new Rectangle(16, 16, Color.RED); Pane root = new Pane(rectangle);
            Scene scene = new Scene(root, 16, 16);
            try (var journal = new FxCaptureJournal()) {
                journal.attach(scene);
                assertNull(journal.lastSnapshotGeneration(scene));
                var first = journal.snapshot(scene);
                assertEquals(0xffff0000, first.getPixelReader().getArgb(8, 8));
                var afterFirst = journal.stamp();
                assertEquals(Long.valueOf(afterFirst.renderGeneration()), journal.lastSnapshotGeneration(scene));
                var repeated = journal.snapshot(scene);
                assertEquals(0xffff0000, repeated.getPixelReader().getArgb(8, 8));
                assertEquals(afterFirst, journal.stamp());
                assertTrue(FxPaintCollector.generationChanges(afterFirst, journal.stamp()).isEmpty());
                assertNull(journal.lastPaintEpoch(rectangle));
                assertFalse(journal.stamp().postLayoutPulse());
                assertFalse(journal.stamp().mutationJournalComplete());
                rectangle.setFill(Color.BLUE);
                var beforeSecond = journal.stamp();
                assertTrue(beforeSecond.renderGeneration() > afterFirst.renderGeneration());
                var second = journal.snapshot(scene);
                assertEquals(0xff0000ff, second.getPixelReader().getArgb(8, 8));
                assertEquals(beforeSecond, journal.stamp());
                assertEquals(Long.valueOf(beforeSecond.renderGeneration()), journal.lastSnapshotGeneration(scene));
                assertEquals(0, journal.stamp().paintRevision());
                assertNull(journal.lastPaintEpoch(root));
            }
        });
    }

    /** Generation меняется от настоящего callback; requestNextPulse сам по себе не считается краской. */
    @Test void realPostLayoutCallbackChangesGenerationAndDetachStopsObservation() throws Exception {
        Stage[] stage = new Stage[1]; Scene[] scene = new Scene[1]; FxCaptureJournal[] journal = new FxCaptureJournal[1];
        FxPaintCollector.Stamp[] before = new FxPaintCollector.Stamp[1];
        CompletableFuture<FxPaintCollector.Stamp> first = new CompletableFuture<>();
        try {
            onFx(() -> {
                scene[0] = new Scene(new Pane(new Rectangle(16, 16, Color.RED)), 32, 32);
                stage[0] = new Stage(); stage[0].setScene(scene[0]); stage[0].show();
                journal[0] = new FxCaptureJournal(); journal[0].attach(scene[0]);
                before[0] = journal[0].stamp(); observeNextPulse(scene[0], journal[0], first);
                assertEquals(before[0], journal[0].stamp());
            });
            var after = first.get(10, TimeUnit.SECONDS);
            assertTrue(after.renderGeneration() > before[0].renderGeneration());
            assertTrue(after.layoutRevision() > before[0].layoutRevision());
            assertTrue(FxPaintCollector.generationChanges(before[0], after).contains("render/pulse generation changed"));
            assertFalse(after.mutationJournalComplete()); assertEquals(0, after.paintRevision());
            CompletableFuture<FxPaintCollector.Stamp> detached = new CompletableFuture<>();
            onFx(() -> {
                assertNull(journal[0].lastPaintEpoch(scene[0].getRoot()));
                journal[0].detach(scene[0]); before[0] = journal[0].stamp();
                observeNextPulse(scene[0], journal[0], detached);
            });
            assertEquals(before[0], detached.get(10, TimeUnit.SECONDS));
        } finally {
            onFx(() -> { if (journal[0] != null) journal[0].close(); if (stage[0] != null) stage[0].close(); });
        }
    }

    /** Подписывается на реальный callback и читает stamp после завершения обработчиков текущего pulse. */
    private static void observeNextPulse(Scene scene, FxCaptureJournal journal,
                                         CompletableFuture<FxPaintCollector.Stamp> result) {
        Runnable[] listener = new Runnable[1];
        listener[0] = () -> {
            scene.removePostLayoutPulseListener(listener[0]);
            Platform.runLater(() -> {
                try { result.complete(journal.stamp()); }
                catch (RuntimeException error) { result.completeExceptionally(error); }
            });
        };
        scene.addPostLayoutPulseListener(listener[0]); Platform.requestNextPulse();
    }

    /** Root ABA сохраняется, удалённая сцена не наблюдается и не получает snapshot generation. */
    @Test void sceneRootAbaAndDetachReleaseListeners() throws Exception {
        onFx(() -> {
            Pane first = new Pane(), second = new Pane(); Scene scene = new Scene(first, 16, 16);
            try (var journal = new FxCaptureJournal()) {
                journal.attach(scene); long before = journal.stamp().epoch();
                scene.setRoot(second); second.setOpacity(0.5); scene.setRoot(first);
                assertTrue(journal.stamp().epoch() >= before + 3);
                long restored = journal.stamp().epoch(); second.setOpacity(1);
                assertEquals(restored, journal.stamp().epoch());
                journal.detach(scene); long detached = journal.stamp().epoch();
                scene.setFill(Color.RED); first.setOpacity(0.5);
                assertEquals(detached, journal.stamp().epoch());
                assertThrows(IllegalArgumentException.class, () -> journal.snapshot(scene));
                assertThrows(IllegalArgumentException.class, () -> journal.lastSnapshotGeneration(scene));
                journal.attach(scene); assertNull(journal.lastSnapshotGeneration(scene));
                assertFalse(journal.stamp().postLayoutPulse());
            }
        });
    }

    /** Исполняет утверждения на настоящем FX-потоке; ожидание находится вне него. */
    private static void onFx(Runnable action) throws Exception {
        var task = new FutureTask<Void>(() -> { action.run(); return null; });
        Platform.runLater(task); task.get(10, TimeUnit.SECONDS);
    }
}
