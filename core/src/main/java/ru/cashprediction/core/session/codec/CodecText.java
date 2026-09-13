package ru.cashprediction.core.session.codec;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import ru.cashprediction.core.session.SnapshotSchema;

/**
 * Общие для кодеков преобразования простых значений в текст и обратно.
 *
 * <p>Числа координат записываются без лишней дробной части ({@code 100}, а не {@code 100.0}),
 * как в образцах формата в плане: так файл XML и Markdown удобнее читать глазами. Разбор строгий:
 * {@code true}/{@code false} только строчными, момент времени только ISO-8601 в UTC.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
final class CodecText {

    /** Граница, до которой целое {@code double} точно представимо и печатается как {@code long}. */
    private static final double EXACT_LONG_LIMIT = 1e15;

    private CodecText() {
    }

    /**
     * Форматирует координату: целые — без дробной части, дробные — минимальной точной записью.
     *
     * @param value конечное число
     * @return например {@code 100} или {@code 100.5}
     */
    static String formatNumber(double value) {
        if (value == Math.rint(value) && Math.abs(value) < EXACT_LONG_LIMIT) {
            return Long.toString((long) value);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /**
     * Представляет координату для JSON: целые — {@link Long}, дробные — {@link BigDecimal}.
     *
     * @param value конечное число
     * @return число для {@code JsonWriter}
     */
    static Number jsonNumber(double value) {
        if (value == Math.rint(value) && Math.abs(value) < EXACT_LONG_LIMIT) {
            return (long) value;
        }
        return BigDecimal.valueOf(value);
    }

    /**
     * Разбирает число с плавающей точкой.
     *
     * @param text текст
     * @param what название значения для сообщения
     * @return конечное число
     * @throws SnapshotFormatException если текст не число
     */
    static double parseNumber(String text, String what) throws SnapshotFormatException {
        try {
            double value = Double.parseDouble(text.strip());
            if (!Double.isFinite(value)) {
                throw new NumberFormatException(text);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new SnapshotFormatException("Некорректное число в «" + what + "»: «" + text + "»", e);
        }
    }

    /**
     * Разбирает целое число.
     *
     * @param text текст
     * @param what название значения для сообщения
     * @return число
     * @throws SnapshotFormatException если текст не целое число
     */
    static long parseLong(String text, String what) throws SnapshotFormatException {
        try {
            return Long.parseLong(text.strip());
        } catch (NumberFormatException e) {
            throw new SnapshotFormatException("Некорректное целое число в «" + what + "»: «" + text + "»", e);
        }
    }

    /**
     * Разбирает логическое значение {@code true}/{@code false}.
     *
     * @param text текст
     * @param what название значения для сообщения
     * @return значение
     * @throws SnapshotFormatException если текст не {@code true} и не {@code false}
     */
    static boolean parseBoolean(String text, String what) throws SnapshotFormatException {
        return switch (text) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new SnapshotFormatException("Ожидалось true или false в «" + what + "»: «" + text + "»");
        };
    }

    /**
     * Разбирает момент времени ISO-8601 ({@code 2026-09-13T10:15:30.123Z}).
     *
     * @param text текст
     * @param what название значения для сообщения
     * @return момент
     * @throws SnapshotFormatException если формат неверен
     */
    static Instant parseInstant(String text, String what) throws SnapshotFormatException {
        try {
            return Instant.parse(text.strip());
        } catch (DateTimeParseException e) {
            throw new SnapshotFormatException("Некорректный момент времени в «" + what + "»: «" + text + "»", e);
        }
    }

    /**
     * Разбирает номер схемы и проверяет, что программа его поддерживает.
     *
     * @param text текст
     * @return номер схемы
     * @throws SnapshotFormatException если номер некорректен или новее поддерживаемого
     */
    static int parseSchema(String text) throws SnapshotFormatException {
        long value = parseLong(text, "schema");
        if (value < 1 || value > Integer.MAX_VALUE) {
            throw new SnapshotFormatException("Некорректная версия схемы снимка: " + text);
        }
        return checkSchema((int) value);
    }

    /**
     * Проверяет, что программа поддерживает схему.
     *
     * @param schema номер схемы
     * @return тот же номер
     * @throws SnapshotFormatException если схема не поддерживается
     */
    static int checkSchema(int schema) throws SnapshotFormatException {
        try {
            return SnapshotSchema.requireSupported(schema);
        } catch (IllegalArgumentException e) {
            throw new SnapshotFormatException(e.getMessage(), e);
        }
    }
}
