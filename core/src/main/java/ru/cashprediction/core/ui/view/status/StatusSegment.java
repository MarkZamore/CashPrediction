package ru.cashprediction.core.ui.view.status;

import java.util.Objects;
import ru.cashprediction.core.ui.token.ColorToken;

/**
 * Сегмент строки состояния (спецификация v2, §5.4). Невидимый сегмент не оставляет вертикальной черты.
 *
 * @param id      id сегмента: {@code file}, {@code dirty}, {@code rows}, {@code horizon}, {@code message},
 *                {@code whatIf}, {@code autosave}, {@code session} (константы {@link StatusModel})
 * @param text    текст
 * @param tooltip подсказка или пустая строка
 * @param color   цвет текста
 * @param grow    растягивается ли сегмент (только {@code message})
 * @param visible виден ли
 */
public record StatusSegment(String id, String text, String tooltip, ColorToken color, boolean grow, boolean visible) {

    /** Проверяет поля и подставляет значения по умолчанию. */
    public StatusSegment {
        Objects.requireNonNull(id, "id");
        text = Objects.requireNonNullElse(text, "");
        tooltip = Objects.requireNonNullElse(tooltip, "");
        color = color == null ? ColorToken.TEXT_PRIMARY : color;
    }
}
