package ru.cashprediction.core.ui.command;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.OneTimeTransaction;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.TxId;
import ru.cashprediction.core.ui.view.table.RowKind;

/**
 * Что известно о строке таблицы по её идентификатору: вид, пропущено ли событие, есть ли у него корректировка
 * (спецификация v2, §3.2 колонка «Доступен», §5.2 контекстное меню строки).
 *
 * <p>Нужен {@link CommandAvailability} и контекстному меню строки ({@code MenuModels}): оба решают по одной и той же
 * записи, поэтому серый пункт меню и подсказка горячей клавиши не расходятся.</p>
 *
 * <p><b>Как определяется вид</b> - по форме идентификатора ({@link ForecastRow#rowId()}) и по плану, без просмотра
 * всех строк прогноза (меню строится при каждом изменении, а строк может быть 200 000):</p>
 * <ul>
 *   <li>{@code start} - {@link RowKind#START};</li>
 *   <li>{@code whatif@ГГГГ-ММ-ДД} - {@link RowKind#WHAT_IF}, если включена доп. экономия «что-если»;</li>
 *   <li>{@code <правило>@ГГГГ-ММ-ДД} - {@link RowKind#RULE}, если правило есть в плане; корректировка ищется по ключу
 *       события ({@link Plan#findAdjustment}), пропуск - корректировка {@link Adjustment.Skip};</li>
 *   <li>иначе id разовой операции - {@link RowKind#ONE_TIME}, если операция есть в плане.</li>
 * </ul>
 * <p>Любой другой идентификатор (итог месяца, группа прошедших, устаревшая строка) даёт {@code kind == null}: таким
 * строкам команды «Правка» недоступны.</p>
 *
 * @param rowId    идентификатор строки без пробелов по краям или пустая строка - строки нет
 * @param kind     вид строки события или {@code null}, если идентификатор не указывает на событие плана
 * @param skipped  пропущено ли событие правила
 * @param adjusted есть ли у события правила корректировка (в том числе пропуск)
 */
public record RowRef(String rowId, RowKind kind, boolean skipped, boolean adjusted) {

    /** Строка не выбрана. */
    public static final RowRef NONE = new RowRef("", null, false, false);

    /** Начало идентификатора строки «что-если» ({@link ForecastRow#rowId()}). */
    private static final String WHAT_IF_PREFIX = "whatif@";

    /** Заменяет {@code null} пустой строкой. */
    public RowRef {
        rowId = Objects.requireNonNullElse(rowId, "");
    }

    /**
     * Определяет строку по идентификатору.
     *
     * @param state состояние приложения (план и «что-если»)
     * @param rowId идентификатор строки или пустая строка / {@code null}
     * @return сведения о строке; {@link #NONE} для пустого идентификатора
     */
    public static RowRef resolve(AppState state, String rowId) {
        Objects.requireNonNull(state, "state");
        String id = rowId == null ? "" : rowId.strip();
        if (id.isEmpty()) {
            return NONE;
        }
        Plan plan = state.document().plan();
        if (id.equals(ForecastRow.START_ROW_ID)) {
            return new RowRef(id, RowKind.START, false, false);
        }
        if (id.startsWith(WHAT_IF_PREFIX)) {
            // Строка «что-если» существует, только пока задана доп. экономия: иначе это устаревшее выделение.
            boolean exists = !state.view().whatIf().extraMonthlySaving().isZero()
                    && parseDate(id.substring(WHAT_IF_PREFIX.length())).isPresent();
            return new RowRef(id, exists ? RowKind.WHAT_IF : null, false, false);
        }
        if (id.indexOf('@') >= 0) {
            return resolveRuleEvent(plan, id);
        }
        try {
            return plan.findOneTime(new TxId(id)).isPresent() ? new RowRef(id, RowKind.ONE_TIME, false, false) : unknown(id);
        } catch (IllegalArgumentException e) {
            // Идентификатор другой формы (например, строки итога) не может быть id разовой операции.
            return unknown(id);
        }
    }

    /** @return пуст ли идентификатор (строка не выбрана) */
    public boolean isEmpty() {
        return rowId.isEmpty();
    }

    /**
     * @param expected вид строки
     * @return совпадает ли вид
     */
    public boolean is(RowKind expected) {
        return kind == expected;
    }

    /**
     * Фактическая дата строки: из прогноза, если он рассчитан (событие могло быть перенесено или сдвинуто с
     * выходного), иначе номинальная дата из идентификатора или плана.
     *
     * @param state состояние приложения
     * @return дата или пусто для неизвестной строки
     */
    public Optional<LocalDate> date(AppState state) {
        Objects.requireNonNull(state, "state");
        if (kind == null) {
            return Optional.empty();
        }
        Forecast forecast = state.document().forecast();
        if (forecast != null) {
            Optional<LocalDate> actual = forecast.findRow(rowId).map(ForecastRow::date);
            if (actual.isPresent()) {
                return actual;
            }
        }
        Plan plan = state.document().plan();
        return switch (kind) {
            case START -> Optional.of(plan.startDate());
            case RULE -> Optional.of(OccurrenceKey.parseRowId(rowId).originalDate());
            case ONE_TIME -> plan.findOneTime(new TxId(rowId)).map(OneTimeTransaction::date);
            case WHAT_IF -> parseDate(rowId.substring(WHAT_IF_PREFIX.length()));
            case PAST_HEADER, MONTH_TOTAL -> Optional.empty();
        };
    }

    private static RowRef resolveRuleEvent(Plan plan, String id) {
        OccurrenceKey key;
        try {
            key = OccurrenceKey.parseRowId(id);
        } catch (IllegalArgumentException | DateTimeException e) {
            // Не событие правила (другая форма id с «@»): такой строке команды правила недоступны.
            return unknown(id);
        }
        if (plan.findRule(key.ruleId()).isEmpty()) {
            return unknown(id);
        }
        Optional<Adjustment> adjustment = plan.findAdjustment(key);
        boolean skipped = adjustment.map(a -> a.action() instanceof Adjustment.Skip).orElse(false);
        return new RowRef(id, RowKind.RULE, skipped, adjustment.isPresent());
    }

    private static RowRef unknown(String id) {
        return new RowRef(id, null, false, false);
    }

    private static Optional<LocalDate> parseDate(String text) {
        try {
            return Optional.of(LocalDate.parse(text));
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }
}
