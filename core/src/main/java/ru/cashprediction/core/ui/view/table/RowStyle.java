package ru.cashprediction.core.ui.view.table;

import ru.cashprediction.core.ui.token.ColorToken;

/**
 * Оформление строки таблицы целиком (спецификация v2, §5.2 «Раскраска строк»).
 *
 * <p>Фон выделенной строки ({@code accent.weak}) клиент рисует сам по выделению: в модели фон без учёта выделения
 * (приоритет: баланс &lt; 0 → {@code negative.bg}; ниже подушки → {@code cushion.bg}; итог → {@code total.bg};
 * PAST_HEADER → {@code bg.alt}). При выделении текст — {@code text.primary}.</p>
 *
 * @param background фон или {@code null} — фон таблицы {@code bg.surface}
 * @param text       цвет текста по умолчанию для ячеек строки
 * @param bold       жирный ли текст (итог месяца)
 * @param italic     курсив (START, WHAT_IF, PAST_HEADER)
 */
public record RowStyle(ColorToken background, ColorToken text, boolean bold, boolean italic) {

    /** Обычная строка. */
    public static final RowStyle PLAIN = new RowStyle(null, ColorToken.TEXT_PRIMARY, false, false);

    /** Подставляет цвет текста по умолчанию. */
    public RowStyle {
        text = text == null ? ColorToken.TEXT_PRIMARY : text;
    }
}
