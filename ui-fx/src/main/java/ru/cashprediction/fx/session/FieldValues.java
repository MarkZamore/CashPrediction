package ru.cashprediction.fx.session;

import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.util.DateFormats;

import java.time.LocalDate;
import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.time.DateTimeException;
import java.util.Optional;

/**
 * Канонические строковые формы значений полей для снимка сессии ({@code WindowState.fields}).
 *
 * <p>Формы общие для трёх клиентов (JavaFX, Swing, Web): деньги — {@link Money#formatPlain()}
 * ({@code "95000,00"}), даты — ISO {@code yyyy-MM-dd}, день года — {@code MM-dd}, целые — десятичная
 * запись, флажки — {@code true/false}. Главное правило: если текст в поле сейчас некорректен
 * (пользователь не допечатал сумму), он сохраняется <b>как набран</b>, чтобы после сбоя вернуться
 * в поле ровно в том виде, в каком его оставили.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class FieldValues {

    /** Формат дня года в снимке и в поле редактора ежегодного правила. */
    public static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MM-dd");

    private FieldValues() {
    }

    /**
     * Разбирает сумму, введённую пользователем.
     *
     * @param text текст поля ({@code "80 000,00"}, {@code "80000.5"})
     * @return сумма или пусто, если текст пустой или некорректный
     */
    public static Optional<Money> parseMoney(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Money.parse(text));
        } catch (IllegalArgumentException | ArithmeticException e) {
            // Недопечатанная сумма — обычное состояние поля, а не ошибка программы.
            return Optional.empty();
        }
    }

    /**
     * Каноническая форма суммы для снимка.
     *
     * @param text текст поля
     * @return {@code ""} для пустого поля, {@code "95000,00"} для корректной суммы, иначе текст как есть
     */
    public static String canonicalMoney(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return parseMoney(text).map(Money::formatPlain).orElse(text);
    }

    /**
     * Текст для показа суммы из снимка в поле ввода.
     *
     * @param canonical значение из снимка
     * @return {@code "95 000,00"} для корректной суммы, иначе значение как есть
     */
    public static String displayMoney(String canonical) {
        if (canonical == null || canonical.isBlank()) {
            return "";
        }
        return parseMoney(canonical).map(Money::format).orElse(canonical);
    }

    /**
     * Разбирает дату в форме {@code ГГГГ-ММ-ДД} или {@code ДД.ММ.ГГГГ}.
     *
     * @param text текст
     * @return дата или пусто
     */
    public static Optional<LocalDate> parseDate(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(DateFormats.parse(text));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Каноническая форма даты для снимка.
     *
     * @param text текст редактора даты
     * @return {@code ""}, ISO-дата или текст как есть
     */
    public static String canonicalDate(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return parseDate(text).map(DateFormats::iso).orElse(text);
    }

    /**
     * Разбирает целое число (поле Spinner).
     *
     * @param text текст
     * @return число или пусто
     */
    public static Optional<Integer> parseInt(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(text.strip()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Разбирает день года: {@code ММ-ДД} (как в файле плана) или {@code ДД.ММ} (как привычно по-русски).
     *
     * @param text текст
     * @return день года или пусто
     */
    public static Optional<MonthDay> parseMonthDay(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String t = text.strip();
        try {
            if (t.contains(".")) {
                String[] parts = t.split("\\.");
                if (parts.length != 2) {
                    return Optional.empty();
                }
                return Optional.of(MonthDay.of(Integer.parseInt(parts[1]), Integer.parseInt(parts[0])));
            }
            return Optional.of(MonthDay.parse("--" + t));
        } catch (DateTimeException | NumberFormatException e) {
            // DateTimeException покрывает и DateTimeParseException, и «31 февраля» из MonthDay.of.
            return Optional.empty();
        }
    }

    /**
     * Каноническая форма дня года.
     *
     * @param text текст поля
     * @return {@code ""}, {@code "03-15"} или текст как есть
     */
    public static String canonicalMonthDay(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return parseMonthDay(text).map(MONTH_DAY::format).orElse(text);
    }
}
