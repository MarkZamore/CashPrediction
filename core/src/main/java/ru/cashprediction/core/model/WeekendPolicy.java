package ru.cashprediction.core.model;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * Что делать, если плановая дата регулярной операции выпала на субботу или воскресенье.
 *
 * <p>Пример: зарплату «5-го числа» бухгалтерия переводит в пятницу, если 5-е выпало на выходной.
 * Праздничные дни не учитываются: список праздников меняется каждый год, а план должен
 * оставаться переносимым текстовым файлом. Это ограничение показано в подсказке редактора.</p>
 */
public enum WeekendPolicy {
    /** Дата не сдвигается. */
    NONE("нет", "Не сдвигать"),
    /** Суббота и воскресенье переносятся на предыдущую пятницу. */
    PREVIOUS_BUSINESS_DAY("раньше", "На пятницу (раньше)"),
    /** Суббота и воскресенье переносятся на следующий понедельник. */
    NEXT_BUSINESS_DAY("позже", "На понедельник (позже)");

    private final String label;
    private final String title;

    WeekendPolicy(String label, String title) {
        this.label = label;
        this.title = title;
    }

    /** @return слово для файла плана: нет / раньше / позже */
    public String label() {
        return label;
    }

    /** @return подпись для интерфейса */
    public String title() {
        return title;
    }

    /**
     * Применяет правило сдвига к дате.
     *
     * @param date номинальная дата операции
     * @return та же дата для будней или сдвинутая дата для выходных
     */
    public LocalDate apply(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        boolean weekend = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
        if (!weekend || this == NONE) {
            return date;
        }
        if (this == PREVIOUS_BUSINESS_DAY) {
            // Суббота -> пятница (минус 1 день), воскресенье -> пятница (минус 2 дня).
            return date.minusDays(day == DayOfWeek.SATURDAY ? 1 : 2);
        }
        // Суббота -> понедельник (плюс 2 дня), воскресенье -> понедельник (плюс 1 день).
        return date.plusDays(day == DayOfWeek.SATURDAY ? 2 : 1);
    }
}
