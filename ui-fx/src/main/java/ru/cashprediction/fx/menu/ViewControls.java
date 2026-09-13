package ru.cashprediction.fx.menu;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import ru.cashprediction.core.document.PeriodChoice;
import ru.cashprediction.core.document.ViewMode;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.fx.ShellContext;
import ru.cashprediction.fx.dialog.OpenRequest;
import ru.cashprediction.fx.view.ViewFlags;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Элементы управления видом, общие для меню и панели инструментов: режим таблица/график, период, флаги
 * «Вид», режим «что-если», слайдер горизонта плана и поле фильтра.
 *
 * <p><b>Синхронизация в обе стороны без петель.</b> Пользователь меняет элемент → слушатель вызывает
 * {@code ShellContext.updateView} (или команду горизонта) → документ рассылает событие → оболочка вызывает
 * {@link #sync(ViewState, Plan)}, которая выставляет все элементы по новому виду. Во время {@code sync} флаг
 * {@code syncing} поднят, и слушатели ничего не отправляют обратно — иначе программная установка значения
 * снова меняла бы вид.</p>
 *
 * <p>Только FX Application Thread.</p>
 */
public final class ViewControls {

    /** Коэффициент пункта «Доходы −10 %». */
    static final BigDecimal INCOME_DOWN = new BigDecimal("0.90");
    /** Коэффициент пункта «Расходы +10 %». */
    static final BigDecimal EXPENSE_UP = new BigDecimal("1.10");
    /** Наибольший горизонт на слайдере меню «Вид» (больше — через «Параметры плана»). */
    public static final int SLIDER_MAX_MONTHS = 120;

    private final ShellContext shell;
    private boolean syncing;

    // JavaFX: RadioMenuItem + ToggleGroup → Swing: JRadioButtonMenuItem + ButtonGroup → Web: role="menuitemradio"
    private final ToggleGroup modeGroup = new ToggleGroup();
    private final RadioMenuItem tableItem = new RadioMenuItem("Таблица");
    private final RadioMenuItem chartItem = new RadioMenuItem("График");
    private final ToggleGroup modeButtons = new ToggleGroup();
    private final ToggleButton tableButton = new ToggleButton("Таблица");
    private final ToggleButton chartButton = new ToggleButton("График");
    // JavaFX: RadioMenuItem + ToggleGroup → Swing: JRadioButtonMenuItem + ButtonGroup → Web: role="menuitemradio"
    private final ToggleGroup periodGroup = new ToggleGroup();
    private final Map<PeriodChoice, RadioMenuItem> periodItems = new EnumMap<>(PeriodChoice.class);
    private final Map<String, CheckMenuItem> flagItems = new LinkedHashMap<>();
    // JavaFX: CheckMenuItem → Swing: JCheckBoxMenuItem → Web: role="menuitemcheckbox"
    private final CheckMenuItem incomeDown = new CheckMenuItem("Доходы −10 %");
    // JavaFX: CheckMenuItem → Swing: JCheckBoxMenuItem → Web: role="menuitemcheckbox"
    private final CheckMenuItem expenseUp = new CheckMenuItem("Расходы +10 %");
    private final Spinner<Integer> savingSpinner = new Spinner<>(0, 10_000_000, 0, 1000);
    private final Label savingLabel = new Label("Откладывать доп., ₽/мес");
    private final CustomMenuItem savingItem;
    // JavaFX: MenuItem → Swing: JMenuItem → Web: <li role="menuitem">
    private final MenuItem applyWhatIf = new MenuItem("Применить к плану…");
    // JavaFX: MenuItem → Swing: JMenuItem → Web: <li role="menuitem">
    private final MenuItem resetWhatIf = new MenuItem("Сбросить");
    private final Slider horizonSlider = new Slider(1, SLIDER_MAX_MONTHS, 12);
    private final Label horizonLabel = new Label("Горизонт плана: 12 мес");
    private final CustomMenuItem horizonItem;
    private final TextField filterField = new TextField();
    private final SharedMenuItems periodShared;
    private final SharedMenuItems whatIfShared;

    /**
     * Создаёт элементы управления и подключает их слушатели.
     *
     * @param shell оболочка приложения
     */
    public ViewControls(ShellContext shell) {
        this.shell = Objects.requireNonNull(shell, "shell");

        // ---- режим
        tableItem.setUserData(ViewMode.TABLE);
        chartItem.setUserData(ViewMode.CHART);
        tableItem.setToggleGroup(modeGroup);
        chartItem.setToggleGroup(modeGroup);
        tableButton.setUserData(ViewMode.TABLE);
        chartButton.setUserData(ViewMode.CHART);
        tableButton.setToggleGroup(modeButtons);
        chartButton.setToggleGroup(modeButtons);
        // JavaFX: Tooltip → Swing: setToolTipText → Web: title
        tableButton.setTooltip(new Tooltip("Таблица событий с балансом (Ctrl+1)"));
        // JavaFX: Tooltip → Swing: setToolTipText → Web: title
        chartButton.setTooltip(new Tooltip("График баланса во времени (Ctrl+2)"));
        modeGroup.selectedToggleProperty().addListener((o, a, b) -> onModeToggle(b, modeGroup));
        modeButtons.selectedToggleProperty().addListener((o, a, b) -> onModeToggle(b, modeButtons));

        // ---- период
        for (PeriodChoice choice : PeriodChoice.values()) {
            // JavaFX: RadioMenuItem → Swing: JRadioButtonMenuItem → Web: role="menuitemradio"
            RadioMenuItem item = new RadioMenuItem(choice == PeriodChoice.ALL ? "Весь горизонт" : "Период: " + choice.label());
            item.setUserData(choice);
            item.setToggleGroup(periodGroup);
            periodItems.put(choice, item);
        }
        periodGroup.selectedToggleProperty().addListener((o, a, b) -> {
            if (syncing) {
                return;
            }
            if (b == null) {
                resync();
                return;
            }
            PeriodChoice choice = (PeriodChoice) b.getUserData();
            shell.updateView(v -> v.withPeriod(choice));
        });
        periodShared = new SharedMenuItems(new ArrayList<>(periodItems.values()));

        // ---- флаги «Вид»
        flag(ViewFlags.SHOW_INCOME, "Доходы");
        flag(ViewFlags.SHOW_EXPENSE, "Расходы");
        flag(ViewFlags.SHOW_ONE_TIME, "Разовые операции");
        flag(ViewFlags.SHOW_SKIPPED, "Пропущенные события");
        flag(ViewFlags.MONTH_TOTALS, "Итоги по месяцам");
        flag(ViewFlags.CHART_MARKERS, "Маркеры событий на графике");
        flag(ViewFlags.CHART_BARS, "Столбцы итогов месяцев на графике");
        flag(ViewFlags.SUMMARY_PANEL, "Панель сводки");

        // ---- что-если
        incomeDown.selectedProperty().addListener((o, a, b) -> whatIf(w -> w.withIncomeFactor(b ? INCOME_DOWN : BigDecimal.ONE)));
        expenseUp.selectedProperty().addListener((o, a, b) -> whatIf(w -> w.withExpenseFactor(b ? EXPENSE_UP : BigDecimal.ONE)));
        savingSpinner.setEditable(true);
        savingSpinner.setPrefWidth(130);
        savingSpinner.valueProperty().addListener((o, a, b) ->
                whatIf(w -> w.withExtraMonthlySaving(Money.ofMajor(b == null ? 0 : b))));
        HBox savingBox = new HBox(8, savingLabel, savingSpinner);
        savingBox.setAlignment(Pos.CENTER_LEFT);
        savingBox.setPadding(new Insets(2, 0, 2, 0));
        // JavaFX: CustomMenuItem → Swing: JPanel(JLabel+JSpinner) через JMenu.add(Component) → Web: <li class="custom"><input type="number">
        savingItem = new CustomMenuItem(savingBox, false);
        applyWhatIf.setOnAction(e -> shell.actions().applyWhatIf(OpenRequest.fromMain()));
        resetWhatIf.setOnAction(e -> shell.actions().resetWhatIf());
        // JavaFX: SeparatorMenuItem → Swing: JMenu.addSeparator() → Web: <li role="separator"><hr>
        whatIfShared = new SharedMenuItems(List.of(incomeDown, expenseUp, savingItem, new SeparatorMenuItem(),
                applyWhatIf, resetWhatIf));

        // ---- горизонт плана
        horizonSlider.setMajorTickUnit(12);
        horizonSlider.setMinorTickCount(0);
        horizonSlider.setBlockIncrement(1);
        horizonSlider.setShowTickMarks(true);
        horizonSlider.setPrefWidth(240);
        horizonSlider.valueProperty().addListener((o, a, b) -> {
            horizonLabel.setText("Горизонт плана: " + Math.round(b.doubleValue()) + " мес");
            // Пока ползунок тянут, план не меняется: иначе каждый пиксель стал бы отдельным шагом «Отменить».
            if (!horizonSlider.isValueChanging()) {
                applyHorizon();
            }
        });
        horizonSlider.valueChangingProperty().addListener((o, was, is) -> {
            if (!is) {
                applyHorizon();
            }
        });
        VBox horizonBox = new VBox(4, horizonLabel, horizonSlider);
        horizonBox.setPadding(new Insets(2, 0, 2, 0));
        // JavaFX: CustomMenuItem → Swing: SwingSliderMenuItem (JPanel с JLabel+JSlider через JMenu.add(Component)) → Web: <li class="custom"><input type="range">
        horizonItem = new CustomMenuItem(horizonBox, false);

        // ---- фильтр
        // Короткая подсказка в поле: длинная обрезалась; подробности — во всплывающей подсказке.
        filterField.setPromptText("Фильтр… (Ctrl+F)");
        filterField.setPrefColumnCount(22);
        // JavaFX: Tooltip → Swing: setToolTipText → Web: title
        filterField.setTooltip(new Tooltip("Показывать только строки, где название, категория или заметка содержат текст"));
        filterField.textProperty().addListener((o, a, b) -> {
            if (!syncing) {
                shell.updateView(v -> v.withFilterText(b == null ? "" : b));
            }
        });
    }

    private void flag(String key, String title) {
        // JavaFX: CheckMenuItem → Swing: JCheckBoxMenuItem → Web: role="menuitemcheckbox"
        CheckMenuItem item = new CheckMenuItem(title);
        item.selectedProperty().addListener((o, a, b) -> {
            if (!syncing) {
                shell.updateView(v -> ViewFlags.with(v, key, b));
            }
        });
        flagItems.put(key, item);
    }

    private void onModeToggle(Toggle selected, ToggleGroup group) {
        if (syncing) {
            return;
        }
        if (selected == null) {
            // Щелчок по уже нажатой кнопке снимает выбор в ToggleGroup — вернём отметку текущему режиму.
            resync();
            return;
        }
        ViewMode mode = (ViewMode) selected.getUserData();
        shell.updateView(v -> v.withMode(mode));
    }

    private void whatIf(java.util.function.UnaryOperator<WhatIf> change) {
        if (!syncing) {
            shell.updateView(v -> v.withWhatIf(change.apply(v.whatIf())));
        }
    }

    private void applyHorizon() {
        if (syncing) {
            return;
        }
        int months = (int) Math.round(horizonSlider.getValue());
        if (months != horizonMonths(shell.document().plan())) {
            shell.actions().setHorizonMonths(months);
        }
    }

    private void resync() {
        sync(shell.document().viewState(), shell.document().plan());
    }

    /**
     * Выставляет все элементы по виду и плану, не отправляя изменений обратно.
     *
     * @param view вид
     * @param plan план (горизонт для слайдера, валюта для подписи экономии)
     */
    public void sync(ViewState view, Plan plan) {
        syncing = true;
        try {
            modeGroup.selectToggle(view.mode() == ViewMode.CHART ? chartItem : tableItem);
            modeButtons.selectToggle(view.mode() == ViewMode.CHART ? chartButton : tableButton);
            periodGroup.selectToggle(periodItems.get(view.period()));
            flagItems.forEach((key, item) -> item.setSelected(ViewFlags.get(view, key)));
            WhatIf whatIf = view.whatIf();
            incomeDown.setSelected(whatIf.incomeFactor().compareTo(BigDecimal.ONE) != 0);
            expenseUp.setSelected(whatIf.expenseFactor().compareTo(BigDecimal.ONE) != 0);
            int saving = (int) Math.min(Integer.MAX_VALUE, whatIf.extraMonthlySaving().minor() / 100);
            if (!Objects.equals(savingSpinner.getValue(), saving)) {
                savingSpinner.getValueFactory().setValue(saving);
            }
            savingLabel.setText("Откладывать доп., " + plan.currency() + "/мес");
            resetWhatIf.setDisable(whatIf.isNone());
            applyWhatIf.setDisable(whatIf.isNone());
            int months = horizonMonths(plan);
            horizonSlider.setValue(Math.clamp(months, 1, SLIDER_MAX_MONTHS));
            horizonLabel.setText("Горизонт плана: " + months + " мес");
            String filter = view.filterText();
            if (!filter.equals(filterField.getText())) {
                filterField.setText(filter);
            }
        } finally {
            syncing = false;
        }
    }

    /**
     * Горизонт плана в месяцах (для «до даты» — число полных месяцев, не меньше одного).
     *
     * @param plan план
     * @return месяцы
     */
    public static int horizonMonths(Plan plan) {
        return switch (plan.horizon()) {
            case Horizon.Months m -> m.count();
            case Horizon.Years y -> y.count() * 12;
            case Horizon.Until u -> (int) Math.max(1, ChronoUnit.MONTHS.between(plan.startDate(), u.end().plusDays(1)));
        };
    }

    // ------------------------------------------------------------------ доступ для меню и панели инструментов

    /** @return пункт «Таблица» (Ctrl+1) */
    public RadioMenuItem tableItem() {
        return tableItem;
    }

    /** @return пункт «График» (Ctrl+2) */
    public RadioMenuItem chartItem() {
        return chartItem;
    }

    /** @return кнопка «Таблица» панели инструментов */
    public ToggleButton tableButton() {
        return tableButton;
    }

    /** @return кнопка «График» панели инструментов */
    public ToggleButton chartButton() {
        return chartButton;
    }

    /** @return пункты периода по значениям */
    public Map<PeriodChoice, RadioMenuItem> periodItems() {
        return Collections.unmodifiableMap(periodItems);
    }

    /** @return общие пункты периода (меню «Вид» и кнопка «Период») */
    public SharedMenuItems periodShared() {
        return periodShared;
    }

    /** @return общие пункты «что-если» (подменю «Инструменты → Что-если» и кнопка «Что-если») */
    public SharedMenuItems whatIfShared() {
        return whatIfShared;
    }

    /** @return флажки «Вид» по ключам {@link ViewFlags} */
    public Map<String, CheckMenuItem> flagItems() {
        return Collections.unmodifiableMap(flagItems);
    }

    /** @return пункт со слайдером горизонта */
    public CustomMenuItem horizonItem() {
        return horizonItem;
    }

    /** @return слайдер горизонта */
    public Slider horizonSlider() {
        return horizonSlider;
    }

    /** @return поле фильтра */
    public TextField filterField() {
        return filterField;
    }

    /** @return флажок «Доходы −10 %» */
    public CheckMenuItem incomeDownItem() {
        return incomeDown;
    }

    /** @return флажок «Расходы +10 %» */
    public CheckMenuItem expenseUpItem() {
        return expenseUp;
    }

    /** @return Spinner доп. экономии */
    public Spinner<Integer> savingSpinner() {
        return savingSpinner;
    }

    /**
     * Выбирает режим так, как это сделал бы пользователь пунктом меню (самотест).
     *
     * @param mode режим
     */
    public void selectMode(ViewMode mode) {
        modeGroup.selectToggle(mode == ViewMode.CHART ? chartItem : tableItem);
    }

    /**
     * Выбирает период пунктом меню (самотест).
     *
     * @param period период
     */
    public void selectPeriod(PeriodChoice period) {
        periodGroup.selectToggle(periodItems.get(period));
    }

    /**
     * Ставит флажок «Вид» (самотест).
     *
     * @param key   ключ {@link ViewFlags}
     * @param value значение
     * @throws IllegalArgumentException для неизвестного ключа
     */
    public void setFlag(String key, boolean value) {
        CheckMenuItem item = flagItems.get(key);
        if (item == null) {
            throw new IllegalArgumentException("Неизвестный флаг вида: " + key);
        }
        item.setSelected(value);
    }
}
