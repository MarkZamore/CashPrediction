package ru.cashprediction.swing.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.AbstractBorder;
import javax.swing.border.LineBorder;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.selftest.paint.PaintCaptureRequest;
import ru.cashprediction.core.ui.selftest.paint.PaintObservation;
import ru.cashprediction.core.ui.selftest.paint.PaintObservationCodec;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет actual-геометрию и fail-closed диагностику без запуска окон и Robot. */
class SwingPaintCollectorTest {
    /** Arc width является диаметром пути; centered stroke расширяет внешний радиус на половину толщины. */
    @Test void roundRectDiameterAndCenteredStrokeAreResolvedSeparatelyFromFill() throws Exception {
        edt(() -> {
            JPanel card = new JPanel(); card.setSize(100, 50); var errors = new ArrayList<PaintObservation.Unsupported>();
            var shape = new RoundRectangle2D.Double(0.5, 0.5, 99, 49, 11, 9);
            var fill = SwingPaintCollector.background(card, draw(shape, true, 1), errors, "card");
            var border = SwingPaintCollector.border(card, draw(shape, false, 1), errors, "card");
            assertTrue(errors.stream().anyMatch(u -> u.property().equals("border.radius")));
            assertEquals(5.5, fill.radii().getFirst().rx()); assertEquals(4.5, fill.radii().getFirst().ry());
            assertEquals(6, border.radii().getFirst().rx()); assertEquals(5, border.radii().getFirst().ry());
            assertEquals(11, border.radii().getFirst().rawRx()); assertEquals(5.5, border.pathRadii().getFirst().rx());
            assertEquals(0, border.insets().left()); assertEquals(0.5, fill.insets().left()); assertEquals("centered", border.strokePlacement());
            return null;
        });
    }

    /** Квадратная заливка не получает радиус скруглённой рамки или константу DesignTokens. */
    @Test void squareFillAndRoundedBorderKeepDifferentGeometry() throws Exception {
        edt(() -> {
            JPanel card = new JPanel(); card.setSize(100, 50); var errors = new ArrayList<PaintObservation.Unsupported>();
            var fill = SwingPaintCollector.background(card, draw(new Rectangle2D.Double(0, 0, 100, 50), true, 1), errors, "card");
            var border = SwingPaintCollector.border(card, draw(new RoundRectangle2D.Double(0.5, 0.5, 99, 49, 11, 11), false, 1), errors, "card");
            assertEquals(0, fill.radii().getFirst().rx()); assertEquals(6, border.radii().getFirst().rx());
            return null;
        });
    }

    /** Реальные CompoundBorder и LineBorder сохраняют все слои, ширины, цвета и отступы. */
    @Test void readsNestedSquareBordersWithoutTokenSubstitution() throws Exception {
        edt(() -> {
            JPanel card = new JPanel(); card.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createCompoundBorder(new LineBorder(new Color(0xff123456, true), 3), BorderFactory.createEmptyBorder(2, 4, 6, 8)),
                    new LineBorder(new Color(0xffabcdef, true), 2)));
            var errors = new ArrayList<PaintObservation.Unsupported>(); var layers = SwingPaintCollector.configuredBorders(card, errors, "card");
            assertTrue(errors.isEmpty()); assertEquals(2, layers.size()); assertEquals(3, layers.getFirst().widths().getFirst().doubleValue());
            assertEquals(0xff123456, layers.getFirst().colors().getFirst().intValue()); assertEquals(0, layers.getFirst().radii().getFirst().rx());
            // EmptyBorder(2,4,6,8) имеет left=4, right=8; наружная LineBorder добавляет 3.
            assertEquals(7, layers.getLast().insets().left()); assertEquals(11, layers.getLast().insets().right());
            assertEquals(5, layers.getLast().insets().top());
            assertEquals(9, layers.getLast().insets().bottom()); assertEquals(0xffabcdef, layers.getLast().colors().getFirst().intValue());
            return null;
        });
    }

    /** Незнакомый Border и округлённый LineBorder не маскируются под поддержанный radius=6. */
    @Test void unknownBordersFailClosed() throws Exception {
        edt(() -> {
            JPanel card = new JPanel(); var errors = new ArrayList<PaintObservation.Unsupported>();
            card.setBorder(new AbstractBorder() { }); assertTrue(SwingPaintCollector.configuredBorders(card, errors, "card").isEmpty());
            assertFalse(errors.isEmpty()); errors.clear(); card.setBorder(new LineBorder(Color.RED, 2, true));
            assertTrue(SwingPaintCollector.configuredBorders(card, errors, "card").isEmpty()); assertFalse(errors.isEmpty()); return null;
        });
    }

    /** Отсутствие реальных hook сохраняется как unsupported даже при правильных id и viewport. */
    @Test void unhookedRealSwingWidgetsCannotProduceSuccessfulObservation() throws Exception {
        edt(() -> {
            JPanel root = root(); JPanel toolbar = new JPanel(null); toolbar.putClientProperty("cp.id", "toolbar"); toolbar.setBounds(0, 0, 1200, 40);
            JButton button = new JButton("action"); button.putClientProperty("cp.id", "edit.undo"); button.setBounds(0, 0, 30, 30); toolbar.add(button); root.add(toolbar);
            SwingPaintJournal journal = new SwingPaintJournal(root);
            try (SwingPaintCollector collector = new SwingPaintCollector(root, journal)) {
                var bracket = collector.prepare(request(), input(journal, root)); var actual = collector.finish(bracket, 1200, 800, environment());
                assertFalse(actual.unsupported().isEmpty()); assertTrue(actual.unsupported().stream().anyMatch(u -> u.property().equals("paint.census")));
                assertTrue(actual.icons().isEmpty()); assertEquals(actual, PaintObservationCodec.read(PaintObservationCodec.write(actual)));
                assertThrows(IllegalArgumentException.class, () -> collector.finish(bracket, 1200, 800, environment()));
            }
            return null;
        });
    }

    /** Настоящий disabledIcon SwingIcons не получает provenance из cp.text или ожидаемого ключа. */
    @Test void currentSwingIconsDisabledReplacementRequiresDecodeAndPaintHooks() throws Exception {
        edt(() -> {
            JPanel root = root(); JPanel toolbar = new JPanel(null); toolbar.putClientProperty("cp.id", "toolbar"); toolbar.setBounds(0, 0, 1200, 40);
            JButton button = new JButton(); button.setBounds(0, 0, 30, 30); button.putClientProperty("cp.id", "edit.undo");
            SwingIcons.standalone(button, "\u21b6"); button.setEnabled(false); toolbar.add(button); root.add(toolbar);
            assertNotNull(button.getDisabledIcon()); assertNotSame(button.getIcon(), button.getDisabledIcon());
            SwingPaintJournal journal = new SwingPaintJournal(root);
            try (SwingPaintCollector collector = new SwingPaintCollector(root, journal)) {
                var bracket = collector.prepare(request(), input(journal, root)); var observation = collector.finish(bracket, 1200, 800, environment());
                assertTrue(observation.assets().isEmpty());
                assertTrue(observation.unsupported().stream().anyMatch(u -> u.property().equals("icon") && u.reason().contains("absent")));
            }
            return null;
        });
    }

    /** Даже возврат текста к прежнему значению меняет поколения и проваливает bracket. */
    @Test void changeAndRevertInvalidateBracketAndCloseRemovesListeners() throws Exception {
        edt(() -> {
            JPanel root = root(); JButton button = new JButton("before"); root.add(button); SwingPaintJournal journal = new SwingPaintJournal(root);
            int listeners = button.getPropertyChangeListeners().length; SwingPaintCollector collector = new SwingPaintCollector(root, journal);
            try {
                var bracket = collector.prepare(request(), input(journal, root)); button.setText("during"); button.setText("before");
                var actual = collector.finish(bracket, 1200, 800, environment());
                assertFalse(actual.synchronization().stable()); assertTrue(actual.synchronization().layoutRevisionAfter() > actual.synchronization().layoutRevisionBefore());
                assertTrue(actual.synchronization().changes().stream().anyMatch(reason -> reason.equals("mutation generation changed")));
            } finally { collector.close(); }
            assertEquals(listeners, button.getPropertyChangeListeners().length); return null;
        });
    }

    /** Paint generation обнаруживает repaint при неизменном fingerprint компонентов. */
    @Test void repaintInvalidationAndAbortAreOneShot() throws Exception {
        edt(() -> {
            JPanel root = root(); SwingPaintJournal journal = new SwingPaintJournal(root);
            try (SwingPaintCollector collector = new SwingPaintCollector(root, journal)) {
                var bracket = collector.prepare(request(), input(journal, root)); journal.paintInvalidated();
                var actual = collector.finish(bracket, 1200, 800, environment()); assertFalse(actual.synchronization().stable());
                assertTrue(actual.synchronization().changes().contains("paint generation changed"));
                bracket = collector.prepare(request(), input(journal, root)); collector.abort(bracket);
                var consumed = bracket; assertThrows(IllegalArgumentException.class, () -> collector.abort(consumed));
            }
            return null;
        });
    }

    /** Чтение свойств Swing вне EDT запрещено. */
    @Test void constructorRequiresEdt() throws Exception {
        JPanel root = edt(SwingPaintCollectorTest::root); SwingPaintJournal journal = new SwingPaintJournal(root);
        assertThrows(IllegalStateException.class, () -> new SwingPaintCollector(root, journal));
    }

    /** Создаёт измеренное состояние graphics, не подставляя токены ожидаемого профиля. */
    private static SwingPaintJournal.ShapeDraw draw(java.awt.Shape shape, boolean fill, double width) {
        Graphics2D g = new BufferedImage(100, 50, BufferedImage.TYPE_INT_ARGB).createGraphics();
        try {
            g.setColor(new Color(0xff123456, true)); g.setStroke(new BasicStroke((float) width));
            var state = new SwingPaintJournal.GraphicsState(new PaintObservation.Transform(1, 0, 0, 1, 0, 0), g.getClip(),
                    g.getColor().getRGB(), 1, "AlphaComposite:3", true, (double) ((BasicStroke) g.getStroke()).getLineWidth(), true, List.of());
            return new SwingPaintJournal.ShapeDraw(shape, fill, state, "test-primitive", 0);
        } finally { g.dispose(); }
    }
    /** Создаёт корень без окна; такой root заведомо не может пройти экранный capture. */
    private static JPanel root() { JPanel root = new JPanel(null); root.setSize(1200, 800); return root; }
    /** Создаёт намерение без ожидаемых цветов, пикселей или состояний. */
    private static PaintCaptureRequest request() { return new PaintCaptureRequest(UUID.randomUUID(), UUID.randomUUID(), 1, "scenario", "shot", 1, "0".repeat(64), Map.of(), System.nanoTime() + 1_000_000_000L); }
    /** Передаёт подтверждение ввода отдельно от actual focus/hit-test. */
    private static PaintObservation.Interaction input(SwingPaintJournal journal, JPanel root) { return new PaintObservation.Interaction(null, "none", null, "inactive-root", 0, false); }
    /** Возвращает диагностическое окружение теста, которое не выбирает эталон. */
    private static PaintObservation.Environment environment() { return new PaintObservation.Environment("test-os", "test-runtime", "test-renderer", 1, "test-fonts", Map.of()); }
    /** Выполняет модульную проверку на EDT без GUI. */
    private static <T> T edt(Callable<T> action) throws Exception { FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get(); }
}
