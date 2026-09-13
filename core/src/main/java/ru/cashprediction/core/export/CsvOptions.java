package ru.cashprediction.core.export;

import java.time.LocalDate;

/**
 * Параметры экспорта прогноза в CSV (результат диалога «Экспорт CSV»).
 *
 * <p>Record неизменяем и потокобезопасен.</p>
 *
 * @param separator разделитель ячеек: {@code ;} для русского Excel, {@code ,} для остальных программ, либо табуляция
 * @param bom       добавлять ли в начало BOM (U+FEFF): без него Excel открывает UTF-8 как ANSI и портит кириллицу
 * @param from      первый день диапазона; {@code null} — с начала прогноза
 * @param to        последний день диапазона; {@code null} — до конца прогноза
 */
public record CsvOptions(char separator, boolean bom, LocalDate from, LocalDate to) {

    /** Настройки по умолчанию: точка с запятой, BOM, весь прогноз. */
    public static final CsvOptions DEFAULT = new CsvOptions(';', true, null, null);

    /** Проверяет разделитель и порядок дат. */
    public CsvOptions {
        if (separator == '"' || separator == '\r' || separator == '\n') {
            throw new IllegalArgumentException("Недопустимый разделитель CSV");
        }
        if (from != null && to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("Конец диапазона экспорта раньше его начала");
        }
    }

    /**
     * @param newFrom первый день или {@code null}
     * @param newTo   последний день или {@code null}
     * @return копия с другим диапазоном дат
     */
    public CsvOptions withRange(LocalDate newFrom, LocalDate newTo) {
        return new CsvOptions(separator, bom, newFrom, newTo);
    }
}
