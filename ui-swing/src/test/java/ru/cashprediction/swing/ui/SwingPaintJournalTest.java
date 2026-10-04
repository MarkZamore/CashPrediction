package ru.cashprediction.swing.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.font.GraphicAttribute;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.text.AttributedString;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.selftest.paint.PaintObservation;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет реальные вызовы Graphics2D в изолированном canvas; это не свидетельство экранного S5. */
class SwingPaintJournalTest {
    /** Повторы внутри прохода остаются отдельными, следующая эпоха очищает историю. */
    @Test void repeatedDrawsRemainDistinctAndNextEpochDoesNotAccumulate() throws Exception {
        edt(() -> {
            JPanel root = root(); SwingPaintJournal journal = new SwingPaintJournal(root);
            BufferedImage asset = journal.decode(png(), "memory:decode-test");
            Graphics2D graphics = canvas();
            try {
                journal.beginEpoch();
                try (var scope = journal.openOwner(root, graphics)) {
                    assertTrue(scope.drawImage(graphics, asset, 2, 3, 15, 17, null, "glyph", "icon-paint"));
                    scope.drawImage(graphics, asset, 2, 3, 15, 17, null, "glyph", "icon-paint"); scope.complete();
                }
                journal.finishEpoch(); var first = journal.snapshot();
                assertEquals(2, first.owners().getFirst().images().size());
                assertEquals(0, first.owners().getFirst().images().getFirst().occurrence());
                assertEquals(1, first.owners().getFirst().images().getLast().occurrence());
                assertFalse(first.owners().getFirst().complete()); assertFalse(first.owners().getFirst().screen());
                journal.beginEpoch(); try (var scope = journal.openOwner(root, graphics)) { scope.complete(); }
                journal.finishEpoch(); assertTrue(journal.snapshot().owners().getFirst().images().isEmpty());
                assertTrue(journal.snapshot().epoch() > first.epoch());
            } finally { graphics.dispose(); }
            return null;
        });
    }

    /** Реальный composite и дробный transform сохраняются без ожидаемого disabled-токена. */
    @Test void recordsActualAlphaFractionalTransformAndDestination() throws Exception {
        edt(() -> {
            JPanel root = root(); SwingPaintJournal journal = new SwingPaintJournal(root);
            BufferedImage asset = journal.decode(png(), "memory:decode-test"); Graphics2D graphics = canvas();
            try {
                journal.beginEpoch();
                try (var scope = journal.openOwner(root, graphics)) {
                    graphics.translate(0.25, 0.75); graphics.setComposite(AlphaComposite.SrcOver.derive(0.55f));
                    scope.drawImage(graphics, asset, 7, 11, 17, 15, null, "clear", "icon-paint"); scope.complete();
                }
                journal.finishEpoch(); var draw = journal.snapshot().owners().getFirst().images().getFirst();
                assertEquals(0.55f, draw.state().alpha(), 0.000001); assertEquals(0.25, draw.state().transform().tx());
                assertEquals(0.75, draw.state().transform().ty()); assertEquals(new PaintObservation.Box(7, 11, 17, 15), draw.destination());
                assertNotNull(draw.asset()); assertEquals(draw.sourceIdentity(), draw.asset().sourceObjectIdentity());
            } finally { graphics.dispose(); }
            return null;
        });
    }

    /** Правильная подпись или маркер не спасают новый либо изменённый объект изображения. */
    @Test void unknownAndMutatedImageIdentityRemainUnresolved() throws Exception {
        edt(() -> {
            JPanel root = root(); root.putClientProperty("cp.icon", "correct-marker"); SwingPaintJournal journal = new SwingPaintJournal(root);
            BufferedImage known = journal.decode(png(), "memory:decode-test"), replacement = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
            known.setRGB(0, 0, 0xff445566); Graphics2D graphics = canvas();
            try {
                journal.beginEpoch();
                try (var scope = journal.openOwner(root, graphics)) {
                    scope.drawImage(graphics, known, 0, 0, 16, 16, null, "glyph", "text-glyph");
                    scope.drawImage(graphics, replacement, 16, 0, 16, 16, null, "glyph", "icon-paint"); scope.complete();
                }
                journal.finishEpoch(); var paint = journal.snapshot().owners().getFirst();
                assertTrue(paint.images().stream().allMatch(draw -> draw.asset() == null)); assertFalse(paint.unsupported().isEmpty());
            } finally { graphics.dispose(); }
            return null;
        });
    }

    /** Исключение painter не становится завершённым проходом из-за finally/close. */
    @Test void interruptedAndIncrementalPassesFailClosed() throws Exception {
        edt(() -> {
            JPanel root = root(); SwingPaintJournal journal = new SwingPaintJournal(root); Graphics2D graphics = canvas();
            try {
                graphics.setClip(0, 0, 4, 4); journal.beginEpoch();
                try (var scope = journal.openOwner(root, graphics)) { scope.fill(graphics, new java.awt.Rectangle(0, 0, 10, 10), "test-fill"); }
                journal.finishEpoch(); var paint = journal.snapshot().owners().getFirst();
                assertFalse(paint.fullClip()); assertFalse(paint.complete());
                journal.paintInvalidated(); assertFalse(journal.snapshot().finished());
            } finally { graphics.dispose(); }
            return null;
        });
    }

    /** TextLayout вызывает настоящий GraphicAttribute.draw, и каждый drawImage входит в census. */
    @Test void textLayoutReplacementDrawUsesCurrentScopeAndComposite() throws Exception {
        edt(() -> {
            JPanel root = root(); SwingPaintJournal journal = new SwingPaintJournal(root);
            BufferedImage asset = journal.decode(png(), "memory:decode-test"); Graphics2D graphics = canvas();
            try {
                journal.beginEpoch();
                try (var scope = journal.openOwner(root, graphics)) {
                    AttributedString text = new AttributedString("xx"); text.addAttribute(TextAttribute.FONT, graphics.getFont());
                    text.addAttribute(TextAttribute.CHAR_REPLACEMENT, new GraphicAttribute(GraphicAttribute.ROMAN_BASELINE) {
                        /** Возвращает реальную высоту тестового inline-изображения над baseline. */
                        @Override public float getAscent() { return 12; }
                        /** Возвращает реальную высоту под baseline. */
                        @Override public float getDescent() { return 4; }
                        /** Резервирует ширину для каждого реального draw. */
                        @Override public float getAdvance() { return 16; }
                        /** Передаёт фактические координаты GraphicAttribute, а не owner-derived slot. */
                        @Override public void draw(Graphics2D g, float x, float y) { scope.drawImage(g, asset, Math.round(x), Math.round(y - 12), 16, 16, null, "glyph", "text-glyph"); }
                    });
                    graphics.setComposite(AlphaComposite.SrcOver.derive(0.4f));
                    new TextLayout(text.getIterator(), graphics.getFontRenderContext()).draw(graphics, 5, 24); scope.complete();
                }
                journal.finishEpoch(); var draws = journal.snapshot().owners().getFirst().images();
                assertEquals(2, draws.size()); assertEquals("text-glyph", draws.getFirst().paintSource());
                assertEquals(5, draws.getFirst().destination().x()); assertEquals(21, draws.getLast().destination().x());
                assertEquals(0.4f, draws.getLast().state().alpha(), 0.000001);
            } finally { graphics.dispose(); }
            return null;
        });
    }

    /** Фактический roundRect сохраняет диаметры, толщину и независимые цвета fill/stroke. */
    @Test void capturesActualShapeArgumentsAndProtectsAgainstLaterMutation() throws Exception {
        edt(() -> {
            JPanel root = root(); SwingPaintJournal journal = new SwingPaintJournal(root); Graphics2D graphics = canvas();
            RoundRectangle2D shape = new RoundRectangle2D.Double(0.5, 0.5, 79, 39, 11, 9);
            try {
                journal.beginEpoch();
                try (var scope = journal.openOwner(root, graphics)) {
                    graphics.setColor(new Color(0xff123456, true)); scope.fill(graphics, shape, "actual-fill");
                    graphics.setColor(new Color(0xff654321, true)); graphics.setStroke(new BasicStroke(2)); scope.draw(graphics, shape, "actual-border"); scope.complete();
                }
                journal.finishEpoch(); shape.setRoundRect(0, 0, 2, 2, 1, 1);
                var layers = journal.snapshot().owners().getFirst().shapes();
                assertEquals(11, ((RoundRectangle2D) layers.getFirst().shape()).getArcWidth());
                assertEquals(9, ((RoundRectangle2D) layers.getFirst().shape()).getArcHeight());
                assertEquals(0xff123456, layers.getFirst().state().argb().intValue());
                assertEquals(2, layers.getLast().state().strokeWidth().doubleValue());
                ((RoundRectangle2D) layers.getFirst().shape()).setRoundRect(0, 0, 1, 1, 0, 0);
                assertEquals(11, ((RoundRectangle2D) layers.getFirst().shape()).getArcWidth());
            } finally { graphics.dispose(); }
            return null;
        });
    }

    /** Производный scaled объект сохраняет исходные байты, но получает отдельную object identity. */
    @Test void scaledIdentityIsBoundToOriginalDecodeAndBytesAreDefensive() throws Exception {
        JPanel root = edt(SwingPaintJournalTest::root); SwingPaintJournal journal = new SwingPaintJournal(root);
        byte[] original = png(); BufferedImage source = journal.decode(original, "memory:decode-test"); original[0] = 0;
        Image scaled = journal.scale(source, 17, 15, Image.SCALE_SMOOTH);
        edt(() -> {
            Graphics2D graphics = canvas();
            try {
                journal.beginEpoch(); try (var scope = journal.openOwner(root, graphics)) {
                    scope.drawImage(graphics, scaled, 1, 1, 17, 15, null, "arrow", "icon-paint"); scope.complete();
                }
                journal.finishEpoch(); var asset = journal.snapshot().owners().getFirst().images().getFirst().asset();
                assertNotNull(asset); assertEquals(2, asset.decodedWidth()); assertEquals(journal.identity(scaled), asset.sourceObjectIdentity());
                assertNotEquals(journal.identity(source), asset.sourceObjectIdentity());
                byte[] bytes = asset.base64(); bytes[0] = 0; assertEquals((byte) 137, asset.base64()[0]);
            } finally { graphics.dispose(); }
            return null;
        });
    }

    /** Epoch API запрещает конкурентное чтение вне EDT. */
    @Test void rejectsNonEdtEpochCalls() throws Exception {
        SwingPaintJournal journal = new SwingPaintJournal(edt(SwingPaintJournalTest::root));
        assertThrows(IllegalStateException.class, journal::beginEpoch); assertThrows(IllegalStateException.class, journal::snapshot);
    }

    /** Изменение пикселей после завершённого draw инвалидирует bracket, даже без замены image-ссылки. */
    @Test void pixelMutationAfterDrawInvalidatesFinishedEpoch() throws Exception {
        edt(() -> {
            JPanel root = root(); SwingPaintJournal journal = new SwingPaintJournal(root);
            BufferedImage asset = journal.decode(png(), "memory:decode-test"); Graphics2D graphics = canvas();
            try {
                journal.beginEpoch(); try (var scope = journal.openOwner(root, graphics)) {
                    scope.drawImage(graphics, asset, 0, 0, 16, 16, null, "glyph", "icon-paint"); scope.complete();
                }
                journal.finishEpoch(); var before = journal.snapshot(); asset.setRGB(0, 0, 0xff223344);
                var after = journal.snapshot(); assertFalse(after.finished()); assertTrue(after.revision() > before.revision());
                assertTrue(after.owners().getFirst().unsupported().contains("installed image changed after draw"));
            } finally { graphics.dispose(); }
            return null;
        });
    }

    /** Контекст выбирается по реальному предку; повторный полный проход не дублирует прошлые draws. */
    @Test void scopeLookupAndReplacementFollowPhysicalOwner() throws Exception {
        edt(() -> {
            JPanel root = root(), child = new JPanel(); child.setBounds(0, 0, 20, 20); root.add(child);
            SwingPaintJournal journal = new SwingPaintJournal(root); Graphics2D graphics = canvas();
            try {
                journal.beginEpoch(); try (var outer = journal.openOwner(root, graphics)) {
                    assertSame(outer, journal.currentScope(child));
                    try (var inner = journal.openOwner(child, graphics)) { assertSame(inner, journal.currentScope(child)); inner.complete(); }
                    assertSame(outer, journal.currentScope(child)); outer.complete();
                }
                assertNull(journal.currentScope(child));
                try (var replacement = journal.openOwner(child, graphics)) { replacement.complete(); }
                journal.finishEpoch(); assertEquals(2, journal.snapshot().owners().size());
            } finally { graphics.dispose(); }
            return null;
        });
    }

    /** Создаёт только лёгкий компонент, без окна или GUI-взаимодействия. */
    private static JPanel root() { JPanel panel = new JPanel(null); panel.setSize(80, 40); return panel; }
    /** Возвращает тестовый canvas, который журнал обязан признать offscreen. */
    private static Graphics2D canvas() { return new BufferedImage(80, 40, BufferedImage.TYPE_INT_ARGB).createGraphics(); }
    /** Создаёт независимый ресурс только для проверки provenance, не эталон S5. */
    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB); image.setRGB(0, 0, 0xff123456);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); assertTrue(ImageIO.write(image, "png", bytes)); return bytes.toByteArray();
    }
    /** Исполняет проверку лёгких компонентов на EDT, не запуская GUI. */
    private static <T> T edt(Callable<T> action) throws Exception { FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get(); }
}
