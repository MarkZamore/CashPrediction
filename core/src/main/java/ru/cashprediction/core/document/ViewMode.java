package ru.cashprediction.core.document;

import java.util.Optional;
import ru.cashprediction.core.format.FormatWords;
import ru.cashprediction.core.markdown.RuFormats;

/**
 * Режим центральной области главного окна: таблица событий или график баланса.
 *
 * <p>Одинаков для трёх клиентов (JavaFX, Swing, Web) и хранится в {@code settings.md} словом {@link #label()}.
 * Это слово — грамматика файла настроек, а не текст интерфейса: оно берётся из нелокализуемого ресурса
 * {@link FormatWords}, чтобы файл читался при любом языке интерфейса.</p>
 */
public enum ViewMode {
    /** Вертикальная таблица событий с балансом после каждого. */
    TABLE,
    /** Шаговый график баланса по дням. */
    CHART;

    /** @return слово для файла настроек: «таблица» / «график» */
    public String label() {
        // Слово ищется при каждом вызове, а не в конструкторе: ошибка ресурса не ломает загрузку перечисления.
        return switch (this) {
            case TABLE -> FormatWords.get("settings.view.table");
            case CHART -> FormatWords.get("settings.view.chart");
        };
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
            // Нормализуются обе стороны: слово формата в ресурсе может быть записано с заглавной буквы или с «ё».
            if (t.equals(RuFormats.normalize(mode.label())) || t.equals(mode.name().toLowerCase(java.util.Locale.ROOT))) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}
