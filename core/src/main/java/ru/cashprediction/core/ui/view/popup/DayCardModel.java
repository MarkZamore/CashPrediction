package ru.cashprediction.core.ui.view.popup;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import ru.cashprediction.core.ui.token.ColorToken;

/**
 * Карточка дня у указателя на графике (спецификация v2, §5.6.2): JavaFX {@code PopupWindow} → Swing {@code JWindow}
 * → Web div. Отступ 8, промежутки 3, ширина 200–320; не принимает мышь и не восстанавливается.
 *
 * @param date         день
 * @param header       первая строка {@code font.header}: «05.10.2026, понедельник»
 * @param balanceLine  {@code day.balance} «Баланс на конец дня: {format(cur)}»
 * @param balanceColor {@code expense} при балансе &lt; 0; {@code warn} ниже подушки; иначе {@code text.primary}
 * @param lines        до 8 строк событий дня с учётом фильтров, без START
 * @param moreText     {@code day.more} «… и ещё {N}» или пустая строка
 * @param noneText     {@code day.none} «Событий нет» ({@code text.muted}), если событий нет, иначе пустая строка
 */
public record DayCardModel(LocalDate date, String header, String balanceLine, ColorToken balanceColor,
                           List<Line> lines, String moreText, String noneText) {

    /**
     * Строка события.
     *
     * @param text  «+80 000,00  Зарплата» (два пробела) или «пропущено  {title}»
     * @param color {@code income}, {@code expense} или {@code text.muted} для пропущенного
     */
    public record Line(String text, ColorToken color) {
        /** Проверяет поля. */
        public Line {
            text = Objects.requireNonNullElse(text, "");
            Objects.requireNonNull(color, "color");
        }
    }

    /** Проверяет поля и копирует список. */
    public DayCardModel {
        Objects.requireNonNull(date, "date");
        header = Objects.requireNonNullElse(header, "");
        balanceLine = Objects.requireNonNullElse(balanceLine, "");
        balanceColor = balanceColor == null ? ColorToken.TEXT_PRIMARY : balanceColor;
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        moreText = Objects.requireNonNullElse(moreText, "");
        noneText = Objects.requireNonNullElse(noneText, "");
    }
}
