package ru.cashprediction.core.ui.view.table;

import java.util.List;
import java.util.Objects;
import ru.cashprediction.core.ui.command.CommandId;
import ru.cashprediction.core.ui.token.ColorToken;

/**
 * Пустое состояние таблицы по центру области (спецификация v2, §5.2): JavaFX placeholder с кнопками → Swing панель
 * поверх {@code JScrollPane} → Web блок по центру.
 *
 * @param kind    вид пустого состояния
 * @param text    текст
 * @param color   цвет текста ({@code expense} для ошибки прогноза, иначе {@code text.muted})
 * @param buttons кнопки по порядку
 */
public record Placeholder(Kind kind, String text, ColorToken color, List<Button> buttons) {

    /** Вид пустого состояния. */
    public enum Kind {
        /** «Прогноз не рассчитан: {0}». */
        FORECAST_ERROR,
        /** «В плане «{0}» пока нет операций…» с кнопками «Добавить доход…», «Добавить расход…», «Открыть пример». */
        NEW_PLAN,
        /** «Нет строк: измените фильтр, период или флажки меню «Вид»» и «Очистить фильтр», если фильтр не пуст. */
        FILTERED
    }

    /**
     * Кнопка пустого состояния.
     *
     * @param id      стабильный id ({@code empty.addIncome}, …)
     * @param command команда ({@code edit.addIncome}, {@code edit.addExpense}, {@code file.sample}, {@code filter.clear})
     * @param text    текст кнопки
     */
    public record Button(String id, CommandId command, String text) {
        /** Проверяет поля. */
        public Button {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(command, "command");
            text = Objects.requireNonNullElse(text, "");
        }
    }

    /** Проверяет поля и копирует список. */
    public Placeholder {
        Objects.requireNonNull(kind, "kind");
        text = Objects.requireNonNullElse(text, "");
        color = color == null ? ColorToken.TEXT_MUTED : color;
        buttons = List.copyOf(Objects.requireNonNull(buttons, "buttons"));
    }
}
