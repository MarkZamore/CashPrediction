package ru.cashprediction.swing.menu;

import java.awt.event.ItemEvent;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.ButtonGroup;
import javax.swing.ButtonModel;
import javax.swing.JToggleButton;
import javax.swing.SpinnerNumberModel;
import ru.cashprediction.core.document.PeriodChoice;
import ru.cashprediction.core.document.RecoveryStoreKind;
import ru.cashprediction.core.document.ViewMode;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.model.Money;

/**
 * Общие модели переключателей главного окна: вид (таблица/график), период, фильтры, «что-если», автосохранение и
 * хранилище восстановления по умолчанию.
 *
 * <p><b>Зачем общие модели.</b> Один и тот же выбор показан в нескольких местах: период — в меню «Вид» и в кнопке
 * «Период ▾» панели инструментов, вид — в меню и в кнопках «Таблица/График», маркеры графика — в меню и в
 * контекстном меню графика. В JavaFX для этого служит общая {@code ToggleGroup}; в Swing пункты и кнопки получают
 * один и тот же {@link ButtonModel} ({@code setModel}), поэтому щелчок в любом месте сразу отмечает все копии.</p>
 *
 * <p>Слушатели висят на самих моделях (а не на кнопках): при общей модели слушатель кнопки сработал бы по разу на
 * каждую копию. Программная синхронизация с {@link ViewState} ({@link #sync}) слушателей не вызывает.</p>
 *
 * <p>Группы переключателей ({@link ButtonGroup}) заполняет строитель меню: в группу добавляется ровно один пункт на
 * модель, остальные копии только разделяют модель. Класс используется только в потоке EDT.</p>
 */
// JavaFX: RadioMenuItem + ToggleGroup → Swing: JRadioButtonMenuItem + ButtonGroup (общий ButtonModel) → Web: role="menuitemradio"
public final class ViewModels {

    /** Ключ фильтра «Доходы» (одинаков в снимке сессии всех клиентов). */
    public static final String SHOW_INCOME = "showIncome";
    /** Ключ фильтра «Расходы». */
    public static final String SHOW_EXPENSE = "showExpense";
    /** Ключ фильтра «Разовые». */
    public static final String SHOW_ONE_TIME = "showOneTime";
    /** Ключ фильтра «Пропущенные». */
    public static final String SHOW_SKIPPED = "showSkipped";
    /** Ключ флажка «Итоги по месяцам». */
    public static final String MONTH_TOTALS = "monthTotals";
    /** Ключ флажка «Маркеры событий на графике». */
    public static final String CHART_MARKERS = "chartMarkers";
    /** Ключ флажка «Столбцы по месяцам на графике». */
    public static final String CHART_BARS = "chartBars";
    /** Ключ флажка «Панель сводки». */
    public static final String SUMMARY_PANEL = "summaryPanel";

    /** Все ключи флажков вида в порядке меню. */
    public static final List<String> FILTER_KEYS = List.of(SHOW_INCOME, SHOW_EXPENSE, SHOW_ONE_TIME, SHOW_SKIPPED,
            MONTH_TOTALS, CHART_MARKERS, CHART_BARS, SUMMARY_PANEL);

    private final Map<ViewMode, ButtonModel> modeModels = new EnumMap<>(ViewMode.class);
    private final Map<PeriodChoice, ButtonModel> periodModels = new EnumMap<>(PeriodChoice.class);
    private final Map<String, ButtonModel> filterModels = new LinkedHashMap<>();
    private final Map<RecoveryStoreKind, ButtonModel> storeModels = new EnumMap<>(RecoveryStoreKind.class);
    private final ButtonModel incomeWhatIf = new JToggleButton.ToggleButtonModel();
    private final ButtonModel expenseWhatIf = new JToggleButton.ToggleButtonModel();
    private final ButtonModel autosave = new JToggleButton.ToggleButtonModel();
    /** Сумма «Откладывать доп.» в рублях: общая модель двух спиннеров (меню «Инструменты» и кнопка «Что-если»). */
    private final SpinnerNumberModel extraSaving = new SpinnerNumberModel(0, 0, 10_000_000, 1_000);

    private final ButtonGroup modeGroup = new ButtonGroup();
    private final ButtonGroup periodGroup = new ButtonGroup();
    private final ButtonGroup storeGroup = new ButtonGroup();

    /** Идёт программная синхронизация: изменения моделей не являются выбором пользователя. */
    private boolean syncing;

    /**
     * Создаёт модели и связывает их с обработчиками выбора пользователя.
     *
     * @param onMode          выбран вид
     * @param onPeriod        выбран период
     * @param onFilter        переключён флажок вида (ключ, новое значение)
     * @param onIncomeWhatIf  «Доходы −10 %»
     * @param onExpenseWhatIf «Расходы +10 %»
     * @param onExtraSaving   «Откладывать доп.» (сумма в месяц)
     * @param onAutosave      «Автосохранение»
     * @param onStore         хранилище восстановления по умолчанию
     */
    public ViewModels(Consumer<ViewMode> onMode, Consumer<PeriodChoice> onPeriod, BiConsumer<String, Boolean> onFilter,
                      Consumer<Boolean> onIncomeWhatIf, Consumer<Boolean> onExpenseWhatIf, Consumer<Money> onExtraSaving,
                      Consumer<Boolean> onAutosave, Consumer<RecoveryStoreKind> onStore) {
        for (ViewMode mode : ViewMode.values()) {
            ButtonModel model = new JToggleButton.ToggleButtonModel();
            // Радио-модели реагируют только на выбор (SELECTED): снятие отметки с прежнего пункта — не команда.
            model.addItemListener(e -> {
                if (!syncing && e.getStateChange() == ItemEvent.SELECTED) {
                    onMode.accept(mode);
                }
            });
            modeModels.put(mode, model);
        }
        for (PeriodChoice period : PeriodChoice.values()) {
            ButtonModel model = new JToggleButton.ToggleButtonModel();
            model.addItemListener(e -> {
                if (!syncing && e.getStateChange() == ItemEvent.SELECTED) {
                    onPeriod.accept(period);
                }
            });
            periodModels.put(period, model);
        }
        for (String key : FILTER_KEYS) {
            ButtonModel model = new JToggleButton.ToggleButtonModel();
            model.addItemListener(e -> {
                if (!syncing) {
                    onFilter.accept(key, e.getStateChange() == ItemEvent.SELECTED);
                }
            });
            filterModels.put(key, model);
        }
        for (RecoveryStoreKind kind : RecoveryStoreKind.values()) {
            ButtonModel model = new JToggleButton.ToggleButtonModel();
            model.addItemListener(e -> {
                if (!syncing && e.getStateChange() == ItemEvent.SELECTED) {
                    onStore.accept(kind);
                }
            });
            storeModels.put(kind, model);
        }
        incomeWhatIf.addItemListener(e -> {
            if (!syncing) {
                onIncomeWhatIf.accept(e.getStateChange() == ItemEvent.SELECTED);
            }
        });
        expenseWhatIf.addItemListener(e -> {
            if (!syncing) {
                onExpenseWhatIf.accept(e.getStateChange() == ItemEvent.SELECTED);
            }
        });
        autosave.addItemListener(e -> {
            if (!syncing) {
                onAutosave.accept(e.getStateChange() == ItemEvent.SELECTED);
            }
        });
        extraSaving.addChangeListener(e -> {
            if (!syncing) {
                onExtraSaving.accept(Money.ofMajor(extraSaving.getNumber().longValue()));
            }
        });
    }

    /**
     * Модель пункта вида.
     *
     * @param mode вид
     * @return общая модель
     */
    public ButtonModel mode(ViewMode mode) {
        return modeModels.get(Objects.requireNonNull(mode, "mode"));
    }

    /**
     * Модель пункта периода.
     *
     * @param period период
     * @return общая модель
     */
    public ButtonModel period(PeriodChoice period) {
        return periodModels.get(Objects.requireNonNull(period, "period"));
    }

    /**
     * Модель флажка вида.
     *
     * @param key один из {@link #FILTER_KEYS}
     * @return общая модель
     * @throws IllegalArgumentException если ключ неизвестен
     */
    public ButtonModel filter(String key) {
        ButtonModel model = filterModels.get(key);
        if (model == null) {
            throw new IllegalArgumentException("Неизвестный флажок вида: " + key);
        }
        return model;
    }

    /**
     * Модель пункта хранилища восстановления.
     *
     * @param kind хранилище
     * @return общая модель
     */
    public ButtonModel store(RecoveryStoreKind kind) {
        return storeModels.get(Objects.requireNonNull(kind, "kind"));
    }

    /** @return модель «Доходы −10 %» */
    public ButtonModel incomeWhatIf() {
        return incomeWhatIf;
    }

    /** @return модель «Расходы +10 %» */
    public ButtonModel expenseWhatIf() {
        return expenseWhatIf;
    }

    /** @return модель «Автосохранение» */
    public ButtonModel autosave() {
        return autosave;
    }

    /** @return общая модель спиннеров «Откладывать доп.» (рубли в месяц) */
    public SpinnerNumberModel extraSaving() {
        return extraSaving;
    }

    /** @return группа пунктов вида (в неё добавляется по одному пункту на модель) */
    public ButtonGroup modeGroup() {
        return modeGroup;
    }

    /** @return группа пунктов периода */
    public ButtonGroup periodGroup() {
        return periodGroup;
    }

    /** @return группа пунктов хранилища */
    public ButtonGroup storeGroup() {
        return storeGroup;
    }

    /**
     * Приводит отметки к состоянию вида документа, не вызывая обработчиков.
     *
     * @param view состояние вида
     */
    public void sync(ViewState view) {
        syncing = true;
        try {
            modeModels.get(view.mode()).setSelected(true);
            periodModels.get(view.period()).setSelected(true);
            filterModels.get(SHOW_INCOME).setSelected(view.showIncome());
            filterModels.get(SHOW_EXPENSE).setSelected(view.showExpense());
            filterModels.get(SHOW_ONE_TIME).setSelected(view.showOneTime());
            filterModels.get(SHOW_SKIPPED).setSelected(view.showSkipped());
            filterModels.get(MONTH_TOTALS).setSelected(view.monthTotals());
            filterModels.get(CHART_MARKERS).setSelected(view.chartMarkers());
            filterModels.get(CHART_BARS).setSelected(view.chartBars());
            filterModels.get(SUMMARY_PANEL).setSelected(view.summaryPanel());
            // Множитель, отличный от единицы, означает включённый флажок (как в ToolActions.isIncomeWhatIf).
            incomeWhatIf.setSelected(view.whatIf().incomeFactor().compareTo(BigDecimal.ONE) != 0);
            expenseWhatIf.setSelected(view.whatIf().expenseFactor().compareTo(BigDecimal.ONE) != 0);
            long saving = view.whatIf().extraMonthlySaving().minor() / 100;
            if (extraSaving.getNumber().longValue() != saving) {
                extraSaving.setValue((int) Math.min(saving, 10_000_000));
            }
        } finally {
            syncing = false;
        }
    }

    /**
     * Приводит отметки настроек приложения (автосохранение, хранилище) к значениям, не вызывая обработчиков.
     *
     * @param autosaveOn включено ли автосохранение
     * @param store      хранилище по умолчанию
     */
    public void syncSettings(boolean autosaveOn, RecoveryStoreKind store) {
        syncing = true;
        try {
            autosave.setSelected(autosaveOn);
            storeModels.get(store).setSelected(true);
        } finally {
            syncing = false;
        }
    }

    /**
     * Значение флажка вида по ключу снимка сессии.
     *
     * @param view состояние вида
     * @param key  ключ
     * @return значение
     */
    public static boolean filterValue(ViewState view, String key) {
        return switch (key) {
            case SHOW_INCOME -> view.showIncome();
            case SHOW_EXPENSE -> view.showExpense();
            case SHOW_ONE_TIME -> view.showOneTime();
            case SHOW_SKIPPED -> view.showSkipped();
            case MONTH_TOTALS -> view.monthTotals();
            case CHART_MARKERS -> view.chartMarkers();
            case CHART_BARS -> view.chartBars();
            case SUMMARY_PANEL -> view.summaryPanel();
            default -> throw new IllegalArgumentException("Неизвестный флажок вида: " + key);
        };
    }

    /**
     * Меняет флажок вида по ключу снимка сессии.
     *
     * @param view  состояние вида
     * @param key   ключ
     * @param value новое значение
     * @return новое состояние вида
     * @throws IllegalArgumentException если ключ неизвестен
     */
    public static ViewState withFilter(ViewState view, String key, boolean value) {
        return switch (key) {
            case SHOW_INCOME -> view.withShowIncome(value);
            case SHOW_EXPENSE -> view.withShowExpense(value);
            case SHOW_ONE_TIME -> view.withShowOneTime(value);
            case SHOW_SKIPPED -> view.withShowSkipped(value);
            case MONTH_TOTALS -> view.withMonthTotals(value);
            case CHART_MARKERS -> view.withChartMarkers(value);
            case CHART_BARS -> view.withChartBars(value);
            case SUMMARY_PANEL -> view.withSummaryPanel(value);
            default -> throw new IllegalArgumentException("Неизвестный флажок вида: " + key);
        };
    }
}
