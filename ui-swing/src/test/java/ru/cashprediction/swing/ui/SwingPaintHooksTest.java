package ru.cashprediction.swing.ui;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.cashprediction.core.ui.command.CommandId;
import ru.cashprediction.core.ui.menu.Emphasis;
import ru.cashprediction.core.ui.menu.ToolbarModel;
import ru.cashprediction.core.ui.menu.ToolbarNode;
import ru.cashprediction.core.ui.token.ColorToken;
import ru.cashprediction.core.ui.token.UiIcons;
import ru.cashprediction.core.ui.view.summary.CardModel;
import static org.junit.jupiter.api.Assertions.*;

/** Focused проверки реальных лёгких callbacks без окон, Robot или screen certification. */
class SwingPaintHooksTest {
    /** Прогретый глобальный cache не становится оригиналом другого root journal. */
    @Test void coldRootSourcesAreIsolatedFromWarmGlobalCacheAndOtherRoot() throws Exception {
        edt(() -> {
            var warmed = SwingIcons.icon("search", ColorToken.TEXT_PRIMARY, 16);
            var first = root(); var second = root();
            var left = first.context().icon("search", ColorToken.TEXT_PRIMARY, 16, 16);
            var right = second.context().icon("search", ColorToken.TEXT_PRIMARY, 16, 16);
            assertNotSame(left.getImage(), right.getImage()); assertNotSame(left.getImage(), warmed.getImage());
            assertSame(left, first.context().icon("search", ColorToken.TEXT_PRIMARY, 16, 16));
            assertArrayEquals(UiIcons.png("search", ColorToken.TEXT_PRIMARY).orElseThrow(),
                    SwingIcons.decodedSource(left.getImage()).orElseThrow().bytes());
            assertTrue(first.context().journal().snapshot().owners().isEmpty());
            assertTrue(second.context().journal().snapshot().owners().isEmpty());
            return null;
        });
    }

    /** Реальные root/toolbar/filter/clear callbacks создают owner записи именно для рисующих компонентов. */
    @Test void actualToolbarAndNestedClearCallbacksPopulateJournal() throws Exception {
        edt(() -> {
            var root = root(); var toolbar = new SwingToolbar(null, root.context());
            toolbar.render(new ToolbarModel(List.of(
                    new ToolbarNode.Button("undo", CommandId.EDIT_UNDO, "\u21b6", "", true, Emphasis.NONE),
                    new ToolbarNode.FilterField("filter", "value", "prompt", "", 220, 0, true, ""))));
            root.add(toolbar); layout(root);
            paint(root);
            var snapshot = root.context().journal().snapshot();
            assertTrue(snapshot.owners().stream().anyMatch(owner -> owner.owner() == root));
            assertTrue(snapshot.owners().stream().anyMatch(owner -> owner.owner() == toolbar));
            assertTrue(snapshot.owners().stream().anyMatch(owner -> owner.owner() == toolbar.widget("filter")));
            assertTrue(snapshot.owners().stream().anyMatch(owner -> owner.owner() == toolbar.filter()));
            var clear = (AbstractButton) java.util.Arrays.stream(((JPanel) toolbar.widget("filter")).getComponents())
                    .filter(AbstractButton.class::isInstance).findFirst().orElseThrow();
            var clearPaint = owner(snapshot, clear);
            assertEquals(1, clearPaint.images().size());
            assertSame(clear, clearPaint.images().getFirst().painter());
            assertSame(((ImageIcon) clear.getIcon()).getImage(), clearPaint.images().getFirst().image());
            assertNotNull(clearPaint.images().getFirst().asset());
            assertTrue(snapshot.owners().stream().noneMatch(SwingPaintJournal.OwnerSnapshot::complete));
            assertTrue(clearPaint.unsupported().stream().anyMatch(reason -> reason.contains("census")));
            return null;
        });
    }

    /** Instrumented callback сохраняет все обычные пиксели disabled PNG, TextLayout и фильтра. */
    @Test void rootAwareToolbarKeepsOrdinaryPixelsIncludingDisabledAndTextGlyphs() throws Exception {
        edt(() -> {
            var hooked = root(); var ordinary = new SwingPaintRoot(false); ordinary.setSize(hooked.getSize());
            var model = new ToolbarModel(List.of(
                    new ToolbarNode.Button("undo", CommandId.EDIT_UNDO, "\u21b6", "", false, Emphasis.NONE),
                    new ToolbarNode.Button("glyph", CommandId.FILTER_FOCUS_TABLE, "\u0394", "", true, Emphasis.NONE),
                    new ToolbarNode.FilterField("filter", "value", "prompt", "", 220, 0, true, "")));
            var instrumentedToolbar = new SwingToolbar(null, hooked.context());
            var ordinaryToolbar = new SwingToolbar(null);
            instrumentedToolbar.render(model); ordinaryToolbar.render(model);
            hooked.add(instrumentedToolbar); ordinary.add(ordinaryToolbar); layout(hooked); layout(ordinary);
            BufferedImage recorded = paint(hooked); BufferedImage expected = canvas(ordinary);
            Graphics2D graphics = expected.createGraphics();
            try { ordinary.paint(graphics); } finally { graphics.dispose(); }
            assertArrayEquals(expected.getRGB(0, 0, expected.getWidth(), expected.getHeight(), null, 0, expected.getWidth()),
                    recorded.getRGB(0, 0, recorded.getWidth(), recorded.getHeight(), null, 0, recorded.getWidth()));
            return null;
        });
    }

    /** Disabled состояние пишет фактически выбранный ImageIcon, не enabled альтернативу. */
    @Test void disabledImageComesFromActualButtonDelegate() throws Exception {
        edt(() -> {
            var root = root(); var button = root.context().button(""); root.add(button);
            SwingIcons.standalone(button, "search", root.context()); button.setEnabled(false); layout(root);
            paint(root);
            var image = owner(root.context().journal().snapshot(), button).images().getFirst();
            assertSame(((ImageIcon) button.getDisabledIcon()).getImage(), image.image());
            assertNotSame(((ImageIcon) button.getIcon()).getImage(), image.image());
            assertNotNull(image.asset()); assertTrue(image.drawn());
            return null;
        });
    }

    /** TextLayout передаёт настоящий painter через GraphicAttribute, а не имя значка. */
    @Test void textLayoutGlyphHasOriginalRootDecodeAndRealPainter() throws Exception {
        edt(() -> {
            var root = root(); var button = root.context().button("\u0394"); root.add(button);
            SwingIcons.decorate(button, root.context()); layout(root); paint(root);
            var draws = owner(root.context().journal().snapshot(), button).images();
            assertFalse(draws.isEmpty());
            assertTrue(draws.stream().allMatch(draw -> draw.painter() == button && draw.paintSource().equals("text-glyph")));
            assertTrue(draws.stream().allMatch(draw -> draw.asset() != null));
            return null;
        });
    }

    /** Карточка записывает реальные fill/border по одному разу, сохраняя обычные пиксели. */
    @Test void actualCardFillAndBorderAreNotDuplicatedOrInvented() throws Exception {
        edt(() -> {
            var root = root(); var panel = new SwingSummaryPanel(null, root.context()); root.add(panel);
            var model = new CardModel("card", "title", "42", ColorToken.TEXT_PRIMARY, "caption", ColorToken.TEXT_MUTED, null, "", "");
            var card = panel.new Card(model); panel.add(card); layout(root);
            BufferedImage hooked = paint(root);
            var actual = owner(root.context().journal().snapshot(), card);
            assertEquals(2, actual.shapes().size());
            assertTrue(actual.shapes().getFirst().fill()); assertFalse(actual.shapes().getLast().fill());
            assertEquals(card.getBackground().getRGB(), actual.shapes().getFirst().state().argb().intValue());
            assertEquals(1.0, actual.shapes().getLast().state().strokeWidth().doubleValue());
            assertFalse(actual.complete());
            var ordinary = new SwingSummaryPanel(null); var oldCard = ordinary.new Card(model);
            ordinary.add(oldCard); ordinary.setSize(panel.getSize()); layout(ordinary);
            BufferedImage old = canvas(ordinary); Graphics2D graphics = old.createGraphics();
            try { ordinary.paint(graphics); } finally { graphics.dispose(); }
            assertArrayEquals(old.getRGB(0, 0, old.getWidth(), old.getHeight(), null, 0, old.getWidth()),
                    hooked.getRGB(0, 0, hooked.getWidth(), hooked.getHeight(), null, 0, hooked.getWidth()));
            return null;
        });
    }

    /** Равные pixels постороннего decode не получают provenance текущего root. */
    @Test void foreignImageDrawIsPerformedButCannotBeImportedAfterPaint() throws Exception {
        edt(() -> {
            var root = root(); var image = SwingIcons.icon("search").getImage();
            var buffer = canvas(root); Graphics2D graphics = buffer.createGraphics();
            var journal = root.context().journal(); journal.beginEpoch();
            try { root.context().paintPass(() -> root.context().paint(root, graphics, g ->
                    root.context().image(root, (Graphics2D) g, image, 0, 0, 16, 16, null, "icon-paint"))); }
            finally { graphics.dispose(); journal.finishEpoch(); }
            var actual = owner(journal.snapshot(), root);
            assertEquals(1, actual.images().size()); assertTrue(actual.images().getFirst().drawn());
            assertNull(actual.images().getFirst().asset()); assertFalse(actual.complete());
            assertTrue(actual.unsupported().contains("unknown or changed image identity"));
            return null;
        });
    }

    /** Заменённый plain ImageIcon сохраняет paint, но его отсутствие в перехвате остаётся Unsupported. */
    @Test void replacementPlainIconDoesNotBecomeACompletedCensus() throws Exception {
        edt(() -> {
            var root = root(); var button = root.context().button(""); root.add(button);
            button.setIcon(new ImageIcon(root.context().icon("search", null, 16, 16).getImage()));
            layout(root); paint(root);
            var actual = owner(root.context().journal().snapshot(), button);
            assertTrue(actual.images().isEmpty()); assertFalse(actual.complete());
            assertTrue(actual.unsupported().stream().anyMatch(reason -> reason.contains("census")));
            return null;
        });
    }

    /** Исключение component/border/children не завершает owner, но закрывает scopes и attempt token. */
    @ParameterizedTest @ValueSource(strings = {"component", "border", "children"})
    void interruptedPaintClosesScopesWithoutClaimingCompletion(String phase) throws Exception {
        edt(() -> {
            var root = root(); var child = new BrokenPainter(phase); root.add(child); layout(root);
            var journal = root.context().journal(); journal.beginEpoch();
            Graphics2D graphics = canvas(root).createGraphics();
            try { assertThrows(IllegalStateException.class, () -> root.context().paintPass(() -> root.paint(graphics))); }
            finally { graphics.dispose(); journal.finishEpoch(); }
            assertFalse(owner(journal.snapshot(), root).complete());
            child.phase = "none";
            paint(root); assertTrue(journal.snapshot().finished());
            return null;
        });
    }

    /** Прямой автоматический paint без manager callback инвалидирует уже собранную эпоху. */
    @Test void repaintOutsideOwnPassInvalidatesWithoutInventingANewEpoch() throws Exception {
        edt(() -> {
            var root = root(); var button = root.context().button(""); root.add(button);
            button.setIcon(root.context().icon("search", null, 16, 16)); layout(root); paint(root);
            var before = root.context().journal().snapshot();
            Graphics2D graphics = canvas(root).createGraphics();
            try { button.paint(graphics); } finally { graphics.dispose(); }
            var after = root.context().journal().snapshot();
            assertEquals(before.epoch(), after.epoch()); assertTrue(after.revision() > before.revision()); assertFalse(after.finished());
            paint(root); assertEquals(1, owner(root.context().journal().snapshot(), button).images().size());
            return null;
        });
    }

    /** Частичный clip и offscreen ImageIO canvas не становятся полным экранным проходом. */
    @Test void partialOffscreenCallbackIsNeverScreenComplete() throws Exception {
        edt(() -> {
            var root = root(); var journal = root.context().journal(); journal.beginEpoch();
            Graphics2D graphics = canvas(root).createGraphics(); graphics.clipRect(0, 0, 4, 4);
            try { root.context().paintPass(() -> root.paint(graphics)); }
            finally { graphics.dispose(); journal.finishEpoch(); }
            var actual = owner(journal.snapshot(), root);
            assertFalse(actual.fullClip()); assertFalse(actual.screen()); assertFalse(actual.complete());
            return null;
        });
    }

    /** Глубокий изменённый ancestor отвергается sidecar-проверкой на границе, даже при неизменном scaled Image. */
    @Test void originalAncestorMutationInvalidatesDrawnScalingChain() throws Exception {
        edt(() -> {
            var root = root(); var context = root.context(); var journal = context.journal();
            var original = SwingIcons.decode(UiIcons.png("search").orElseThrow(), "fixture:original", journal);
            var first = SwingIcons.scale(original.getImage(), 16, 16, Image.SCALE_SMOOTH, journal);
            Graphics2D graphics = canvas(root).createGraphics(); journal.beginEpoch();
            try {
                context.paintPass(() -> context.paint(root, graphics, g -> {
                    context.image(root, (Graphics2D) g, first.getImage(), 0, 0, 16, 16, null, "icon-paint");
                    var second = SwingIcons.scale(first.getImage(), 12, 12, Image.SCALE_SMOOTH, journal);
                    context.image(root, (Graphics2D) g, second.getImage(), 20, 0, 12, 12, null, "icon-paint");
                }));
            } finally { graphics.dispose(); journal.finishEpoch(); }
            assertTrue(context.validateSources());
            BufferedImage base = (BufferedImage) original.getImage(); base.setRGB(0, 0, base.getRGB(0, 0) ^ 1);
            assertFalse(context.validateSources()); assertFalse(journal.snapshot().finished());
            return null;
        });
    }

    /** Ordinary root не имеет journal и не участвует в диагностическом lifecycle. */
    @Test void ordinaryRootHasNoCaptureContext() throws Exception {
        edt(() -> { assertNull(new SwingPaintRoot(false).context()); return null; });
    }

    /** Создаёт лёгкий настоящий content root без JFrame и экранной конфигурации. */
    private static SwingPaintRoot root() {
        SwingLook.install(); var root = new SwingPaintRoot(true); root.setSize(600, 180); root.setDoubleBuffered(false); return root;
    }

    /** Завершает рекурсивную layout невидимых компонентов без очереди native окон. */
    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) if (child instanceof Container nested) layout(nested);
    }

    /** Выполняет один реальный paint callback в тестовый buffer; он не является screen evidence. */
    private static BufferedImage paint(SwingPaintRoot root) {
        var journal = root.context().journal(); journal.beginEpoch();
        BufferedImage image = canvas(root); Graphics2D graphics = image.createGraphics();
        graphics.setClip(0, 0, root.getWidth(), root.getHeight());
        try { root.context().paintPass(() -> root.paint(graphics)); }
        finally { graphics.dispose(); journal.finishEpoch(); }
        return image;
    }

    /** Создаёт только memory canvas точного текущего размера компонента. */
    private static BufferedImage canvas(JComponent component) {
        return new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_ARGB);
    }

    /** Находит callback по identity фактического painter, не по cp.id. */
    private static SwingPaintJournal.OwnerSnapshot owner(SwingPaintJournal.Snapshot snapshot, JComponent component) {
        return snapshot.owners().stream().filter(entry -> entry.owner() == component).findFirst().orElseThrow();
    }

    /** Доставляет focused test на EDT с передачей исходной ошибки вызывающему потоку. */
    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }

    /** Настоящий лёгкий компонент, прерывающий заданную фазу штатного полного paint. */
    private static final class BrokenPainter extends JPanel {
        private String phase;
        /** Сохраняет фазу для отрицательного контроля callbacks. */
        BrokenPainter(String phase) { this.phase = phase; }
        /** Прерывает component paint только в соответствующем отрицательном случае. */
        @Override protected void paintComponent(Graphics graphics) { fail("component"); super.paintComponent(graphics); }
        /** Прерывает border paint, не превращая finally владельца в успешный complete. */
        @Override protected void paintBorder(Graphics graphics) { fail("border"); super.paintBorder(graphics); }
        /** Прерывает children paint после обычного component/border возврата. */
        @Override protected void paintChildren(Graphics graphics) { fail("children"); super.paintChildren(graphics); }
        /** Выбрасывает контролируемую ошибку именно из заданного callback. */
        private void fail(String actual) { if (phase.equals(actual)) throw new IllegalStateException("paint interrupted:" + actual); }
    }
}
