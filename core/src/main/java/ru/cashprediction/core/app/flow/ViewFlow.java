package ru.cashprediction.core.app.flow;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.app.FocusTarget;
import ru.cashprediction.core.app.RevealMode;
import ru.cashprediction.core.document.PeriodChoice;
import ru.cashprediction.core.document.ViewMode;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.Origin;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.command.CommandId;
import ru.cashprediction.core.ui.form.FieldSpecs;
import ru.cashprediction.core.ui.form.FieldCodec;
import ru.cashprediction.core.ui.form.FieldView;
import ru.cashprediction.core.ui.form.ButtonSpecs;
import ru.cashprediction.core.ui.form.ButtonView;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormLogic;
import ru.cashprediction.core.ui.form.FormOutcome;
import ru.cashprediction.core.ui.form.FormPage;
import ru.cashprediction.core.ui.form.FormRow;
import ru.cashprediction.core.ui.form.FormSession;
import ru.cashprediction.core.ui.form.FormSpec;
import ru.cashprediction.core.ui.form.FormState;
import ru.cashprediction.core.ui.form.FormView;
import ru.cashprediction.core.ui.form.Problem;
import ru.cashprediction.core.ui.forms.simple.TextInputForms;
import ru.cashprediction.core.ui.text.UiFormats;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.view.status.StatusLevel;
import ru.cashprediction.core.ui.view.table.LazyTableModel;

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
    /** Индекс обновляется только при смене прогноза или фильтров, а не при выделении строки. */
    private Navigation navigation;

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
        Objects.requireNonNull(mode, "mode");
        if (context.state().view().mode() == mode) {
            return;
        }
        if (mode == ViewMode.CHART) {
            // JavaFX: Popup → Swing: JWindow → Web: div.quickEdit
            context.singleInstance(WindowType.QUICK_EDIT_POPUP.name()).ifPresent(FormSession::closeRequested);
        }
        context.updateView(view -> view.withMode(mode));
        context.port().focus(mode == ViewMode.TABLE ? FocusTarget.TABLE : FocusTarget.CHART);
    }

    /**
     * {@code view.flag.*}: переключить флажок вида.
     *
     * @param flag команда флажка
     */
    public void toggleFlag(CommandId flag) {
        Objects.requireNonNull(flag, "flag");
        context.updateView(view -> switch (flag) {
            case VIEW_FLAG_SHOW_INCOME -> view.withShowIncome(!view.showIncome());
            case VIEW_FLAG_SHOW_EXPENSE -> view.withShowExpense(!view.showExpense());
            case VIEW_FLAG_SHOW_ONE_TIME -> view.withShowOneTime(!view.showOneTime());
            case VIEW_FLAG_SHOW_SKIPPED -> view.withShowSkipped(!view.showSkipped());
            case VIEW_FLAG_MONTH_TOTALS -> view.withMonthTotals(!view.monthTotals());
            case VIEW_FLAG_CHART_MARKERS -> view.withChartMarkers(!view.chartMarkers());
            case VIEW_FLAG_CHART_BARS -> view.withChartBars(!view.chartBars());
            case VIEW_FLAG_SUMMARY_PANEL -> view.withSummaryPanel(!view.summaryPanel());
            default -> throw new IllegalArgumentException("Not a view flag: " + flag);
        });
    }

    /**
     * {@code view.period.*}: период показа (план не меняется), затем прокрутка к сегодня.
     *
     * @param period период
     */
    public void setPeriod(PeriodChoice period) {
        Objects.requireNonNull(period, "period");
        if (context.state().view().period() == period) {
            return;
        }
        context.updateView(view -> view.withPeriod(period));
        scrollToToday();
    }

    /**
     * {@code view.horizonSlider} отпущен: горизонт {@code value} месяцев, {@code undo.horizon}; при горизонте &gt; 120
     * план не меняется, пока ползунок не сдвинут.
     *
     * @param value месяцев 1..120
     */
    public void horizonSliderCommit(int value) {
        if (value < 1 || value > 120) {
            throw new IllegalArgumentException("Slider months must be in 1..120");
        }
        Plan plan = context.document().plan();
        // Ползунок зажат на 120 при длинном горизонте; одно отпускание без сдвига не обрезает план.
        if (value == Math.min(120, plan.horizon().approximateMonths(plan.startDate()))) {
            return;
        }
        changeMonths(value);
    }

    /** {@code view.horizonMonths}: TEXT_INPUT customMonths §6.23. */
    public void customMonths() {
        // JavaFX: TextInputDialog → Swing: JDialog → Web: dialog
        context.openForm(FormRequest.fresh(new CustomMonths(), WindowType.TEXT_INPUT, true,
                Map.of("purpose", TextInputForms.PURPOSE_CUSTOM_MONTHS)), null, result -> {
                    if (result instanceof Integer months) {
                        changeMonths(months);
                    }
                });
    }

    /**
     * Текст фильтра (после задержки 300 мс клиента): совпадение без учёта регистра, «ё» = «е», START проходит всегда.
     *
     * @param text текст поля
     */
    public void filterText(String text) {
        String value = Objects.requireNonNullElse(text, "");
        if (!context.state().view().filterText().equals(value)) {
            context.updateView(view -> view.withFilterText(value));
        }
    }

    /** {@code filter.clear}: очистить фильтр. */
    public void clearFilter() {
        filterText("");
    }

    /** {@code view.focusFilter}: {@code port.focus(FILTER)}. */
    public void focusFilter() {
        context.port().focus(FocusTarget.FILTER);
    }

    /** {@code filter.focusTable}: применить текст фильтра немедленно и {@code port.focus(TABLE)}. */
    public void focusTable() {
        // Последний текст клиент передаёт через filterText до команды, в том числе до своего таймера.
        context.port().focus(FocusTarget.TABLE);
    }

    /** {@code past.toggle}: развернуть или свернуть группу «Прошедшие события». */
    public void togglePast() {
        context.setPastExpanded(!context.state().pastExpanded());
        context.refresh();
    }

    /**
     * {@code card.showInTable}, {@code chart.showInTable}, двойной щелчок по карточке или графику.
     *
     * @param date дата
     */
    public void showInTable(LocalDate date) {
        if (date == null) {
            return;
        }
        setMode(ViewMode.TABLE);
        AppState state = context.state();
        ForecastRow row = navigation(state).firstEvent(date);
        if (row == null) {
            context.status(StatusLevel.WARN, "status.msg.noRowAfter", UiFormats.date(date));
            return;
        }
        if (row.date().isBefore(state.today())) {
            context.setPastExpanded(true);
        }
        context.setSelection(row.rowId());
        context.refresh();
        context.port().revealRow(row.rowId(), RevealMode.SELECT_AND_SCROLL);
    }

    /**
     * Выделение строки пользователем; скрытая в свёрнутой группе строка раскрывает группу.
     *
     * @param rowId id строки или пустая строка
     */
    public void selectRow(String rowId) {
        String id = Objects.requireNonNullElse(rowId, "");
        AppState state = context.state();
        if (!id.isEmpty()) {
            Navigation index = navigation(state);
            ForecastRow row = index.byId.get(id);
            if (row != null) {
                if (row.origin() != Origin.START && row.date().isBefore(state.today())) {
                    context.setPastExpanded(true);
                }
            } else if (!index.serviceVisible(id, state)) {
                return;
            }
        }
        context.setSelection(id);
        context.refresh();
    }

    /** Прокрутка к первой строке с датой ≥ сегодня (открытие плана, смена периода). */
    public void scrollToToday() {
        AppState state = context.state();
        Navigation index = navigation(state);
        ForecastRow row = index.firstEvent(state.today());
        ForecastRow start = index.byId.get(ForecastRow.START_ROW_ID);
        if (start != null && !start.date().isBefore(state.today())) {
            row = start;
        }
        if (row != null) {
            context.port().revealRow(row.rowId(), RevealMode.SCROLL_TO_TOP);
        }
    }

    /**
     * Применяет горизонт через единственную точку правки с описанием отмены.
     * Доступен внутри пакета для результата восстановленной формы в {@code CoreWindowFactory}.
     *
     * @param months число месяцев от 1 до 600
     * @throws IllegalArgumentException если число месяцев вне диапазона
     */
    void changeMonths(int months) {
        Horizon horizon = new Horizon.Months(months);
        if (!horizon.equals(context.document().plan().horizon())) {
            context.edits().edit(UiText.get("undo.horizon",
                    UiFormats.horizonLabel(horizon, context.document().plan().startDate())), "",
                    plan -> plan.withHorizon(horizon));
        }
    }

    /** Возвращает кэш индексов; линейное построение нужно только после изменения исходных данных. */
    private Navigation navigation(AppState state) {
        Forecast forecast = state.document().forecast();
        FilterKey key = new FilterKey(state.view().period(), state.view().showIncome(), state.view().showExpense(),
                state.view().showOneTime(), state.view().showSkipped(), state.view().filterText());
        if (navigation == null || navigation.forecast != forecast || !navigation.key.equals(key)) {
            navigation = new Navigation(state, key);
        }
        return navigation;
    }

    /** Только признаки, влияющие на набор событий; режим и служебные строки не сбрасывают индекс. */
    private record FilterKey(PeriodChoice period, boolean income, boolean expense, boolean oneTime,
                             boolean skipped, String text) { }

    /** Индекс доступных событий для поиска даты и id без создания текстов таблицы. */
    private static final class Navigation {
        private final Forecast forecast;
        private final FilterKey key;
        private final List<ForecastRow> events = new ArrayList<>();
        private final Map<String, ForecastRow> byId = new HashMap<>();
        private final Map<String, LocalDate> lastInMonth = new HashMap<>();
        private LocalDate firstEventDate;

        private Navigation(AppState state, FilterKey key) {
            this.forecast = state.document().forecast();
            this.key = key;
            Plan plan = state.document().plan();
            if (forecast == null || plan.rules().isEmpty() && plan.oneTimes().isEmpty()) {
                return;
            }
            LocalDate end = state.view().periodEnd(plan, forecast.anchor());
            for (ForecastRow row : forecast.rows()) {
                if (row.date().isAfter(end)) {
                    break;
                }
                if (!state.view().accepts(row)) {
                    continue;
                }
                byId.put(row.rowId(), row);
                if (row.origin() != Origin.START) {
                    events.add(row);
                    lastInMonth.put(LazyTableModel.totalRowId(YearMonth.from(row.date())), row.date());
                }
            }
            // При отсутствии событий таблица показывает пустое состояние, даже START не выделяется.
            if (events.isEmpty()) {
                byId.clear();
            } else {
                firstEventDate = events.getFirst().date();
            }
        }

        /** Нижняя граница по дате: одинаковые даты возвращают именно первое событие. */
        private ForecastRow firstEvent(LocalDate date) {
            int low = 0;
            int high = events.size();
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (events.get(middle).date().isBefore(date)) {
                    low = middle + 1;
                } else {
                    high = middle;
                }
            }
            return low < events.size() ? events.get(low) : null;
        }

        /** Служебные строки выделяются только когда присутствуют в текущей раскладке таблицы. */
        private boolean serviceVisible(String id, AppState state) {
            if (LazyTableModel.PAST_HEADER_ROW_ID.equals(id)) {
                return firstEventDate != null && firstEventDate.isBefore(state.today());
            }
            LocalDate last = lastInMonth.get(id);
            return state.view().monthTotals() && last != null
                    && (state.pastExpanded() || !last.isBefore(state.today()));
        }
    }

    /**
     * Дополняет общую раскладку текущим горизонтом и проверкой диапазона без переполнения.
     * Доступна внутри пакета для выбора той же логики в {@code FormCatalog} при восстановлении.
     */
    static final class CustomMonths implements FormLogic {
        private final FormLogic delegate = TextInputForms.customMonths();

        /** {@inheritDoc} */
        @Override
        public FormSpec spec(FormContext context) {
            FormSpec spec = delegate.spec(context);
            return new FormSpec(spec.formId(), spec.windowType(), spec.purpose(), spec.presentation(),
                    spec.windowTitle(), spec.glyph(), spec.width(), spec.modal(), spec.resizable(), spec.restorable(),
                    List.of(new FormPage("main", List.of(new FormRow.Field(FieldSpecs.focused(
                            FieldSpecs.text("value", UiText.get("s2.viewHelp.customMonths.value"), "")))))),
                    spec.buttons(), spec.defaultButtonId());
        }

        /** {@inheritDoc} */
        @Override
        public Map<String, String> defaults(FormContext context) {
            Plan plan = context.app().document().plan();
            return Map.of("value", Long.toString(plan.horizon().approximateMonths(plan.startDate())));
        }

        /** {@inheritDoc} */
        @Override
        public FormView evaluate(FormState state, FormContext context) {
            Plan plan = context.app().document().plan();
            String header = UiText.get("s2.viewHelp.customMonths.header",
                    UiFormats.horizonLabel(plan.horizon(), plan.startDate()), UiFormats.date(plan.endDate()));
            boolean valid = months(state.value("value")) != 0;
            Problem problem = valid ? Problem.NONE : Problem.error(UiText.get("s2.viewHelp.customMonths.error"));
            return new FormView(0, state.page(), header, Map.of("value", FieldView.of(state.value("value"))),
                    problem, Map.of("apply", valid ? ButtonView.ENABLED : ButtonView.DISABLED),
                    List.of(), List.of(), "", false);
        }

        /** {@inheritDoc} */
        @Override
        public FormOutcome onButton(String id, FormState state, FormContext context) {
            if (ButtonSpecs.CANCEL.equals(id)) {
                return new FormOutcome.Close(null);
            }
            int value = months(state.value("value"));
            return value == 0 ? new FormOutcome.Stay(Problem.error(UiText.get("s2.viewHelp.customMonths.error")))
                    : new FormOutcome.Close(value);
        }

        /** Ноль означает неверный ввод; слишком длинное целое не выбрасывает исключение в форму. */
        private static int months(String raw) {
            try {
                long value = FieldCodec.parseLong(raw).orElse(0);
                return value >= 1 && value <= Horizon.MAX_MONTHS ? (int) value : 0;
            } catch (NumberFormatException overflow) {
                return 0;
            }
        }
    }
}
