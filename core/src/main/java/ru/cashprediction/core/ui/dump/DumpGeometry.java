package ru.cashprediction.core.ui.dump;

import java.util.Objects;

/** Переводит реальные измерения содержимого из координат экрана в координаты главного содержимого. */
public final class DumpGeometry {
    private DumpGeometry() { }

    /**
     * Убирает только начало координат экрана, не изменяя размер и относительное положение.
     * Рамка ОС не входит в измеряемое содержимое. Геометрия WindowState для восстановления не меняется.
     *
     * @param contentOnScreen реальные границы содержимого окна на экране
     * @param mainContentOnScreen реальные границы содержимого главного окна на экране
     * @return те же границы относительно начала главного содержимого
     */
    public static UiDump.Box relativeContent(UiDump.Box contentOnScreen, UiDump.Box mainContentOnScreen) {
        valid(contentOnScreen); valid(mainContentOnScreen);
        return new UiDump.Box(contentOnScreen.x() - mainContentOnScreen.x(),
                contentOnScreen.y() - mainContentOnScreen.y(), contentOnScreen.width(), contentOnScreen.height());
    }

    /** Не превращает неизвестные или повреждённые измерения в нулевую геометрию. */
    private static void valid(UiDump.Box box) {
        Objects.requireNonNull(box, "box");
        if (!Double.isFinite(box.x()) || !Double.isFinite(box.y()) || !Double.isFinite(box.width())
                || !Double.isFinite(box.height()) || box.width() <= 0 || box.height() <= 0)
            throw new IllegalArgumentException("Invalid content measurement");
    }
}
