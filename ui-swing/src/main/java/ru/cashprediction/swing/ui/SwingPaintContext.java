package ru.cashprediction.swing.ui;

import java.awt.*;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.*;
import ru.cashprediction.core.ui.token.ColorToken;
import ru.cashprediction.core.ui.token.UiIcons;

/** Локальное владение оригинальным decode и настоящими callbacks одного selftest-корня. */
final class SwingPaintContext {
    private final SwingPaintJournal journal;
    private final Map<String, ImageIcon> originals = new HashMap<>();
    private final Map<String, ImageIcon> variants = new HashMap<>();
    private final Map<Image, SwingIcons.DecodedPng> drawnSources = new IdentityHashMap<>();
    private boolean ownPass;

    /** Создаёт журнал до первой загрузки значков именно этого корня. */
    SwingPaintContext(JComponent root) { journal = new SwingPaintJournal(root); }

    /** Возвращает тот же журнал для driver, без повторного создания после decode. */
    SwingPaintJournal journal() { return journal; }

    /** Создаёт настоящий составной owner тулбара с полным callback, включая его детей. */
    JPanel panel(LayoutManager layout) { return new OwnerPanel(layout); }

    /** Создаёт настоящую кнопку с callback полного paint и прежней моделью JButton. */
    JButton button(String text) { return new OwnerButton(text); }

    /** Создаёт настоящую toggle кнопку, сохраняя её штатную модель выбранности. */
    JToggleButton toggle(String text, boolean selected) { return new OwnerToggle(text, selected); }

    /** Загружает оригинал в локальный cache и устанавливает фактически зарегистрированный scaled Image. */
    ImageIcon icon(String key, ColorToken color, int width, int height) {
        edt();
        String originalKey = key + ":" + (color == null ? "original" : color.name());
        ImageIcon original = originals.computeIfAbsent(originalKey, ignored -> SwingIcons.decode(
                (color == null ? UiIcons.png(key) : UiIcons.png(key, color)).orElseThrow(() -> new IllegalArgumentException(key)),
                "UiIcons.png(" + originalKey + ")", journal));
        String variantKey = originalKey + ":" + width + ":" + height;
        return variants.computeIfAbsent(variantKey, ignored -> new RecordedIcon(
                SwingIcons.scale(original.getImage(), width, height, Image.SCALE_SMOOTH, journal).getImage()));
    }

    /** Выполняет ровно один собственный проход внутри уже открытой эпохи manager. */
    void paintPass(Runnable paint) {
        edt();
        if (ownPass || !journal.collectingEpoch()) throw new IllegalStateException("paint pass requires a fresh active epoch");
        drawnSources.clear(); ownPass = true;
        try { paint.run(); } finally { ownPass = false; }
    }

    /** Открывает scope в реальном полном paint, но не объявляет неполный delegate census завершённым. */
    void paint(JComponent owner, Graphics graphics, Consumer<Graphics> callback) {
        edt();
        if (!ownPass || !(graphics instanceof Graphics2D actual)) {
            journal.paintInvalidated(); callback.accept(graphics); return;
        }
        try (var scope = journal.openOwner(owner, actual)) {
            scope.unsupported("UI delegate image/text/shape census is not fully intercepted");
            callback.accept(graphics);
            // Нормальный возврат callback ещё не доказывает полный census, поэтому complete не вызывается.
        }
    }

    /** Пишет фактическую заливку карточки, выполняя исходную операцию один раз. */
    void fill(JComponent painter, Graphics2D graphics, Shape shape, String implementation) {
        var scope = scope(painter);
        if (scope == null) graphics.fill(shape); else scope.fill(graphics, shape, implementation);
    }

    /** Пишет фактическую рамку карточки без смешивания с геометрией её дочерних labels. */
    void draw(JComponent painter, Graphics2D graphics, Shape shape, String implementation) {
        var scope = scope(painter);
        if (scope == null) graphics.draw(shape); else scope.draw(graphics, shape, implementation);
    }

    /** Регистрирует реальный draw конкретного painter; неизвестный Image не получает provenance. */
    void image(JComponent painter, Graphics2D graphics, Image image, int x, int y, int width, int height,
               java.awt.image.ImageObserver observer, String source) {
        var scope = scope(painter);
        if (scope == null) { graphics.drawImage(image, x, y, width, height, observer); return; }
        var retained = SwingIcons.decodedSource(image);
        if (retained.isPresent()) drawnSources.putIfAbsent(image, retained.get());
        else scope.unsupported("original decoded image or ancestor changed or unknown");
        scope.drawImage(painter, graphics, image, x, y, width, height, observer, "unclassified", source);
    }

    /** Проверяет всю удержанную decode/scaling цепочку на обеих границах capture. */
    boolean validateSources() {
        edt(); boolean valid = true;
        for (var entry : drawnSources.entrySet()) {
            if (SwingIcons.decodedSource(entry.getKey()).filter(value -> value == entry.getValue()).isEmpty()) valid = false;
        }
        if (!valid) journal.paintInvalidated();
        return valid;
    }

    /** Возвращает scope только во время собственного полного прохода, без регистрации задним числом. */
    private SwingPaintJournal.PaintScope scope(JComponent painter) {
        edt(); return ownPass ? journal.currentScope(painter) : null;
    }

    /** Ограничивает context фактическим EDT, без глобального или ThreadLocal выбора окна. */
    private static void edt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("paint context requires EDT");
    }

    /** Составной owner, чьи children получают собственные scopes при наличии hooks. */
    private final class OwnerPanel extends JPanel {
        /** Сохраняет штатную раскладку составного контрола. */
        OwnerPanel(LayoutManager layout) { super(layout); }
        /** Наблюдает полный paint панели без выдуманного дочернего census. */
        @Override public void paint(Graphics graphics) { SwingPaintContext.this.paint(this, graphics, super::paint); }
    }

    /** Кнопка с прямой привязкой к context окна, без lookup по клиентскому свойству. */
    private final class OwnerButton extends JButton {
        /** Сохраняет настоящий текст кнопки. */
        OwnerButton(String text) { super(text); }
        /** Наблюдает реальный UI delegate и border внутри owner scope. */
        @Override public void paint(Graphics graphics) { SwingPaintContext.this.paint(this, graphics, super::paint); }
    }

    /** Переключатель с таким же наблюдением полного штатного paint. */
    private final class OwnerToggle extends JToggleButton {
        /** Сохраняет текст и выбранность штатной toggle модели. */
        OwnerToggle(String text, boolean selected) { super(text, selected); }
        /** Наблюдает полный callback выбранного или отключённого контрола. */
        @Override public void paint(Graphics graphics) { SwingPaintContext.this.paint(this, graphics, super::paint); }
    }

    /** ImageIcon сохраняет тот же Image и перехватывает собственный настоящий callback UI delegate. */
    private final class RecordedIcon extends ImageIcon {
        /** Устанавливает уже декодированный объект, не создавая второе доказательное изображение. */
        RecordedIcon(Image image) { super(image); }

        /** Рисует один раз с настоящим painter и текущими transform/composite/observer. */
        @Override public synchronized void paintIcon(Component component, Graphics graphics, int x, int y) {
            if (component instanceof JComponent painter && graphics instanceof Graphics2D actual) {
                image(painter, actual, getImage(), x, y, getIconWidth(), getIconHeight(),
                        getImageObserver() == null ? component : getImageObserver(), "icon-paint");
            } else {
                var scope = ownPass && component instanceof JComponent painter ? journal.currentScope(painter) : null;
                if (scope != null) scope.unsupported("icon callback without Graphics2D");
                super.paintIcon(component, graphics, x, y);
            }
        }
    }
}
