package ru.cashprediction.swing.dialog;

import java.awt.BorderLayout;
import java.awt.Window;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.MonthDay;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurrenceKind;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.WeekendPolicy;
import ru.cashprediction.core.recurrence.Occurrence;
import ru.cashprediction.core.recurrence.OccurrenceGenerator;
import ru.cashprediction.core.session.SessionRecorder;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.util.DateFormats;
import ru.cashprediction.core.util.RuText;

/**
 * Диалог 3 «Регулярная операция»: название, тип, сумма, категория, повтор с зависящими от него полями,
 * «С»/«По», сдвиг с выходных, признак активности и заметка. Внизу — живой предпросмотр «Ближайшие даты»
 * ({@link OccurrenceGenerator#upcoming}).
 *
 * <p><b>Динамические поля.</b> Для «ежемесячно» видны день месяца и «каждые N месяцев», для «еженедельно» —
 * день недели и «каждые N недель», для «каждые N дней» — только N, для «ежегодно» — день и месяц.
 * Если повтор отсчитывается от даты («каждые 2 месяца», «каждые 3 дня»), а «С» не задана, редактор сам включает
 * «С» и подставляет дату начала плана — как требует {@code PlanValidator}.</p>
 *
 * <p><b>Корректировка из предпросмотра.</b> В режиме изменения выбранную ближайшую дату можно скорректировать:
 * кнопка вызывает колбэк, и фасад открывает диалог «Корректировка» поверх этого окна (вложенные модальные
 * окна — сценарий проверки восстановления после сбоя).</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
// JavaFX: Dialog<RecurringRule> + DialogPane → Swing: SwingDialog<RecurringRule> + SwingDialogPane → Web: openDialog(id): Promise<R> поверх <dialog>
public final class RuleDialog extends SwingDialog<RecurringRule> {

    private static final int PREVIEW_COUNT = 6;
    private static final Pattern MONTH_DAY = Pattern.compile("\\s*(\\d{1,2})-(\\d{1,2})\\s*");

    private final Plan plan;
    private final RecurringRule original;
    private final RuleId ruleId;
    private final LocalDate today;
    private final Consumer<LocalDate> adjustRequest;

    private final JTextField title = new JTextField(24);
    private final JComboBox<Kind> kind;
    private final MoneyField amount = new MoneyField();
    private final JComboBox<String> category;
    private final JComboBox<RecurrenceKind> recurrenceKind =
            Combos.choice(RecurrenceKind::title, RecurrenceKind.MONTHLY, RecurrenceKind.values());
    private final JSpinner dayOfMonth = new JSpinner(new SpinnerNumberModel(1, 1, 31, 1));
    private final JSpinner everyN = new JSpinner(new SpinnerNumberModel(1, 1, 366, 1));
    private final JLabel everyNUnit = new JLabel("мес.");
    private final JComboBox<DayOfWeek> weekday = Combos.choice(RuText::weekdayFull, DayOfWeek.MONDAY, DayOfWeek.values());
    private final JSpinner monthDayDay = new JSpinner(new SpinnerNumberModel(1, 1, 31, 1));
    private final JComboBox<Month> monthDayMonth = Combos.choice(RuText::monthNominative, Month.JANUARY, Month.values());
    private final JCheckBox fromEnabled = new JCheckBox();
    private final DateField from = new DateField();
    private final JCheckBox untilEnabled = new JCheckBox();
    private final DateField until = new DateField();
    private final JComboBox<WeekendPolicy> weekendPolicy =
            Combos.choice(WeekendPolicy::title, WeekendPolicy.NONE, WeekendPolicy.values());
    private final JCheckBox enabled = new JCheckBox("правило участвует в прогнозе", true);
    private final JTextField note = new JTextField(24);

    private final DefaultListModel<Occurrence> previewModel = new DefaultListModel<>();
    private final JList<Occurrence> preview = new JList<>(previewModel);
    private final JLabel previewEmpty = new JLabel("Событий нет");
    private final JButton adjustButton = new JButton("Скорректировать выбранную дату…");

    private final JLabel dayOfMonthLabel;
    private final JLabel everyNLabel;
    private final JPanel everyNRow;
    private final JLabel weekdayLabel;
    private final JLabel monthDayLabel;
    private final JPanel monthDayRow;
    private RecurrenceKind shownKind;

    /**
     * Создаёт редактор правила.
     *
     * @param owner         окно-владелец
     * @param ownerId       идентификатор владельца для снимка
     * @param recorder      рекордер сессии или {@code null}
     * @param plan          текущий план (категории, дата начала, корректировки)
     * @param existing      редактируемое правило или {@code null} для создания
     * @param presetKind    тип нового правила ({@code INCOME} для «Добавить доход»); {@code null} — расход
     * @param today         сегодняшняя дата
     * @param adjustRequest колбэк «скорректировать событие с номинальной датой» или {@code null}
     */
    public RuleDialog(Window owner, String ownerId, SessionRecorder recorder, Plan plan, RecurringRule existing,
                      Kind presetKind, LocalDate today, Consumer<LocalDate> adjustRequest) {
        super(owner, ownerId, WindowType.RULE_EDITOR, true, "Регулярная операция", recorder);
        this.plan = plan;
        this.original = existing;
        this.ruleId = existing != null ? existing.id() : plan.nextRuleId();
        this.today = today;
        this.adjustRequest = adjustRequest;
        putContext(WindowType.CONTEXT_MODE, existing != null ? WindowType.MODE_EDIT : WindowType.MODE_CREATE);
        putContext(WindowType.CONTEXT_RULE_ID, existing != null ? existing.id().value() : "");

        Kind initialKind = existing != null ? existing.kind() : presetKind != null ? presetKind : Kind.EXPENSE;
        kind = Combos.choice(Kind::title, initialKind, Kind.values());
        category = Combos.editable(plan.categories(), existing != null ? existing.category() : "");
        fillInitialValues(existing, initialKind);

        FormPanel form = new FormPanel();
        form.addRow("Название", title);
        form.addRow("Тип", kind);
        form.addRow("Сумма", amount);
        form.addRow("Категория", category);
        form.addRow("Повтор", recurrenceKind);
        dayOfMonthLabel = form.addRow("День месяца", dayOfMonth);
        // JavaFX: Tooltip → Swing: setToolTipText → Web: title
        dayOfMonth.setToolTipText("31 - последний день месяца");
        everyNRow = FormPanel.inline(everyN, everyNUnit);
        everyNLabel = form.addRow("Каждые", everyNRow);
        weekdayLabel = form.addRow("День недели", weekday);
        monthDayRow = FormPanel.inline(monthDayDay, monthDayMonth);
        monthDayLabel = form.addRow("Дата в году", monthDayRow);
        form.addRow("С", fromEnabled, from);
        form.addRow("По", untilEnabled, until);
        form.addRow("Если выходной", weekendPolicy);
        form.addRow("Активна", enabled);
        form.addRow("Заметка", note);
        form.addSection("Ближайшие даты");
        preview.setVisibleRowCount(PREVIEW_COUNT);
        preview.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        preview.setCellRenderer(Combos.renderer(this::formatOccurrence));
        JPanel previewPanel = new JPanel(new BorderLayout(0, 4));
        previewPanel.add(previewEmpty, BorderLayout.NORTH);
        previewPanel.add(new JScrollPane(preview), BorderLayout.CENTER);
        if (existing != null && adjustRequest != null) {
            previewPanel.add(FormPanel.inline(adjustButton), BorderLayout.SOUTH);
        }
        form.addWide(previewPanel, true);
        Runnable header = () -> pane().setHeaderText(existing != null
                ? "Изменение правила «" + existing.title() + "» (" + existing.id().value() + ")"
                : Combos.selected(kind) == Kind.INCOME ? "Новый регулярный доход" : "Новый регулярный расход");
        header.run();
        // Заголовок нового правила следует за выбранным типом: пользователь мог открыть «Добавить доход» и выбрать расход.
        kind.addItemListener(e -> header.run());
        pane().setContent(form);

        // JavaFX: Tooltip → Swing: setToolTipText → Web: title
        from.setToolTipText("Первая дата, с которой действует правило; от неё же считаются «каждые N»");
        until.setToolTipText("Последняя дата, до которой действует правило");

        // Порядок привязки — как в словаре окон (WindowType.RULE_EDITOR.fieldIds()).
        binder().bindText("title", title);
        binder().bindEnumCombo("kind", kind, Kind.class);
        binder().bindMoney("amount", amount);
        binder().bindEditableCombo("category", category);
        binder().bindEnumCombo("recurrenceKind", recurrenceKind, RecurrenceKind.class);
        binder().bindSpinner("dayOfMonth", dayOfMonth);
        binder().bindSpinner("everyN", everyN);
        binder().bindEnumCombo("weekday", weekday, DayOfWeek.class);
        binder().bindCustom("monthDay", this::monthDayText, this::applyMonthDayText);
        monthDayDay.addChangeListener(e -> binder().changed());
        monthDayMonth.addItemListener(e -> binder().changed());
        binder().bindCheckBox("fromEnabled", fromEnabled);
        binder().bindDate("from", from);
        binder().bindCheckBox("untilEnabled", untilEnabled);
        binder().bindDate("until", until);
        binder().bindEnumCombo("weekendPolicy", weekendPolicy, WeekendPolicy.class);
        binder().bindCheckBox("enabled", enabled);
        binder().bindText("note", note);

        // Подстановка «С» — только на ввод пользователя, не при восстановлении из снимка.
        recurrenceKind.addItemListener(e -> ensureAnchor());
        everyN.addChangeListener(e -> ensureAnchor());
        preview.addListSelectionListener(e -> updateAdjustButton());
        adjustButton.addActionListener(e -> {
            Occurrence selected = preview.getSelectedValue();
            if (selected != null && this.adjustRequest != null) {
                this.adjustRequest.accept(selected.nominal());
            }
        });

        setValidator(this::formError);
        setWarningSupplier(this::formWarning);
        setResultConverter(button -> button.isDefaultButton() ? build() : null);
        setButtonTypes(SwingButtonType.OK, SwingButtonType.CANCEL);
        setInitialFocus(existing != null ? amount : title);
    }

    private void fillInitialValues(RecurringRule existing, Kind initialKind) {
        if (existing == null) {
            LocalDate base = today.isBefore(plan.startDate()) ? plan.startDate() : today;
            dayOfMonth.setValue(base.getDayOfMonth());
            weekday.setSelectedItem(base.getDayOfWeek());
            monthDayDay.setValue(base.getDayOfMonth());
            monthDayMonth.setSelectedItem(base.getMonth());
            // Доход обычно переносят на пятницу, расходы по умолчанию не сдвигаем.
            weekendPolicy.setSelectedItem(initialKind == Kind.INCOME ? WeekendPolicy.PREVIOUS_BUSINESS_DAY : WeekendPolicy.NONE);
            return;
        }
        title.setText(existing.title());
        amount.setValue(existing.amount());
        switch (existing.recurrence()) {
            case Recurrence.Monthly m -> {
                recurrenceKind.setSelectedItem(RecurrenceKind.MONTHLY);
                dayOfMonth.setValue(m.dayOfMonth());
                everyN.setValue(m.everyMonths());
            }
            case Recurrence.Weekly w -> {
                recurrenceKind.setSelectedItem(RecurrenceKind.WEEKLY);
                weekday.setSelectedItem(w.weekday());
                everyN.setValue(w.everyWeeks());
            }
            case Recurrence.EveryNDays d -> {
                recurrenceKind.setSelectedItem(RecurrenceKind.EVERY_N_DAYS);
                everyN.setValue(d.days());
            }
            case Recurrence.Yearly y -> {
                recurrenceKind.setSelectedItem(RecurrenceKind.YEARLY);
                monthDayDay.setValue(y.monthDay().getDayOfMonth());
                monthDayMonth.setSelectedItem(y.monthDay().getMonth());
            }
        }
        fromEnabled.setSelected(existing.from() != null);
        from.setValue(existing.from());
        untilEnabled.setSelected(existing.until() != null);
        until.setValue(existing.until());
        weekendPolicy.setSelectedItem(existing.weekendPolicy());
        enabled.setSelected(existing.enabled());
        note.setText(existing.note());
    }

    // ------------------------------------------------------------------ динамическая часть

    /** {@inheritDoc} */
    @Override
    protected void onFieldsChanged() {
        RecurrenceKind selected = Combos.selected(recurrenceKind);
        boolean monthly = selected == RecurrenceKind.MONTHLY;
        boolean weekly = selected == RecurrenceKind.WEEKLY;
        boolean yearly = selected == RecurrenceKind.YEARLY;
        dayOfMonthLabel.setVisible(monthly);
        dayOfMonth.setVisible(monthly);
        everyNLabel.setVisible(!yearly);
        everyNRow.setVisible(!yearly);
        everyNUnit.setText(monthly ? "мес." : weekly ? "нед." : "дн.");
        weekdayLabel.setVisible(weekly);
        weekday.setVisible(weekly);
        monthDayLabel.setVisible(yearly);
        monthDayRow.setVisible(yearly);
        from.setEnabled(fromEnabled.isSelected());
        until.setEnabled(untilEnabled.isSelected());
        if (selected != shownKind) {
            shownKind = selected;
            if (window().isShowing()) {
                // Набор видимых строк изменился — подгоняем высоту окна.
                window().pack();
            }
        }
        updatePreview();
    }

    /** Включает «С» с датой начала плана, если новый повтор отсчитывается от даты. */
    private void ensureAnchor() {
        if (binder().isApplying()) {
            return;
        }
        Optional<Recurrence> recurrence = tryRecurrence();
        if (recurrence.isPresent() && recurrence.get().needsAnchor() && !fromEnabled.isSelected()) {
            fromEnabled.setSelected(true);
            if (from.isBlank()) {
                from.setValue(plan.startDate());
            }
        }
    }

    private void updatePreview() {
        Occurrence previouslySelected = preview.getSelectedValue();
        previewModel.clear();
        Optional<RecurringRule> rule = tryRule();
        if (rule.isPresent()) {
            try {
                LocalDate fromInclusive = today.isBefore(plan.startDate()) ? plan.startDate() : today;
                List<Occurrence> dates = OccurrenceGenerator.upcoming(rule.get(), plan.startDate(), fromInclusive, PREVIEW_COUNT);
                dates.forEach(previewModel::addElement);
            } catch (RuntimeException e) {
                // Предпросмотр не должен ломать форму: ошибку правила покажет проверка формы.
            }
        }
        String hint = rule.isEmpty() ? "Заполните повтор и даты, чтобы увидеть ближайшие события"
                : previewModel.isEmpty() ? "В ближайшие годы событий нет" : "";
        previewEmpty.setText(hint);
        // Пустая подсказка не должна оставлять пустую строку между заголовком раздела и списком дат.
        previewEmpty.setVisible(!hint.isEmpty());
        if (previouslySelected != null) {
            for (int i = 0; i < previewModel.size(); i++) {
                if (previewModel.get(i).nominal().equals(previouslySelected.nominal())) {
                    preview.setSelectedIndex(i);
                }
            }
        }
        updateAdjustButton();
    }

    private void updateAdjustButton() {
        adjustButton.setEnabled(original != null && adjustRequest != null && plan.findRule(ruleId).isPresent()
                && preview.getSelectedValue() != null);
    }

    private String formatOccurrence(Occurrence occurrence) {
        String text = RuText.weekdayShort(occurrence.actual().getDayOfWeek()) + " " + DateFormats.ru(occurrence.actual());
        if (occurrence.shifted()) {
            text += "   (по графику " + DateFormats.ru(occurrence.nominal()) + ", сдвиг с выходного)";
        }
        return text;
    }

    // ------------------------------------------------------------------ «месяц-день»

    private String monthDayText() {
        Month month = Combos.selected(monthDayMonth);
        int day = ((Number) monthDayDay.getValue()).intValue();
        return String.format("%02d-%02d", month == null ? 1 : month.getValue(), day);
    }

    private void applyMonthDayText(String text) {
        Matcher m = MONTH_DAY.matcher(text);
        if (m.matches()) {
            int month = Integer.parseInt(m.group(1));
            int day = Integer.parseInt(m.group(2));
            if (month >= 1 && month <= 12) {
                monthDayMonth.setSelectedItem(Month.of(month));
            }
            monthDayDay.setValue(Math.max(1, Math.min(31, day)));
        }
    }

    // ------------------------------------------------------------------ проверка и результат

    private Optional<Recurrence> tryRecurrence() {
        try {
            return Optional.of(recurrence());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Recurrence recurrence() {
        int n = ((Number) everyN.getValue()).intValue();
        RecurrenceKind selected = Objects.requireNonNull(Combos.selected(recurrenceKind));
        return switch (selected) {
            case MONTHLY -> new Recurrence.Monthly(((Number) dayOfMonth.getValue()).intValue(), n);
            case WEEKLY -> new Recurrence.Weekly(Combos.selected(weekday), n);
            case EVERY_N_DAYS -> new Recurrence.EveryNDays(n);
            case YEARLY -> {
                try {
                    yield new Recurrence.Yearly(MonthDay.of(Combos.selected(monthDayMonth), ((Number) monthDayDay.getValue()).intValue()));
                } catch (DateTimeException e) {
                    throw new IllegalArgumentException("Такой даты в году нет: " + monthDayDay.getValue() + " "
                            + RuText.monthGenitive(Combos.selected(monthDayMonth)), e);
                }
            }
        };
    }

    /** Правило для предпросмотра: сумма не важна, поэтому неверная сумма заменяется условной. */
    private Optional<RecurringRule> tryRule() {
        Optional<Recurrence> recurrence = tryRecurrence();
        if (recurrence.isEmpty() || (fromEnabled.isSelected() && from.value().isEmpty())
                || (untilEnabled.isSelected() && until.value().isEmpty())) {
            return Optional.empty();
        }
        return Optional.of(new RecurringRule(ruleId, title.getText(), Combos.selected(kind),
                amount.value().filter(Money::isPositive).orElse(Money.ofMajor(1)), "", recurrence.get(),
                fromEnabled.isSelected() ? from.value().orElse(null) : null,
                untilEnabled.isSelected() ? until.value().orElse(null) : null,
                Combos.selected(weekendPolicy), enabled.isSelected(), ""));
    }

    private String formError() {
        if (title.getText().isBlank()) {
            return "Укажите название операции";
        }
        String error = amount.validationError("Сумма", true, true);
        if (error != null) {
            return error;
        }
        Recurrence recurrence;
        try {
            recurrence = recurrence();
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        if (fromEnabled.isSelected() && (error = from.validationError("С", true)) != null) {
            return error;
        }
        if (untilEnabled.isSelected() && (error = until.validationError("По", true)) != null) {
            return error;
        }
        if (fromEnabled.isSelected() && untilEnabled.isSelected() && until.value().orElseThrow().isBefore(from.value().orElseThrow())) {
            return "Дата «По» раньше даты «С»";
        }
        if (recurrence.needsAnchor() && !fromEnabled.isSelected()) {
            return "Для повтора «" + recurrence.toRussian() + "» укажите дату «С»: от неё отсчитываются повторы";
        }
        return null;
    }

    private String formWarning() {
        if (original != null) {
            int adjustments = plan.adjustmentsOf(ruleId).size();
            LocalDate newFrom = fromEnabled.isSelected() ? from.value().orElse(null) : null;
            boolean scheduleChanged = !tryRecurrence().equals(Optional.of(original.recurrence()))
                    || !Objects.equals(newFrom, original.from());
            if (adjustments > 0 && scheduleChanged) {
                return RuText.count(adjustments, "корректировка", "корректировки", "корректировок")
                        + " этого правила могут перестать совпадать с датами событий";
            }
        }
        LocalDate newFrom = fromEnabled.isSelected() ? from.value().orElse(null) : null;
        LocalDate newUntil = untilEnabled.isSelected() ? until.value().orElse(null) : null;
        if ((newUntil != null && newUntil.isBefore(plan.startDate())) || (newFrom != null && newFrom.isAfter(plan.endDate()))) {
            return "Правило не даёт событий в горизонте прогноза (" + DateFormats.ru(plan.startDate()) + " - "
                    + DateFormats.ru(plan.endDate()) + ")";
        }
        return null;
    }

    private RecurringRule build() {
        String error = formError();
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
        return new RecurringRule(ruleId, title.getText(), Combos.selected(kind), amount.value().orElseThrow(),
                Combos.editorText(category), recurrence(),
                fromEnabled.isSelected() ? from.value().orElseThrow() : null,
                untilEnabled.isSelected() ? until.value().orElseThrow() : null,
                Combos.selected(weekendPolicy), enabled.isSelected(), note.getText());
    }
}
