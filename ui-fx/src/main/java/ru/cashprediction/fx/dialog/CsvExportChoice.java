package ru.cashprediction.fx.dialog;

import ru.cashprediction.core.export.CsvOptions;

import java.time.LocalDate;

/**
 * Результат диалога «Экспорт CSV» до выбора файла.
 *
 * <p>Диапазон хранится как выбор «видимый период / весь горизонт», а не датами: даты периода
 * вычисляются в момент экспорта по текущему плану. Запись неизменяема.</p>
 *
 * @param separator    разделитель ячеек ({@code ;}, {@code ,} или табуляция)
 * @param bom          писать ли BOM в начало файла
 * @param wholeHorizon {@code true} — весь горизонт, {@code false} — только видимый период
 */
public record CsvExportChoice(char separator, boolean bom, boolean wholeHorizon) {

    /**
     * Строит параметры экспорта ядра.
     *
     * @param periodFrom первый день видимого периода
     * @param periodTo   последний день видимого периода
     * @return параметры {@link CsvOptions}
     */
    public CsvOptions toOptions(LocalDate periodFrom, LocalDate periodTo) {
        return wholeHorizon
                ? new CsvOptions(separator, bom, null, null)
                : new CsvOptions(separator, bom, periodFrom, periodTo);
    }
}
