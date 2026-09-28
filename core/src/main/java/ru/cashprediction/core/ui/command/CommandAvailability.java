package ru.cashprediction.core.ui.command;

import java.util.Objects;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.ui.view.table.RowKind;

/**
 * Единая таблица доступности команд (архитектура §3.2): её используют меню, тулбар, контекстные меню, пустые
 * состояния и диспетчер горячих клавиш, поэтому «серый пункт» и «подсказка при нажатии» не расходятся.
 *
 * <p><b>Правила (спецификация v2, §3-§5, подсказки §8.3):</b></p>
 * <ul>
 *   <li>всегда доступны: file.new/open/openFile/sample/recent.open/save/saveAs/rename/autosave/cashMemory/exit,
 *       edit.add*, edit.planSettings/actualize/reconcile, все view.*, tools.goal/validate/cleanup/currency,
 *       whatIf.income/expense/extra, recovery.* (кроме {@code recovery.store.server} - отключён всегда, без подсказки),
 *       help.*, filter.*, row.addOneTime, row.copy, total.copy, past.toggle, card.copyValue, ui.menuBar,
 *       preview.adjust;</li>
 *   <li>{@code file.exportCsv}, {@code file.savePng}: прогноз рассчитан, иначе {@value #HINT_NO_FORECAST};</li>
 *   <li>{@code chart.showInTable}, {@code chart.addOneTime}: прогноз рассчитан ({@value #HINT_NO_FORECAST}) и под
 *       указателем есть дата ({@code args.date}); вне области построения - отключены без подсказки;</li>
 *   <li>{@code card.showInTable}: у карточки есть дата ({@code args.date}), иначе отключён без подсказки;</li>
 *   <li>строка: {@code args.rowId}, а если он пуст - выделение состояния; нет строки - {@value #HINT_NO_ROW}. Вид
 *       строки - {@link RowRef#resolve};</li>
 *   <li>{@code edit.edit}, {@code row.edit}: START, RULE или ONE_TIME; WHAT_IF - {@value #HINT_WHAT_IF_ROW}; другая
 *       строка (итог, группа прошедших) - {@value #HINT_NO_OPERATION};</li>
 *   <li>{@code edit.delete}, {@code row.delete}: RULE или ONE_TIME, иначе {@value #HINT_NO_OPERATION};</li>
 *   <li>{@code edit.adjust}, {@code row.adjust}, {@code row.goToRule}, {@code row.disableRule}: RULE, иначе
 *       {@value #HINT_NO_RULE_EVENT};</li>
 *   <li>{@code edit.skip}, {@code row.skip}, {@code row.quickEdit}: RULE ({@value #HINT_NO_RULE_EVENT}) и не пропущено
 *       ({@value #HINT_ALREADY_SKIPPED});</li>
 *   <li>{@code edit.reset}, {@code row.reset}: RULE ({@value #HINT_NO_RULE_EVENT}) и скорректировано или пропущено
 *       ({@value #HINT_NO_ADJUSTMENT});</li>
 *   <li>{@code edit.undo}/{@code edit.redo}: canUndo/canRedo, иначе {@value #HINT_NOTHING_TO_UNDO} /
 *       {@value #HINT_NOTHING_TO_REDO};</li>
 *   <li>{@code whatIf.apply}, {@code whatIf.reset}: режим «что-если» активен, иначе {@value #HINT_WHAT_IF_OFF};</li>
 *   <li>{@code ui.contextMenu} (Shift+F10 / Menu): есть карточка в фокусе ({@code args.cardId}) или строка, иначе
 *       {@value #HINT_NO_ROW}.</li>
 * </ul>
 * <p>Модальность здесь не учитывается: её проверяет {@code AppController} до обращения к таблице. Переключение группы
 * прошедших по Enter на строке PAST_HEADER (§3.2 сноска) выполняет контроллер по модели таблицы до обращения к
 * таблице доступности: по одному идентификатору строки группа не отличается от другой служебной строки.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class CommandAvailability {

    /** «Выберите строку в таблице прогноза». */
    public static final String HINT_NO_ROW = "status.hint.noRow";
    /** «Выберите строку регулярной или разовой операции». */
    public static final String HINT_NO_OPERATION = "status.hint.noOperation";
    /** «Выберите событие регулярной операции». */
    public static final String HINT_NO_RULE_EVENT = "status.hint.noRuleEvent";
    /** «У события нет корректировки: оно и так идёт по правилу». */
    public static final String HINT_NO_ADJUSTMENT = "status.hint.noAdjustment";
    /** «Событие уже пропущено». */
    public static final String HINT_ALREADY_SKIPPED = "status.hint.alreadySkipped";
    /** «Строка «что-если» задаётся в меню Инструменты → Что-если». */
    public static final String HINT_WHAT_IF_ROW = "status.hint.whatIfRow";
    /** «Режим «что-если» выключен». */
    public static final String HINT_WHAT_IF_OFF = "status.hint.whatIfOff";
    /** «Отменять нечего». */
    public static final String HINT_NOTHING_TO_UNDO = "status.hint.nothingToUndo";
    /** «Повторять нечего». */
    public static final String HINT_NOTHING_TO_REDO = "status.hint.nothingToRedo";
    /** «Прогноз не рассчитан: сначала исправьте план (Инструменты → Проверить план)». */
    public static final String HINT_NO_FORECAST = "status.hint.noForecast";

    private CommandAvailability() {
    }

    /**
     * Доступность команды в текущем состоянии.
     *
     * @param command команда
     * @param args    аргументы (строка, дата, карточка); для команд меню «Правка» строка берётся из выделения
     *                состояния, если {@code args.rowId} пуст; {@code null} - без аргументов
     * @param state   неизменяемое состояние приложения
     * @return доступность и ключ подсказки
     */
    public static Availability of(CommandId command, CommandArgs args, AppState state) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(state, "state");
        CommandArgs a = args == null ? CommandArgs.NONE : args;
        // Перечислены все команды без ветки default: новая команда не получит доступность «молча».
        return switch (command) {
            case FILE_NEW, FILE_OPEN, FILE_OPEN_FILE, FILE_SAMPLE, FILE_RECENT_OPEN, FILE_SAVE, FILE_SAVE_AS,
                 FILE_RENAME, FILE_AUTOSAVE, FILE_CASH_MEMORY, FILE_EXIT -> Availability.ENABLED;
            case FILE_EXPORT_CSV, FILE_SAVE_PNG -> forecast(state);
            case EDIT_ADD_INCOME, EDIT_ADD_EXPENSE, EDIT_ADD_ONE_TIME, EDIT_PLAN_SETTINGS, EDIT_ACTUALIZE,
                 EDIT_RECONCILE -> Availability.ENABLED;
            case EDIT_EDIT, ROW_EDIT -> editable(row(a, state));
            case EDIT_DELETE, ROW_DELETE -> deletable(row(a, state));
            case EDIT_ADJUST, ROW_ADJUST, ROW_GO_TO_RULE, ROW_DISABLE_RULE -> ruleEvent(row(a, state));
            case EDIT_SKIP, ROW_SKIP, ROW_QUICK_EDIT -> notSkipped(row(a, state));
            case EDIT_RESET, ROW_RESET -> adjusted(row(a, state));
            case EDIT_UNDO -> state.document().canUndo() ? Availability.ENABLED
                    : Availability.disabled(HINT_NOTHING_TO_UNDO);
            case EDIT_REDO -> state.document().canRedo() ? Availability.ENABLED
                    : Availability.disabled(HINT_NOTHING_TO_REDO);
            case VIEW_TABLE, VIEW_CHART, VIEW_FLAG_SHOW_INCOME, VIEW_FLAG_SHOW_EXPENSE, VIEW_FLAG_SHOW_ONE_TIME,
                 VIEW_FLAG_SHOW_SKIPPED, VIEW_FLAG_MONTH_TOTALS, VIEW_FLAG_CHART_MARKERS, VIEW_FLAG_CHART_BARS,
                 VIEW_FLAG_SUMMARY_PANEL, VIEW_PERIOD_M3, VIEW_PERIOD_M6, VIEW_PERIOD_M12, VIEW_PERIOD_M24,
                 VIEW_PERIOD_ALL, VIEW_HORIZON_SLIDER, VIEW_HORIZON_MONTHS, VIEW_FOCUS_FILTER -> Availability.ENABLED;
            case TOOLS_GOAL, WHAT_IF_INCOME, WHAT_IF_EXPENSE, WHAT_IF_EXTRA, TOOLS_VALIDATE, TOOLS_CLEANUP,
                 TOOLS_CURRENCY -> Availability.ENABLED;
            case WHAT_IF_APPLY, WHAT_IF_RESET -> state.view().whatIf().isNone()
                    ? Availability.disabled(HINT_WHAT_IF_OFF) : Availability.ENABLED;
            case RECOVERY_STORE_REGISTRY, RECOVERY_STORE_XML, RECOVERY_SNAPSHOT_NOW, RECOVERY_SHOW_LAST,
                 RECOVERY_CLEAR, RECOVERY_SIMULATE_HALT, RECOVERY_SIMULATE_EXCEPTION -> Availability.ENABLED;
            // Web показывает хранилище сервера отмеченным и отключённым (§3.5, §10 №1): выбирать не из чего.
            case RECOVERY_STORE_SERVER -> Availability.disabled("");
            case HELP_ABOUT, HELP_HOTKEYS, HELP_FORMAT -> Availability.ENABLED;
            case FILTER_CLEAR, FILTER_FOCUS_TABLE -> Availability.ENABLED;
            case ROW_ADD_ONE_TIME, ROW_COPY, TOTAL_COPY, PAST_TOGGLE, CARD_COPY_VALUE -> Availability.ENABLED;
            case CARD_SHOW_IN_TABLE -> a.date() == null ? Availability.disabled("") : Availability.ENABLED;
            case CHART_SHOW_IN_TABLE, CHART_ADD_ONE_TIME -> !state.document().forecastAvailable()
                    ? Availability.disabled(HINT_NO_FORECAST)
                    : a.date() == null ? Availability.disabled("") : Availability.ENABLED;
            case UI_MENU_BAR -> Availability.ENABLED;
            case UI_CONTEXT_MENU -> !a.cardId().isEmpty() || !rowId(a, state).isEmpty()
                    ? Availability.ENABLED : Availability.disabled(HINT_NO_ROW);
            case PREVIEW_ADJUST -> Availability.ENABLED;
        };
    }

    private static Availability forecast(AppState state) {
        return state.document().forecastAvailable() ? Availability.ENABLED : Availability.disabled(HINT_NO_FORECAST);
    }

    private static String rowId(CommandArgs args, AppState state) {
        return args.rowId().isBlank() ? state.selectedRowId() : args.rowId();
    }

    private static RowRef row(CommandArgs args, AppState state) {
        return RowRef.resolve(state, rowId(args, state));
    }

    private static Availability editable(RowRef row) {
        if (row.isEmpty()) {
            return Availability.disabled(HINT_NO_ROW);
        }
        if (row.is(RowKind.START) || row.is(RowKind.RULE) || row.is(RowKind.ONE_TIME)) {
            return Availability.ENABLED;
        }
        return Availability.disabled(row.is(RowKind.WHAT_IF) ? HINT_WHAT_IF_ROW : HINT_NO_OPERATION);
    }

    private static Availability deletable(RowRef row) {
        if (row.isEmpty()) {
            return Availability.disabled(HINT_NO_ROW);
        }
        return row.is(RowKind.RULE) || row.is(RowKind.ONE_TIME)
                ? Availability.ENABLED : Availability.disabled(HINT_NO_OPERATION);
    }

    private static Availability ruleEvent(RowRef row) {
        if (row.isEmpty()) {
            return Availability.disabled(HINT_NO_ROW);
        }
        return row.is(RowKind.RULE) ? Availability.ENABLED : Availability.disabled(HINT_NO_RULE_EVENT);
    }

    private static Availability notSkipped(RowRef row) {
        Availability rule = ruleEvent(row);
        if (!rule.enabled()) {
            return rule;
        }
        return row.skipped() ? Availability.disabled(HINT_ALREADY_SKIPPED) : Availability.ENABLED;
    }

    private static Availability adjusted(RowRef row) {
        Availability rule = ruleEvent(row);
        if (!rule.enabled()) {
            return rule;
        }
        return row.adjusted() ? Availability.ENABLED : Availability.disabled(HINT_NO_ADJUSTMENT);
    }
}
