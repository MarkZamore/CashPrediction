package ru.cashprediction.fx.ui;

import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.stage.Screen;
import javafx.stage.Window;
import ru.cashprediction.core.app.Placement;
import ru.cashprediction.core.session.WindowBounds;

/** Размещает настоящее содержимое свежего диалога, не меняя RAW-геометрию восстановления. */
final class FxContentPlacement {
    private FxContentPlacement() { }

    /** После показа компенсирует измеренную рамку ОС и ограничивает только свежее окно видимой областью. */
    static void centerFresh(Window window, Node content, Node ownerContent) {
        Bounds actual = content.localToScreen(content.getLayoutBounds());
        if (actual == null) return;
        Bounds owner = ownerContent == null ? null : ownerContent.localToScreen(ownerContent.getLayoutBounds());
        var screens = owner == null ? Screen.getScreensForRectangle(window.getX(), window.getY(), window.getWidth(), window.getHeight())
                : Screen.getScreensForRectangle(owner.getMinX(), owner.getMinY(), owner.getWidth(), owner.getHeight());
        Rectangle2D visible = (screens.isEmpty() ? Screen.getPrimary() : screens.getFirst()).getVisualBounds();
        WindowBounds ownerBounds = owner == null
                ? new WindowBounds(visible.getMinX(), visible.getMinY(), visible.getWidth(), visible.getHeight())
                : new WindowBounds(owner.getMinX(), owner.getMinY(), owner.getWidth(), owner.getHeight());
        WindowBounds desired = Placement.centerContent(ownerBounds, actual.getWidth(), actual.getHeight());
        window.setX(clamp(desired.x() + window.getX() - actual.getMinX(), window.getWidth(), visible.getMinX(), visible.getWidth()));
        window.setY(clamp(desired.y() + window.getY() - actual.getMinY(), window.getHeight(), visible.getMinY(), visible.getHeight()));
    }

    /** Ограничивает координату, не изменяя реальную ширину или высоту окна. */
    static double clamp(double position, double size, double minimum, double extent) {
        return Math.max(minimum, Math.min(position, minimum + extent - size));
    }
}
