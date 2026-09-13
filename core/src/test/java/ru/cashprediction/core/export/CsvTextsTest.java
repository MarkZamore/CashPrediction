package ru.cashprediction.core.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.forecast.Flags;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.Origin;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.TxId;

/**
 * Этап S0.5: заголовок CSV, слова отметок и ошибки параметров экспорта берутся из каталога
 * ({@code export_ru.properties}) и совпадают с прежними русскими строками кода буква в букву.
 */
class CsvTextsTest {

    /** Названия столбцов по порядку. */
    @Test
    void header() {
        assertEquals(List.of("Дата", "День", "Операция", "Категория", "Доход", "Расход", "Баланс", "Отметки", "Заметка"),
                CsvExporter.HEADER);
    }

    /** Все отметки строки словами в порядке значков таблицы. */
    @Test
    void allMarks() {
        LocalDate day = LocalDate.of(2026, 9, 10);
        ForecastRow row = new ForecastRow(day, day, "Покупка", Kind.EXPENSE, "", Money.ofMajor(-1), Money.ZERO, Origin.ONE_TIME,
                null, new TxId("t1"), new Flags(true, true, true, true, false, true), "");
        assertEquals(List.of("изменена сумма", "перенесено", "сдвиг с выходного", "разовая", "пропущено", "что-если"),
                CsvExporter.marks(row));
    }

    /** Ошибки параметров экспорта. */
    @Test
    void optionErrors() {
        assertEquals("Недопустимый разделитель CSV",
                assertThrows(IllegalArgumentException.class, () -> new CsvOptions('"', true, null, null)).getMessage());
        assertEquals("Конец диапазона экспорта раньше его начала",
                assertThrows(IllegalArgumentException.class,
                        () -> new CsvOptions(';', true, LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1))).getMessage());
    }
}
