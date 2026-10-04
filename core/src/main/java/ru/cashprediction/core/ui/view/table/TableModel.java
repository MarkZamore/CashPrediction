package ru.cashprediction.core.ui.view.table;

import java.util.List;

/**
 * Ленивая модель таблицы прогноза (спецификация v2, §5.2; архитектура §3.4). Не материализует тексты всех строк:
 * до 200 000 строк, клиенты виртуальные (FX {@code TableView<Integer>} индексов, Swing {@code AbstractTableModel},
 * web - страницы по 300 строк через запрос {@code rows}).
 *
 * <p>Модель неизменяема в пределах ревизии: при любом изменении плана, фильтров, периода или группы прошедших
 * контроллер создаёт новую модель с новой {@link #revision()}. Реализация потокобезопасна для чтения.</p>
 */
public interface TableModel {

    /** @return номер ревизии (web отбрасывает ответы устаревших ревизий) */
    long revision();

    /** @return восемь колонок по порядку */
    List<ColumnSpec> columns();

    /** @return число видимых строк, включая PAST_HEADER и итоги */
    int rowCount();

    /**
     * Строка по индексу.
     *
     * @param index индекс {@code 0..rowCount()-1}
     * @return готовая строка
     * @throws IndexOutOfBoundsException если индекс вне диапазона
     */
    TableRowView row(int index);

    /**
     * Подсказка ячейки: одна на строку во всех колонках, кроме строки 13 для сумм RULE (§5.2 «Подсказки ячеек»).
     *
     * @param index    индекс строки
     * @param columnId id колонки
     * @return текст с переводами строк или пустая строка
     */
    String tooltip(int index, String columnId);

    /** Возвращает подсказку с явными позициями значков; сторонние модели по умолчанию дают обычный текст. */
    default DecoratedTooltip decoratedTooltip(int index, String columnId) {
        return DecoratedTooltip.plain(tooltip(index, columnId));
    }

    /**
     * Индекс строки по идентификатору.
     *
     * @param rowId идентификатор строки
     * @return индекс или -1, если строка не видна (скрыта фильтром, периодом или свёрнутой группой)
     */
    int indexOf(String rowId);

    /** @return идентификатор выделенной строки или пустая строка */
    String selectedRowId();

    /**
     * Строка, к которой прокрутить при открытии плана и смене периода: первая видимая строка START или строка
     * события с датой ≥ сегодня (строки группы и итогов не считаются); контроллер делает её верхней видимой
     * ({@code RevealMode.SCROLL_TO_TOP}), выделение не ставится. После восстановления сеанса контроллер вместо этого
     * показывает восстановленное выделение ({@link #selectedRowId()}, {@code RevealMode.SELECT_AND_SCROLL}).
     *
     * @return идентификатор строки или пустая строка, если все видимые строки раньше сегодня
     */
    String scrollToRowId();

    /** @return пустое состояние или {@code null}, если строки есть */
    Placeholder placeholder();
}
