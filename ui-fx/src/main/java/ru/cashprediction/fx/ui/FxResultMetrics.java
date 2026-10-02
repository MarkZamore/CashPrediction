package ru.cashprediction.fx.ui;

import javafx.scene.layout.Region;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import ru.cashprediction.core.ui.token.FontToken;

/** Резерв строк результата определяется метрикой шрифта, а не типом или полной высотой формы. */
final class FxResultMetrics {
    private FxResultMetrics() { }

    /** Возвращает настоящую высоту строки BASE, включая верхнюю и нижнюю метрики шрифта. */
    static double lineHeight() {
        var sample = new Text("Ag");
        sample.setFont(Font.font(FontToken.BASE.primaryFamily(), FontToken.BASE.sizePx()));
        return sample.getLayoutBounds().getHeight();
    }

    /** Задаёт только минимум: содержимое с дополнительными или перенесёнными строками растёт естественно. */
    static void reserve(Region region, int minLines) {
        region.setMinHeight(lineHeight() * minLines);
    }
}
