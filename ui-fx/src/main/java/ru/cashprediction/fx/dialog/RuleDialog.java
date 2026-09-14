package ru.cashprediction.fx.dialog;

import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import ru.cashprediction.core.diagnostics.PlanValidator;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurrenceKind;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.WeekendPolicy;
import ru.cashprediction.core.recurrence.Occurrence;
import ru.cashprediction.core.recurrence.OccurrenceGenerator;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.util.DateFormats;
import ru.cashprediction.core.util.RuText;
import ru.cashprediction.fx.session.FieldValues;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Диалог 3 «Регулярная операция»: создание и изменение правила повторяющегося дохода или расхода.
 *
 * <p>Поля повтора меняются в зависимости от вида: «ежемесячно» — день месяца и период в месяцах,
 * «еженедельно» — день недели и период в неделях, «каждые N дней» — только период, «ежегодно» — день года.
 * Внизу живой предпросмотр «Ближайшие даты» ({@link OccurrenceGenerator#upcoming}), чтобы пользователь
 * сразу видел, как правило ляжет на календарь и что сделает сдвиг с выходных.</p>
 *
 * <p>В режиме изменения из предпросмотра можно открыть «Корректировку» конкретного события
 * (контекстное меню или кнопка): она открывается как вложенный модальный диалог поверх этого окна —
 * именно такой сценарий «два вложенных окна» проверяет восстановление после сбоя.</p>
 *
 * <p>Результат — правило; для нового правила идентификатор назначает фасад в момент применения.
 * Только FX Application Thread.</p>
 */
// JavaFX: Dialog<R> + DialogPane (AppDialogPane) + ButtonType → Swing: SwingDialog<R> (RuleDialog) → Web: openDialog('rule')
public final class RuleDialog extends FxStatefulDialog<RecurringRule> {

    /** Сколько ближайших дат показывать в предпросмотре. */
    private static final int PREVIEW_COUNT = 6;

    private final Plan plan;
    private final RecurringRule existing;
    private final LocalDate today;
    private final BiConsumer<OccurrenceKey, String> adjustOccurrence;

    private final TextField title = new TextField();
    private final ToggleGroup kindGroup = new ToggleGroup();
    private final RadioButton income = new RadioButton(Kind.INCOME.title());
    private final RadioButton expense = new RadioButton(Kind.EXPENSE.title());
    private final TextField amount;
    private final ComboBox<String> category = new ComboBox<>();
    private final ComboBox<RecurrenceKind> recurrenceKind = new ComboBox<>();
    private final Spinner<Integer> dayOfMonth = FxInputs.intSpinner(1, 31, 1);
    private final Spinner<Integer> everyN = FxInputs.intSpinner(1, 366, 1);
    private final ComboBox<DayOfWeek> weekday = new ComboBox<>();
    private final TextField monthDay = new TextField();
    private final CheckBox fromEnabled = new CheckBox("с даты");
    private final DatePicker from;
    private final CheckBox untilEnabled = new CheckBox("по дату");
    private final DatePicker until;
    private final ComboBox<WeekendPolicy> weekendPolicy = new ComboBox<>();
    private final CheckBox enabled = new CheckBox("Операция активна (учитывается в прогнозе)");
    private final TextArea note = new TextArea();
    private final ListView<Occurrence> preview = new ListView<>();
    private final Button adjustButton = new Button("Скорректировать событие…");
    private final FormGrid form = new FormGrid();
    private final HBox fromBox;
    private final HBox untilBox;

    /**
     * Создаёт редактор правила.
     *
     * @param plan             текущий план (дата начала, категории, корректировки)
     * @param existing         изменяемое правило или {@code null} для нового
     * @param kindForCreate    тип нового правила (доход или расход)
     * @param today            сегодняшняя дата (начало предпросмотра)
     * @param adjustOccurrence открыть корректировку события поверх этого окна (ключ события, идентификатор этого окна)
     *                         или {@code null}, если нельзя
     */
    public RuleDialog(Plan plan, RecurringRule existing, Kind kindForCreate, LocalDate today,
                      BiConsumer<OccurrenceKey, String> adjustOccurrence) {
        super(WindowType.RULE_EDITOR, new AppDialogPane(header(existing, kindForCreate), "↻"));
        this.plan = plan;
        this.existing = existing;
        this.today = today;
        this.adjustOccurrence = adjustOccurrence;

        stateSupport().putContext(WindowType.CONTEXT_MODE, existing == null ? WindowType.MODE_CREATE : WindowType.MODE_EDIT);
        if (existing != null) {
            stateSupport().putContext(WindowType.CONTEXT_RULE_ID, existing.id().value());
        }

        Kind kind = existing == null ? kindForCreate : existing.kind();
        income.setUserData(Kind.INCOME.name());
        expense.setUserData(Kind.EXPENSE.name());
        income.setToggleGroup(kindGroup);
        expense.setToggleGroup(kindGroup);
        (kind == Kind.INCOME ? income : expense).setSelected(true);
        title.setText(existing == null ? "" : existing.title());
        title.setPromptText(kind == Kind.INCOME ? "например, Зарплата" : "например, Аренда");
        if (existing == null) {
            // Новое правило: заголовок «Новый регулярный доход/расход» и пример названия следуют выбранному типу,
            // иначе после переключения на «Расход» (в том числе при восстановлении снимка) заголовок врал бы.
            kindGroup.selectedToggleProperty().addListener((o, was, is) -> {
                Kind selected = is == expense ? Kind.EXPENSE : Kind.INCOME;
                appPane().setHeaderText(header(null, selected));
                title.setPromptText(selected == Kind.INCOME ? "например, Зарплата" : "например, Аренда");
            });
        }
        amount = FxInputs.moneyField(existing == null ? null : existing.amount());
        category.getItems().setAll(plan.categories());
        category.setEditable(true);
        category.setValue(existing == null ? "" : existing.category());
        category.setMaxWidth(Double.MAX_VALUE);

        recurrenceKind.getItems().setAll(RecurrenceKind.values());
        recurrenceKind.setConverter(FxInputs.displayConverter(RecurrenceKind::title));
        weekday.getItems().setAll(DayOfWeek.values());
        weekday.setConverter(FxInputs.displayConverter(FxInputs::weekdayTitle));
        weekday.setValue(DayOfWeek.MONDAY);
        monthDay.setPromptText("ММ-ДД, например 03-15");
        monthDay.setPrefColumnCount(8);
        dayOfMonth.getValueFactory().setValue(plan.startDate().getDayOfMonth());
        // JavaFX: Tooltip → Swing: setToolTipText → Web: title / <div class="tooltip">
        dayOfMonth.setTooltip(new Tooltip("31 - последний день месяца (в феврале 28 или 29)"));
        fillRecurrence(existing == null ? new Recurrence.Monthly(plan.startDate().getDayOfMonth(), 1) : existing.recurrence());

        from = FxInputs.datePicker(existing == null || existing.from() == null ? plan.startDate() : existing.from());
        until = FxInputs.datePicker(existing == null ? null : existing.until());
        fromEnabled.setSelected(existing != null && existing.from() != null);
        untilEnabled.setSelected(existing != null && existing.until() != null);
        from.disableProperty().bind(fromEnabled.selectedProperty().not());
        until.disableProperty().bind(untilEnabled.selectedProperty().not());
        fromBox = new HBox(8, fromEnabled, from);
        untilBox = new HBox(8, untilEnabled, until);
        fromBox.setAlignment(Pos.CENTER_LEFT);
        untilBox.setAlignment(Pos.CENTER_LEFT);

        weekendPolicy.getItems().setAll(WeekendPolicy.values());
        weekendPolicy.setConverter(FxInputs.displayConverter(WeekendPolicy::title));
        weekendPolicy.setValue(existing == null ? WeekendPolicy.NONE : existing.weekendPolicy());
        enabled.setSelected(existing == null || existing.enabled());
        note.setText(existing == null ? "" : existing.note());
        note.setPrefRowCount(2);
        note.setWrapText(true);

        buildPreview();
        form.row("Название", title)
                .row("Тип", new HBox(16, income, expense))
                .row("Сумма", amount)
                .row("Категория", category)
                .section("Когда повторяется")
                .row("Повтор", recurrenceKind)
                .row("День месяца", dayOfMonth)
                .row("Каждые N", everyN)
                .row("День недели", weekday)
                .row("День года", monthDay)
                .row("Начало", fromBox)
                .row("Окончание", untilBox)
                .row("Если выпало на выходной", weekendPolicy)
                .wide(enabled)
                .row("Заметка", note);
        Label previewTitle = new Label("Ближайшие даты");
        previewTitle.setStyle("-fx-font-weight: bold;");
        preview.setPrefWidth(320);
        VBox.setVgrow(preview, Priority.ALWAYS);
        adjustButton.setMaxWidth(Double.MAX_VALUE);
        VBox previewBox = new VBox(6, previewTitle, preview, adjustButton);
        // Предпросмотр справа от формы, а не под ней: в одну колонку диалог был выше экрана ноутбука
        // (около 900 логических точек при масштабе 200 %), и кнопки «Сохранить/Отмена» уходили за край.
        HBox columns = new HBox(14, form, new Separator(Orientation.VERTICAL), previewBox);
        HBox.setHgrow(form, Priority.ALWAYS);
        appPane().setForm(columns);
        appPane().setPrefWidth(920);
        // JavaFX: ButtonType → Swing: SwingButtonType (текст + роль) + JOptionPane.showOptionDialog → Web: <button value> → returnValue
        appPane().getButtonTypes().setAll(AppButtonTypes.SAVE, AppButtonTypes.CANCEL);

        // Порядок привязки = порядок полей словаря WindowType.RULE_EDITOR.
        binder().bindText("title", title);
        binder().bindToggle("kind", kindGroup);
        binder().bindMoney("amount", amount);
        binder().bindEditableCombo("category", category);
        binder().bindCombo("recurrenceKind", recurrenceKind, Enum::name, RecurrenceKind::valueOf);
        binder().bindSpinner("dayOfMonth", dayOfMonth);
        binder().bindSpinner("everyN", everyN);
        binder().bindCombo("weekday", weekday, Enum::name, DayOfWeek::valueOf);
        binder().bindMonthDay("monthDay", monthDay);
        binder().bindCheck("fromEnabled", fromEnabled);
        binder().bindDate("from", from);
        binder().bindCheck("untilEnabled", untilEnabled);
        binder().bindDate("until", until);
        binder().bindCombo("weekendPolicy", weekendPolicy, Enum::name, WeekendPolicy::valueOf);
        binder().bindCheck("enabled", enabled);
        binder().bindText("note", note);

        setResultConverter(button -> button == AppButtonTypes.SAVE
                ? buildRule(new ArrayList<>(), new ArrayList<>()).orElse(null) : null);
        activate();
    }

    /** {@inheritDoc} */
    @Override
    protected void validateForm(List<String> errors, List<String> warnings) {
        updateRecurrenceRows();
        Optional<RecurringRule> rule = buildRule(errors, warnings);
        if (rule.isPresent()) {
            try {
                LocalDate previewFrom = today.isAfter(plan.startDate()) ? today : plan.startDate();
                preview.getItems().setAll(OccurrenceGenerator.upcoming(rule.get(), plan.startDate(), previewFrom, PREVIEW_COUNT));
                if (preview.getItems().isEmpty()) {
                    warnings.add("У правила нет ближайших дат: проверьте «Начало» и «Окончание»");
                }
            } catch (RuntimeException e) {
                preview.getItems().clear();
                errors.add(e.getMessage());
            }
        } else {
            preview.getItems().clear();
        }
        adjustButton.setDisable(!canAdjust());
    }

    // ------------------------------------------------------------------ сборка правила

    private Optional<RecurringRule> buildRule(List<String> errors, List<String> warnings) {
        if (FxInputs.isBlank(title)) {
            errors.add("Укажите название операции");
        }
        Optional<Money> money = FxInputs.money(amount);
        if (FxInputs.isBlank(amount)) {
            errors.add("Укажите сумму");
        } else if (money.isEmpty()) {
            errors.add("Некорректная сумма: «" + amount.getText().strip() + "»");
        } else if (!money.get().isPositive()) {
            errors.add("Сумма должна быть больше нуля (доход это или расход, задаёт «Тип»)");
        } else if (money.get().compareTo(PlanValidator.MAX_AMOUNT) > 0) {
            errors.add("Сумма слишком большая");
        }
        Optional<Recurrence> recurrence = readRecurrence(errors);
        LocalDate fromDate = null;
        if (fromEnabled.isSelected()) {
            fromDate = FxInputs.date(from).orElse(null);
            if (fromDate == null) {
                errors.add("Укажите дату начала правила (ДД.ММ.ГГГГ) или снимите флажок «с даты»");
            }
        }
        LocalDate untilDate = null;
        if (untilEnabled.isSelected()) {
            untilDate = FxInputs.date(until).orElse(null);
            if (untilDate == null) {
                errors.add("Укажите дату окончания правила (ДД.ММ.ГГГГ) или снимите флажок «по дату»");
            }
        }
        if (!errors.isEmpty() || recurrence.isEmpty()) {
            return Optional.empty();
        }
        if (fromDate == null && recurrence.get().needsAnchor()) {
            // Для «каждые 2 месяца» и «каждые N дней» важна точка отсчёта: без неё расписание сдвинется
            // после «Актуализировать». Редактор подставляет дату начала плана, как требует валидатор.
            fromDate = plan.startDate();
            warnings.add("Поле «с даты» будет заполнено датой начала плана (" + DateFormats.ru(fromDate)
                    + "): от неё отсчитывается период");
        }
        if (fromDate != null && untilDate != null && untilDate.isBefore(fromDate)) {
            errors.add("Дата окончания раньше даты начала правила");
            return Optional.empty();
        }
        RuleId id = existing != null ? existing.id() : plan.nextRuleId();
        Kind kind = expense.isSelected() ? Kind.EXPENSE : Kind.INCOME;
        RecurringRule rule = new RecurringRule(id, title.getText(), kind, money.orElseThrow(), category.getEditor().getText(),
                recurrence.get(), fromDate, untilDate, weekendPolicy.getValue(), enabled.isSelected(), note.getText());
        addRuleWarnings(rule, warnings);
        return Optional.of(rule);
    }

    private Optional<Recurrence> readRecurrence(List<String> errors) {
        RecurrenceKind kind = recurrenceKind.getValue() == null ? RecurrenceKind.MONTHLY : recurrenceKind.getValue();
        Optional<Integer> n = FxInputs.integer(everyN);
        try {
            return switch (kind) {
                case MONTHLY -> {
                    Optional<Integer> day = FxInputs.integer(dayOfMonth);
                    if (day.isEmpty() || n.isEmpty()) {
                        errors.add("Укажите день месяца и период целыми числами");
                        yield Optional.empty();
                    }
                    yield Optional.of(new Recurrence.Monthly(day.get(), n.get()));
                }
                case WEEKLY -> {
                    if (weekday.getValue() == null || n.isEmpty()) {
                        errors.add("Укажите день недели и период в неделях");
                        yield Optional.empty();
                    }
                    yield Optional.of(new Recurrence.Weekly(weekday.getValue(), n.get()));
                }
                case EVERY_N_DAYS -> {
                    if (n.isEmpty()) {
                        errors.add("Укажите период в днях целым числом");
                        yield Optional.empty();
                    }
                    yield Optional.of(new Recurrence.EveryNDays(n.get()));
                }
                case YEARLY -> {
                    Optional<MonthDay> md = FieldValues.parseMonthDay(monthDay.getText());
                    if (md.isEmpty()) {
                        errors.add("Укажите день года в форме ММ-ДД (например, 03-15) или ДД.ММ");
                        yield Optional.empty();
                    }
                    yield Optional.of(new Recurrence.Yearly(md.get()));
                }
            };
        } catch (IllegalArgumentException e) {
            // Конструкторы Recurrence проверяют пределы («от 1 до 120 месяцев») и объясняют их по-русски.
            errors.add(e.getMessage());
            return Optional.empty();
        }
    }

    private void addRuleWarnings(RecurringRule rule, List<String> warnings) {
        LocalDate planEnd = plan.endDate();
        if (rule.enabled() && (OccurrenceGenerator.windowStart(rule, plan.startDate()).isAfter(planEnd)
                || OccurrenceGenerator.windowEnd(rule, planEnd).isBefore(plan.startDate()))) {
            warnings.add("Правило не создаёт событий в горизонте плана (" + DateFormats.ru(plan.startDate()) + " - "
                    + DateFormats.ru(planEnd) + ")");
        }
        if (existing != null) {
            long lost = plan.adjustmentsOf(existing.id()).stream()
                    .filter(a -> !OccurrenceGenerator.isNominalDate(rule, plan.startDate(), a.key().originalDate()))
                    .count();
            if (lost > 0) {
                warnings.add(RuText.count(lost, "корректировка перестанет", "корректировки перестанут", "корректировок перестанут")
                        + " совпадать с датами правила");
            }
        }
    }

    // ------------------------------------------------------------------ вид формы

    private void fillRecurrence(Recurrence recurrence) {
        recurrenceKind.setValue(recurrence.kind());
        switch (recurrence) {
            case Recurrence.Monthly m -> {
                dayOfMonth.getValueFactory().setValue(m.dayOfMonth());
                everyN.getValueFactory().setValue(m.everyMonths());
            }
            case Recurrence.Weekly w -> {
                weekday.setValue(w.weekday());
                everyN.getValueFactory().setValue(w.everyWeeks());
            }
            case Recurrence.EveryNDays d -> everyN.getValueFactory().setValue(d.days());
            case Recurrence.Yearly y -> monthDay.setText(FieldValues.MONTH_DAY.format(y.monthDay()));
        }
    }

    private void updateRecurrenceRows() {
        RecurrenceKind kind = recurrenceKind.getValue() == null ? RecurrenceKind.MONTHLY : recurrenceKind.getValue();
        form.setRowVisible(dayOfMonth, kind == RecurrenceKind.MONTHLY);
        form.setRowVisible(everyN, kind != RecurrenceKind.YEARLY);
        form.setRowVisible(weekday, kind == RecurrenceKind.WEEKLY);
        form.setRowVisible(monthDay, kind == RecurrenceKind.YEARLY);
        form.setLabel(everyN, switch (kind) {
            case MONTHLY -> "Каждые N месяцев";
            case WEEKLY -> "Каждые N недель";
            default -> "Каждые N дней";
        });
    }

    private void buildPreview() {
        preview.setPrefHeight(150);
        preview.setPlaceholder(new Label("Заполните форму - здесь появятся даты"));
        preview.setCellFactory(list -> new ListCell<>() {
            /**
             * Показывает дату предпросмотра: день недели, дату, сдвиг с выходного и отметку о корректировке.
             *
             * @param item  событие правила
             * @param empty пустая ячейка
             */
            @Override
            protected void updateItem(Occurrence item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                String text = RuText.weekdayShort(item.actual().getDayOfWeek()) + ", " + DateFormats.ru(item.actual());
                if (item.shifted()) {
                    text += "  (сдвиг с " + RuText.weekdayShort(item.nominal().getDayOfWeek()) + " "
                            + DateFormats.ru(item.nominal()) + ")";
                }
                if (existing != null && plan.findAdjustment(new OccurrenceKey(existing.id(), item.nominal())).isPresent()) {
                    text += "  ✎ есть корректировка";
                }
                setText(text);
            }
        });
        // JavaFX: MenuItem → Swing: JMenuItem → Web: <li role="menuitem">
        MenuItem adjust = new MenuItem("Скорректировать это событие…");
        adjust.setOnAction(e -> openAdjustment());
        // JavaFX: ContextMenu → Swing: JPopupMenu (setComponentPopupMenu) → Web: <ul class="context-menu">
        ContextMenu menu = new ContextMenu(adjust);
        menu.setOnShowing(e -> adjust.setDisable(!canAdjust()));
        // JavaFX: ContextMenuEvent → Swing: MouseAdapter.isPopupTrigger() в mousePressed и mouseReleased → Web: contextmenu + preventDefault
        preview.setOnContextMenuRequested(e -> {
            menu.show(preview, e.getScreenX(), e.getScreenY());
            e.consume();
        });
        preview.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> adjustButton.setDisable(!canAdjust()));
        adjustButton.setOnAction(e -> openAdjustment());
        adjustButton.setDisable(true);
        if (adjustOccurrence == null || existing == null) {
            // Корректировать можно только события уже сохранённого правила: у нового правила ещё нет идентификатора.
            // JavaFX: Tooltip → Swing: setToolTipText → Web: title / <div class="tooltip">
            adjustButton.setTooltip(new Tooltip("Доступно при изменении существующего правила"));
        }
    }

    private boolean canAdjust() {
        return adjustOccurrence != null && existing != null && preview.getSelectionModel().getSelectedItem() != null;
    }

    private void openAdjustment() {
        Occurrence selected = preview.getSelectionModel().getSelectedItem();
        if (canAdjust() && selected != null) {
            adjustOccurrence.accept(new OccurrenceKey(existing.id(), selected.nominal()), windowId());
        }
    }

    private static String header(RecurringRule existing, Kind kind) {
        if (existing != null) {
            return "Изменение регулярной операции «" + existing.title() + "»";
        }
        return kind == Kind.INCOME ? "Новый регулярный доход" : "Новый регулярный расход";
    }
}
