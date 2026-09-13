package ru.cashprediction.core.markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Разбор и вывод таблиц Markdown в том виде, в каком они хранятся в файлах плана.
 *
 * <p>Раскладка при выводе (выровненные колонки, чтобы файл удобно читался в Блокноте):</p>
 * <ul>
 *   <li>ширина колонки = наибольшая длина (в кодовых точках Unicode) заголовка и всех ячеек колонки;</li>
 *   <li>строка данных: {@code "| " + ячейки, дополненные пробелами справа, через " | " + " |"};</li>
 *   <li>разделитель: {@code "|" + дефисы (ширина + 2) через "|" + "|"}.</li>
 * </ul>
 *
 * <p>Символ «|» внутри ячейки записывается как {@code \|} и при чтении превращается обратно в «|».
 * Перевод строки внутри ячейки невозможен (он разорвал бы таблицу), поэтому при выводе заменяется пробелом.</p>
 *
 * <p>При чтении порядок и выравнивание не важны: лишние пробелы вокруг ячеек отбрасываются,
 * ведущий и завершающий «|» необязательны для последней ячейки.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class MarkdownTable {

    /** Ячейка строки-разделителя: дефисы с необязательными двоеточиями выравнивания ({@code :---:}). */
    private static final Pattern SEPARATOR_CELL = Pattern.compile(":?-+:?");

    private MarkdownTable() {
    }

    /**
     * Проверяет, похожа ли строка на строку таблицы.
     *
     * @param line строка файла
     * @return {@code true}, если после отбрасывания пробелов строка начинается с «|»
     */
    public static boolean isTableRow(String line) {
        return line != null && line.strip().startsWith("|");
    }

    /**
     * Разбивает строку таблицы на ячейки.
     *
     * <p>Пробелы вокруг каждой ячейки отбрасываются, {@code \|} превращается в «|». Другие обратные
     * косые черты сохраняются как есть: путь {@code C:\Users} в заметке не должен портиться.</p>
     *
     * @param line строка вида {@code | r1 | Зарплата | доход |}
     * @return список ячеек без обрамляющих «|», например {@code [r1, Зарплата, доход]}
     */
    public static List<String> parseRow(String line) {
        Objects.requireNonNull(line, "line");
        String t = line.strip();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        // Ведущий «|» — просто рамка таблицы, ячейку он не открывает.
        int i = t.startsWith("|") ? 1 : 0;
        boolean endedWithPipe = i == 1;
        for (; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\\' && i + 1 < t.length() && t.charAt(i + 1) == '|') {
                cell.append('|');
                i++;
                endedWithPipe = false;
            } else if (c == '|') {
                cells.add(cell.toString().strip());
                cell.setLength(0);
                endedWithPipe = true;
            } else {
                cell.append(c);
                endedWithPipe = false;
            }
        }
        // Текст после последнего «|» — последняя ячейка строки, у которой забыли закрывающую рамку.
        if (!endedWithPipe) {
            cells.add(cell.toString().strip());
        }
        return cells;
    }

    /**
     * Проверяет, является ли строка разделителем заголовка: {@code |----|:---:|}.
     *
     * @param line строка файла
     * @return {@code true}, если все ячейки строки состоят из дефисов (с необязательными двоеточиями)
     */
    public static boolean isSeparatorRow(String line) {
        if (!isTableRow(line)) {
            return false;
        }
        List<String> cells = parseRow(line);
        if (cells.isEmpty()) {
            return false;
        }
        for (String cell : cells) {
            if (!SEPARATOR_CELL.matcher(cell.replace(" ", "")).matches()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Готовит значение к записи в ячейку: экранирует «|» и заменяет переводы строк пробелами.
     *
     * @param value исходное значение, {@code null} считается пустым
     * @return текст ячейки
     */
    public static String escapeCell(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').replace("|", "\\|");
    }

    /**
     * Выводит выровненную таблицу.
     *
     * @param headers заголовки колонок
     * @param rows    строки данных; короткая строка дополняется пустыми ячейками, лишние ячейки отбрасываются
     * @return строки таблицы без символов перевода строки: заголовок, разделитель, данные
     */
    public static List<String> format(List<String> headers, List<? extends List<String>> rows) {
        int columns = headers.size();
        List<List<String>> escaped = new ArrayList<>(rows.size() + 1);
        escaped.add(headers.stream().map(MarkdownTable::escapeCell).toList());
        for (List<String> row : rows) {
            List<String> cells = new ArrayList<>(columns);
            for (int c = 0; c < columns; c++) {
                cells.add(c < row.size() ? escapeCell(row.get(c)) : "");
            }
            escaped.add(cells);
        }
        int[] widths = new int[columns];
        for (List<String> row : escaped) {
            for (int c = 0; c < columns; c++) {
                // Длина в кодовых точках, а не в char: так же считает человек, глядя на выравнивание.
                widths[c] = Math.max(widths[c], row.get(c).codePointCount(0, row.get(c).length()));
            }
        }
        List<String> lines = new ArrayList<>(escaped.size() + 1);
        lines.add(formatRow(escaped.get(0), widths));
        StringBuilder separator = new StringBuilder("|");
        for (int width : widths) {
            separator.append("-".repeat(width + 2)).append('|');
        }
        lines.add(separator.toString());
        for (int r = 1; r < escaped.size(); r++) {
            lines.add(formatRow(escaped.get(r), widths));
        }
        return lines;
    }

    private static String formatRow(List<String> cells, int[] widths) {
        StringBuilder sb = new StringBuilder("| ");
        for (int c = 0; c < widths.length; c++) {
            if (c > 0) {
                sb.append(" | ");
            }
            String cell = cells.get(c);
            sb.append(cell).append(" ".repeat(widths[c] - cell.codePointCount(0, cell.length())));
        }
        return sb.append(" |").toString();
    }
}
