package ru.cashprediction.core.ui.command;

import ru.cashprediction.core.app.AppState;

/**
 * Единая таблица доступности команд (архитектура §3.2): её используют меню, тулбар, контекстные меню, пустые
 * состояния и диспетчер горячих клавиш, поэтому «серый пункт» и «подсказка при нажатии» не расходятся.
 *
 * <p><b>Правила (спецификация v2, §3–§5):</b></p>
 * <ul>
 *   <li>всегда доступны: file.new/open/openFile/sample/save/saveAs/rename/autosave/cashMemory/exit, edit.add*,
 *       edit.planSettings/actualize/reconcile, все view.*, tools.goal/validate/cleanup/currency, recovery.*
 *       (кроме {@code recovery.store.server} — отключён всегда), help.*, filter.clear;</li>
 *   <li>{@code file.exportCsv}, {@code file.savePng}, {@code chart.*}: прогноз рассчитан, иначе
 *       {@code status.hint.noForecast};</li>
 *   <li>{@code edit.edit}, {@code row.edit}: выделена строка START, RULE или ONE_TIME; нет выделения —
 *       {@code status.hint.noRow}; строка WHAT_IF — {@code status.hint.whatIfRow};</li>
 *   <li>{@code edit.delete}, {@code row.delete}: RULE или ONE_TIME, иначе {@code status.hint.noOperation};</li>
 *   <li>{@code edit.adjust}, {@code row.adjust}, {@code row.goToRule}, {@code row.disableRule}: RULE, иначе
 *       {@code status.hint.noRuleEvent};</li>
 *   <li>{@code edit.skip}, {@code row.skip}, {@code row.quickEdit}: RULE и не пропущено, иначе
 *       {@code status.hint.noRuleEvent} или {@code status.hint.alreadySkipped};</li>
 *   <li>{@code edit.reset}, {@code row.reset}: RULE и (скорректировано или пропущено), иначе
 *       {@code status.hint.noAdjustment};</li>
 *   <li>{@code edit.undo}/{@code edit.redo}: canUndo/canRedo, иначе {@code status.hint.nothingToUndo}/{@code nothingToRedo};</li>
 *   <li>{@code whatIf.apply}, {@code whatIf.reset}: режим «что-если» активен, иначе {@code status.hint.whatIfOff};</li>
 *   <li>{@code card.showInTable}: у карточки есть дата; {@code row.copy}, {@code row.addOneTime}, {@code total.copy},
 *       {@code past.toggle}, {@code card.copyValue}: всегда;</li>
 *   <li>{@code ui.menuBar}, {@code ui.contextMenu}: всегда (второе — при наличии выделения или карточки в фокусе).</li>
 * </ul>
 * <p>Модальность здесь не учитывается: её проверяет {@code AppController} до обращения к таблице.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class CommandAvailability {

    private CommandAvailability() {
    }

    /**
     * Доступность команды в текущем состоянии.
     *
     * @param command команда
     * @param args    аргументы (строка, дата, карточка); для команд меню «Правка» строка берётся из выделения состояния
     * @param state   неизменяемое состояние приложения
     * @return доступность и ключ подсказки
     */
    public static Availability of(CommandId command, CommandArgs args, AppState state) {
        throw new UnsupportedOperationException("S1: core-menu - CommandAvailability.of");
    }
}
