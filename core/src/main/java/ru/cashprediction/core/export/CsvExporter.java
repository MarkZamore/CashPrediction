package ru.cashprediction.core.export;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import ru.cashprediction.core.forecast.Flags;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.Origin;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.util.DateFormats;
import ru.cashprediction.core.util.RuText;

/**
 * Экспорт таблицы прогноза в CSV для Excel и других табличных программ.
 *
 * <p>Формат выбран «дружелюбным к Excel»:</p>
 * <ul>
 *   <li>заголовок {@code Дата;День;Операция;Категория;Доход;Расход;Баланс;Отметки;Заметка} (с выбранным разделителем);</li>
 *   <li>даты {@code дд.ММ.гггг}, суммы без разделителя тысяч с запятой ({@code 95000,00}) — русский Excel
 *       распознаёт их как числа, а пробел в {@code 95 000,00} превратил бы ячейку в текст;</li>
 *   <li>доход и расход — в отдельных столбцах, без знака; у строки начального баланса оба пусты;</li>
 *   <li>отметки — словами через запятую: «изменена сумма, перенесено, сдвиг с выходного, разовая, пропущено, что-если»;</li>
 *   <li>кавычки по RFC 4180, если ячейка содержит разделитель, кавычку или перевод строки;
 *       концы строк CRLF; при {@code bom=true} — BOM в начале.</li>
 * </ul>
 *
 * <p>Экспорт возвращает текст, а запись на диск (атомарную) выполняет вызывающий код: так ядро не зависит
 * от того, выбрал ли пользователь файл через {@code FileChooser}, {@code JFileChooser} или скачивает его из браузера.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class CsvExporter {

    /** Названия столбцов по порядку. */
    public static final List<String> HEADER = List.of("Дата", "День", "Операция", "Категория", "Доход", "Расход", "Баланс", "Отметки", "Заметка");

    /** Конец строки CSV по RFC 4180. */
    private static final String CRLF = "\r\n";

    private CsvExporter() {
    }

    /**
     * Формирует CSV-текст.
     *
     * @param forecast прогноз
     * @param options  параметры экспорта
     * @return полный текст файла (с BOM, если он запрошен)
     */
    public static String toCsv(Forecast forecast, CsvOptions options) {
        Objects.requireNonNull(forecast, "forecast");
        Objects.requireNonNull(options, "options");
        char sep = options.separator();
        LocalDate from = options.from() == null ? forecast.startDate() : options.from();
        LocalDate to = options.to() == null ? forecast.endDate() : options.to();

        StringBuilder sb = new StringBuilder();
        if (options.bom()) {
            sb.append('﻿');
        }
        appendLine(sb, HEADER, sep);
        for (ForecastRow row : forecast.rowsBetween(from, to)) {
            boolean start = row.origin() == Origin.START;
            String amount = row.amount().abs().formatPlain();
            List<String> cells = List.of(
                    DateFormats.ru(row.date()),
                    RuText.weekdayShort(row.date().getDayOfWeek()),
                    row.title(),
                    row.category(),
                    !start && row.kind() == Kind.INCOME ? amount : "",
                    !start && row.kind() == Kind.EXPENSE ? amount : "",
                    row.balanceAfter().formatPlain(),
                    String.join(", ", marks(row)),
                    row.note());
            appendLine(sb, cells, sep);
        }
        return sb.toString();
    }

    /**
     * Отметки строки словами, в том же порядке, что и значки в таблице.
     *
     * @param row строка прогноза
     * @return неизменяемый список слов; пустой, если отметок нет
     */
    public static List<String> marks(ForecastRow row) {
        Flags f = row.flags();
        List<String> marks = new ArrayList<>(6);
        if (f.amountChanged()) {
            marks.add("изменена сумма");
        }
        if (f.moved()) {
            marks.add("перенесено");
        }
        if (f.shifted()) {
            marks.add("сдвиг с выходного");
        }
        if (row.origin() == Origin.ONE_TIME) {
            marks.add("разовая");
        }
        if (f.skipped()) {
            marks.add("пропущено");
        }
        if (f.whatIf()) {
            marks.add("что-если");
        }
        return List.copyOf(marks);
    }

    /**
     * Экранирует ячейку по RFC 4180.
     *
     * @param cell      текст ячейки
     * @param separator разделитель ячеек
     * @return ячейка как есть или в кавычках с удвоенными внутренними кавычками
     */
    public static String escape(String cell, char separator) {
        String text = cell == null ? "" : cell;
        boolean needsQuotes = text.indexOf(separator) >= 0 || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
        return needsQuotes ? '"' + text.replace("\"", "\"\"") + '"' : text;
    }

    private static void appendLine(StringBuilder sb, List<String> cells, char separator) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(separator);
            }
            sb.append(escape(cells.get(i), separator));
        }
        sb.append(CRLF);
    }
}
