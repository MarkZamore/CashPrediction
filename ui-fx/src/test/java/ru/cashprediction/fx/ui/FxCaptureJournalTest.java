package ru.cashprediction.fx.ui;

import java.util.List;
import javafx.css.PseudoClass;
import javafx.scene.canvas.Canvas;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.scene.transform.Translate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет listeners настоящих узлов без toolkit; не является доказательством экранной краски. */
class FxCaptureJournalTest {
    /** Три изменения одного ленивого свойства без промежуточного stamp не теряют ABA. */
    @Test void observesRepeatedPropertyChangesWithoutIntermediateReads() {
        Pane root = new Pane();
        try (var journal = journal(root)) {
            var initial = journal.stamp(); long before = initial.epoch();
            root.setOpacity(0.5); root.setOpacity(1); root.setOpacity(0.5);
            assertEquals(before + 3, journal.stamp().epoch());
            assertEquals(0, journal.stamp().paintRevision());
            assertEquals(initial.renderGeneration() + 3, journal.stamp().renderGeneration());
            assertNull(journal.lastPaintEpoch(root));
        }
    }

    /** Повторное чтение не создаёт поколение, pulse, idle или фиктивный node-paint факт. */
    @Test void snapshotReadDoesNotWriteGenerationOrCertifyPaint() {
        Pane root = new Pane();
        try (var journal = journal(root)) {
            var before = journal.stamp(); Object readValue = new Object();
            assertSame(readValue, journal.readSnapshot(() -> readValue));
            assertSame(readValue, journal.readSnapshot(() -> readValue));
            assertEquals(before, journal.stamp());
            assertFalse(journal.stamp().postLayoutPulse());
            assertFalse(journal.stamp().mutationJournalComplete());
            assertNull(journal.lastPaintEpoch(root));
            assertTrue(journal.coverageGaps().stream().anyMatch(gap -> gap.property().equals("nativePaint")));
            assertThrows(IllegalStateException.class, () -> journal.readSnapshot(() -> {
                throw new IllegalStateException("read failed");
            }));
            assertEquals(before, journal.stamp());
        }
    }

    /** Настоящее изменение дерева внутри чтения не списывается как собственный snapshot. */
    @Test void structuralAbaDuringSnapshotReadStillChangesGeneration() {
        Pane root = new Pane(); Rectangle child = new Rectangle(2, 3);
        try (var journal = journal(root)) {
            var before = journal.stamp();
            journal.readSnapshot(() -> { root.getChildren().add(child); root.getChildren().remove(child); return root; });
            assertTrue(root.getChildren().isEmpty());
            assertTrue(journal.stamp().renderGeneration() >= before.renderGeneration() + 2);
            assertTrue(journal.stamp().epoch() >= before.epoch() + 2);
            assertFalse(journal.stamp().mutationJournalComplete());
        }
    }

    /** Свойства текста, применённого фона и геометрии берутся у настоящих объектов. */
    @Test void observesTextBackgroundAndGeometryAba() {
        Text text = new Text("A"); Pane root = new Pane(text);
        try (var journal = journal(root)) {
            var before = journal.stamp();
            text.setText("B"); text.setText("A");
            root.setBackground(new Background(new BackgroundFill(Color.RED, CornerRadii.EMPTY, null)));
            root.setBackground(null); root.resize(41.25, 23.75); root.resize(0, 0);
            assertTrue(journal.stamp().epoch() >= before.epoch() + 6);
            assertTrue(journal.stamp().layoutRevision() > before.layoutRevision());
            assertFalse(journal.stamp().postLayoutPulse());
        }
    }

    /** Поздний unmanaged ребёнок сразу наблюдается; удалённый ребёнок перестаёт удерживаться. */
    @Test void observesChildAttachDetachAndReattach() {
        Pane root = new Pane(); Rectangle child = new Rectangle(5, 7); child.setManaged(false);
        try (var journal = journal(root)) {
            long before = journal.stamp().epoch();
            root.getChildren().add(child); child.setOpacity(0.25);
            root.getChildren().remove(child);
            assertTrue(journal.stamp().epoch() >= before + 3);
            long removed = journal.stamp().epoch();
            child.setOpacity(1); assertEquals(removed, journal.stamp().epoch());
            root.getChildren().add(child);
            long attached = journal.stamp().epoch(); child.setOpacity(0.75);
            assertEquals(attached + 1, journal.stamp().epoch());
        }
    }

    /** Изменение порядка и возвращение того же дерева остаётся в истории. */
    @Test void observesChildrenOrderAba() {
        Rectangle first = new Rectangle(), second = new Rectangle(); Pane root = new Pane(first, second);
        try (var journal = journal(root)) {
            long before = journal.stamp().epoch();
            root.getChildren().setAll(second, first); root.getChildren().setAll(first, second);
            assertEquals(List.of(first, second), root.getChildren());
            assertTrue(journal.stamp().epoch() >= before + 2);
        }
    }

    /** Замены clip и transform подключают новые свойства и освобождают старые. */
    @Test void followsClipAndMutableTransform() {
        Pane root = new Pane(); Rectangle clip = new Rectangle(20, 20); Translate transform = new Translate();
        try (var journal = journal(root)) {
            root.setClip(clip); root.getTransforms().add(transform);
            long before = journal.stamp().epoch();
            clip.setWidth(17); clip.setWidth(20); transform.setX(0.125); transform.setX(0);
            assertTrue(journal.stamp().epoch() >= before + 4);
            root.setClip(null); root.getTransforms().clear();
            long detached = journal.stamp().epoch(); clip.setWidth(19); transform.setX(4);
            assertEquals(detached, journal.stamp().epoch());
        }
    }

    /** Свойства изображения и замена объекта не превращаются в факт завершения краски. */
    @Test void observesInstalledImageReplacementAndFitAba() {
        WritableImage first = new WritableImage(2, 2), second = new WritableImage(2, 2);
        ImageView view = new ImageView(first); Pane root = new Pane(view);
        try (var journal = journal(root)) {
            long before = journal.stamp().epoch();
            view.setImage(second); view.setImage(first); view.setFitWidth(17); view.setFitWidth(0);
            assertTrue(journal.stamp().epoch() >= before + 4);
            assertNull(journal.lastPaintEpoch(view));
            assertFalse(journal.stamp().mutationJournalComplete());
            assertTrue(journal.coverageGaps().stream().anyMatch(gap -> gap.source() == first && gap.property().equals("image")));
        }
    }

    /** Смена CSS, pseudo-state и semantic marker регистрируется даже с возвратом состояния. */
    @Test void observesStylePseudoStateAndProperties() {
        Pane root = new Pane(); PseudoClass state = PseudoClass.getPseudoClass("capture-test");
        try (var journal = journal(root)) {
            long before = journal.stamp().epoch();
            root.getStyleClass().add("probe"); root.getStyleClass().remove("probe");
            root.setStyle("-fx-opacity: 0.5;"); root.setStyle("");
            root.pseudoClassStateChanged(state, true); root.pseudoClassStateChanged(state, false);
            root.getProperties().put("cp.icon", "A"); root.getProperties().remove("cp.icon");
            assertTrue(journal.stamp().epoch() >= before + 8);
        }
    }

    /** Эффект наблюдается по публичным свойствам, но unknown painter и Canvas остаются пробелами. */
    @Test void effectsCanvasAndPixelWritesNeverCertifyCompleteJournal() {
        Canvas canvas = new Canvas(5, 5); Pane root = new Pane(canvas); DropShadow shadow = new DropShadow();
        root.setEffect(shadow);
        try (var journal = journal(root)) {
            long before = journal.stamp().epoch(); shadow.setRadius(8); shadow.setRadius(10);
            assertTrue(journal.stamp().epoch() >= before + 2);
            assertTrue(journal.coverageGaps().stream().anyMatch(gap -> gap.source() == canvas && gap.property().equals("canvas")));
            assertTrue(journal.coverageGaps().stream().anyMatch(gap -> gap.source() == shadow && gap.property().equals("effect")));
            assertFalse(journal.stamp().mutationJournalComplete());
            assertNull(journal.lastPaintEpoch(canvas));
        }
    }

    /** Отключение одной области сохраняет перекрывающуюся явно подключённую область. */
    @Test void overlappingScopesAndIdempotentAttach() {
        Pane child = new Pane(), root = new Pane(child);
        try (var journal = journal(root)) {
            long first = journal.stamp().epoch(); journal.attach(root);
            assertEquals(first, journal.stamp().epoch());
            journal.attach(child); journal.detach(root);
            long isolated = journal.stamp().epoch(); root.setOpacity(0.75);
            assertEquals(isolated, journal.stamp().epoch());
            long before = journal.stamp().epoch(); child.setOpacity(0.5);
            assertEquals(before + 1, journal.stamp().epoch());
            journal.detach(child); long detached = journal.stamp().epoch(); child.setOpacity(1);
            assertEquals(detached, journal.stamp().epoch());
        }
    }

    /** Проверка потока и закрытие не оставляют доступного журнала с фиктивными поколениями. */
    @Test void enforcesThreadAndClosedLifecycle() {
        var wrongThread = new FxCaptureJournal(() -> false);
        assertThrows(IllegalStateException.class, wrongThread::stamp);
        Pane root = new Pane(); var journal = journal(root);
        journal.close(); journal.close(); root.setOpacity(0.5);
        assertThrows(IllegalStateException.class, journal::stamp);
        assertThrows(IllegalStateException.class, () -> journal.attach(root));
        assertThrows(IllegalStateException.class, journal::coverageGaps);
    }

    /** Создаёт журнал настоящего дерева, заменяя только проверку FX-потока. */
    private static FxCaptureJournal journal(Pane root) {
        var journal = new FxCaptureJournal(() -> true); journal.attach(root); return journal;
    }
}
