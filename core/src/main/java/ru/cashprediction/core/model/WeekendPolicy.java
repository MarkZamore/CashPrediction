package ru.cashprediction.core.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import ru.cashprediction.core.format.FormatWords;
import ru.cashprediction.core.text.Texts;

/**
 * Что делать, если плановая дата регулярной операции выпала на субботу или воскресенье.
 *
 * <p>Пример: зарплату «5-го числа» бухгалтерия переводит в пятницу, если 5-е выпало на выходной.
 * Праздничные дни не учитываются: список праздников меняется каждый год, а план должен
 * оставаться переносимым текстовым файлом. Это ограничение показано в подсказке редактора.</p>
 *
 * <p>Слово для файла ({@link #label()}) берётся из грамматики формата ({@link FormatWords}), подпись для интерфейса
 * ({@link #title()}) — из каталога текстов ({@link Texts}); оба лениво, при вызове.</p>
 */
public enum WeekendPolicy {
    /** Дата не сдвигается. */
    NONE,
    /** Суббота и воскресенье переносятся на предыдущую пятницу. */
    PREVIOUS_BUSINESS_DAY,
    /** Суббота и воскресенье переносятся на следующий понедельник. */
    NEXT_BUSINESS_DAY;

    /** @return слово для файла плана: нет / раньше / позже */
    public String label() {
        return switch (this) {
            case NONE -> FormatWords.get("plan.weekend.none");
            case PREVIOUS_BUSINESS_DAY -> FormatWords.get("plan.weekend.previous");
            case NEXT_BUSINESS_DAY -> FormatWords.get("plan.weekend.next");
        };
    }

    /** @return подпись для интерфейса */
    public String title() {
        return switch (this) {
            case NONE -> Texts.get("weekend.title.none");
            case PREVIOUS_BUSINESS_DAY -> Texts.get("weekend.title.previous");
            case NEXT_BUSINESS_DAY -> Texts.get("weekend.title.next");
        };
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
