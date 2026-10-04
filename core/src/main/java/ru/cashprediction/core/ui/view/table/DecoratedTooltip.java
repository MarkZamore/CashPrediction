package ru.cashprediction.core.ui.view.table;

import java.util.List;
import java.util.Objects;

/**
 * Текст подсказки и явно отмеченные ядром позиции служебных значков.
 *
 * @param text исходный текст без изменения символов
 * @param iconPositions позиции в порядке следования, в кодовых единицах UTF-16
 */
public record DecoratedTooltip(String text, List<IconPosition> iconPositions) {
    /** Копирует позиции и проверяет их соответствие исходному тексту. */
    public DecoratedTooltip {
        Objects.requireNonNull(text, "text");
        iconPositions = List.copyOf(iconPositions);
        int end = 0;
        for (IconPosition position : iconPositions) {
            if (position.offset() < end || !text.startsWith(position.key(), position.offset())) {
                throw new IllegalArgumentException("icon position");
            }
            end = position.offset() + position.key().length();
        }
    }

    /** Возвращает текст без декоративных позиций. */
    public static DecoratedTooltip plain(String text) {
        return new DecoratedTooltip(text, List.of());
    }

    /**
     * Позиция одного служебного значка, а не результат анализа пользовательского текста.
     *
     * @param offset смещение UTF-16 от начала текста
     * @param key ключ значка, совпадающий с символами в этой позиции
     */
    public record IconPosition(int offset, String key) {
        /** Проверяет смещение и непустой ключ. */
        public IconPosition {
            Objects.requireNonNull(key, "key");
            if (offset < 0 || key.isEmpty()) throw new IllegalArgumentException("icon position");
        }
    }
}
