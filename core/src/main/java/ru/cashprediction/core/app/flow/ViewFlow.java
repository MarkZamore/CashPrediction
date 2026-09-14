package ru.cashprediction.core.app.flow;

import java.time.LocalDate;
import java.util.Objects;
import ru.cashprediction.core.document.PeriodChoice;
import ru.cashprediction.core.document.ViewMode;
import ru.cashprediction.core.ui.command.CommandId;

/**
 * Меню «Вид», фильтр, группа прошедших и «Показать в таблице» (спецификация v2, §3.3, §4, §5.1, §5.2).
 *
 * <p>Вид, период и флажки записываются в settings.md через 700 мс. «Показать в таблице с dd.MM.yyyy»:
 * переключиться на таблицу, развернуть прошедшие, если нужно, выделить первую строку с датой ≥ даты и прокрутить к
 * ней ({@code RevealMode.SELECT_AND_SCROLL}); если такой строки нет — {@code status.msg.noRowAfter}. После открытия
 * плана и смены периода — прокрутка к первой строке с датой ≥ сегодня ({@code SCROLL_TO_TOP}), группа прошедших
 * сворачивается при каждом открытии плана.</p>
 *
 * <p>Не потокобезопасен: только поток контроллера.</p>
 */
public final class ViewFlow {

    private final FlowContext context;

    /**
     * Создаёт поток.
     *
     * @param context контекст контроллера
     */
    public ViewFlow(FlowContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /** @return контекст контроллера */
    public FlowContext context() {
        return context;
    }

    /**
     * {@code view.table}/{@code view.chart}; повторный выбор текущего режима ничего не меняет.
     *
     * @param mode режим
     */
    public void setMode(ViewMode mode) {
        throw new UnsupportedOperationException("S2: core-app-edit - ViewFlow.setMode");
    }

    /**
     * {@code view.flag.*}: переключить флажок вида.
     *
     * @param flag команда флажка
     */
    public void toggleFlag(CommandId flag) {
        throw new UnsupportedOperationException("S2: core-app-edit - ViewFlow.toggleFlag");
    }

    /**
     * {@code view.period.*}: период показа (план не меняется), затем прокрутка к сегодня.
     *
     * @param period период
     */
    public void setPeriod(PeriodChoice period) {
        throw new UnsupportedOperationException("S2: core-app-edit - ViewFlow.setPeriod");
    }

    /**
     * {@code view.horizonSlider} отпущен: горизонт {@code value} месяцев, {@code undo.horizon}; при горизонте &gt; 120
     * план не меняется, пока ползунок не сдвинут.
     *
     * @param value месяцев 1..120
     */
    public void horizonSliderCommit(int value) {
        throw new UnsupportedOperationException("S2: core-app-edit - ViewFlow.horizonSliderCommit");
    }

    /** {@code view.horizonMonths}: TEXT_INPUT customMonths §6.23. */
    public void customMonths() {
        throw new UnsupportedOperationException("S2: core-app-edit - ViewFlow.customMonths");
    }

    /**
     * Текст фильтра (после задержки 300 мс клиента): совпадение без учёта регистра, «ё» = «е», START проходит всегда.
     *
     * @param text текст поля
     */
    public void filterText(String text) {
        throw new UnsupportedOperationException("S2: core-app-edit - ViewFlow.filterText");
    }

    /** {@code filter.clear}: очистить фильтр. */
    public void clearFilter() {
        throw new UnsupportedOperationException("S2: core-app-edit - ViewFlow.clearFilter");
    }

    /** {@code view.focusFilter}: {@code port.focus(FILTER)}. */
    public void focusFilter() {
        throw new UnsupportedOperationException("S2: core-app-edit - ViewFlow.focusFilter");
    }

    /** {@code filter.focusTable}: применить текст фильтра немедленно и {@code port.focus(TABLE)}. */
    public void focusTable() {
        throw new UnsupportedOperationException("S2: core-app-edit - ViewFlow.focusTable");
    }

    /** {@code past.toggle}: развернуть или свернуть группу «Прошедшие события». */
    public void togglePast() {
        throw new UnsupportedOperationException("S2: core-app-edit - ViewFlow.togglePast");
    }

    /**
     * {@code card.showInTable}, {@code chart.showInTable}, двойной щелчок по карточке или графику.
     *
     * @param date дата
     */
    public void showInTable(LocalDate date) {
        throw new UnsupportedOperationException("S2: core-app-edit - ViewFlow.showInTable");
    }

    /**
     * Выделение строки пользователем; скрытая в свёрнутой группе строка раскрывает группу.
     *
     * @param rowId id строки или пустая строка
     */
    public void selectRow(String rowId) {
        throw new UnsupportedOperationException("S2: core-app-edit - ViewFlow.selectRow");
    }

    /** Прокрутка к первой строке с датой ≥ сегодня (открытие плана, смена периода). */
    public void scrollToToday() {
        throw new UnsupportedOperationException("S2: core-app-edit - ViewFlow.scrollToToday");
    }
}
