package ru.cashprediction.core.document;

import java.util.Optional;
import ru.cashprediction.core.markdown.RuFormats;

/**
 * Режим центральной области главного окна: таблица событий или график баланса.
 *
 * <p>Одинаков для трёх клиентов (JavaFX, Swing, Web) и хранится в {@code settings.md} словом {@link #label()}.</p>
 */
public enum ViewMode {
    /** Вертикальная таблица событий с балансом после каждого. */
    TABLE("таблица"),
    /** Шаговый график баланса по дням. */
    CHART("график");

    private final String label;

    ViewMode(String label) {
        this.label = label;
    }

    /** @return слово для файла настроек: «таблица» / «график» */
    public String label() {
        return label;
    }

    /**
     * Распознаёт режим по слову из файла настроек или по имени константы (регистр и «ё/е» не важны).
     *
     * @param text «таблица», «график», «TABLE», «chart»
     * @return режим или пустое значение, если слово не распознано
     */
    public static Optional<ViewMode> parse(String text) {
        String t = RuFormats.normalize(text);
        for (ViewMode mode : values()) {
            if (t.equals(mode.label) || t.equals(mode.name().toLowerCase(java.util.Locale.ROOT))) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}
