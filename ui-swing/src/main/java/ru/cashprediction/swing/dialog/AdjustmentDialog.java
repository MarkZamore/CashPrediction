package ru.cashprediction.swing.dialog;

import java.awt.Window;
import java.time.LocalDate;
import java.util.Optional;
import javax.swing.JComboBox;
import javax.swing.JTextField;
import ru.cashprediction.core.markdown.RuFormats;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.session.SessionRecorder;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.util.DateFormats;

/**
 * Диалог 5 «Корректировка события»: что сделать с одним событием правила — пропустить, изменить сумму,
 * перенести дату или заменить и сумму, и дату.
 *
 * <p>Событие определяется правилом и <i>номинальной</i> датой по графику (контекст окна {@code ruleId} и
 * {@code originalDate}). Кнопка «Сбросить» удаляет уже существующую корректировку — событие снова идёт «как по
 * правилу». Результат — {@link Outcome}: новая корректировка или сброс.</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
// JavaFX: Dialog<Adjustment> + DialogPane + ButtonType("Сбросить") → Swing: SwingDialog<Outcome> + SwingDialogPane + SwingButtonType → Web: openDialog(id): Promise<R> поверх <dialog>
public final class AdjustmentDialog extends SwingDialog<AdjustmentDialog.Outcome> {

    /** «Сбросить» — удалить корректировку. */
    public static final SwingButtonType RESET = new SwingButtonType("Сбросить", SwingButtonType.Role.OTHER);

    /**
     * Результат диалога.
     *
     * @param key        событие
     * @param adjustment новая корректировка или {@code null}, если корректировку нужно удалить
     */
    public record Outcome(OccurrenceKey key, Adjustment adjustment) {

        /**
         * Нужно ли удалить корректировку.
         *
         * @return {@code true} для «Сбросить»
         */
        public boolean isReset() {
            return adjustment == null;
        }
    }

    private final Plan plan;
    private final OccurrenceKey key;
    private final Adjustment existing;

    private final JComboBox<RuFormats.ActionType> action =
            Combos.choice(AdjustmentDialog::actionTitle, RuFormats.ActionType.CHANGE_AMOUNT, RuFormats.ActionType.values());
    private final MoneyField amount = new MoneyField();
    private final DateField date = new DateField();
    private final JTextField note = new JTextField(24);

    /**
     * Создаёт диалог.
     *
     * @param owner        окно-владелец
     * @param ownerId      идентификатор владельца для снимка
     * @param recorder     рекордер сессии или {@code null}
     * @param plan         текущий план
     * @param ruleId       правило
     * @param originalDate номинальная дата события по графику правила
     */
    public AdjustmentDialog(Window owner, String ownerId, SessionRecorder recorder, Plan plan, RuleId ruleId, LocalDate originalDate) {
        super(owner, ownerId, WindowType.ADJUSTMENT_EDITOR, true, "Корректировка события", recorder);
        this.plan = plan;
        this.key = new OccurrenceKey(ruleId, originalDate);
        this.existing = plan.findAdjustment(key).orElse(null);
        putContext(WindowType.CONTEXT_RULE_ID, ruleId.value());
        putContext(WindowType.CONTEXT_ORIGINAL_DATE, DateFormats.iso(originalDate));

        Optional<RecurringRule> rule = plan.findRule(ruleId);
        LocalDate scheduled = rule.map(r -> r.weekendPolicy().apply(originalDate)).orElse(originalDate);
        if (existing != null) {
            action.setSelectedItem(RuFormats.actionTypeOf(existing.action()));
            amount.setValue(existing.action().newAmount().orElse(rule.map(RecurringRule::amount).orElse(null)));
            date.setValue(existing.action().newDate().orElse(scheduled));
            note.setText(existing.note());
        } else {
            amount.setValue(rule.map(RecurringRule::amount).orElse(null));
            date.setValue(scheduled);
        }

        FormPanel form = new FormPanel();
        form.addRow("Действие", action);
        form.addRow("Новая сумма", amount);
        form.addRow("Новая дата", date);
        form.addRow("Заметка", note);
        pane().setHeaderText(header(rule, originalDate, scheduled));
        pane().setContent(form);

        binder().bindEnumCombo("action", action, RuFormats.ActionType.class);
        binder().bindMoney("amount", amount);
        binder().bindDate("date", date);
        binder().bindText("note", note);

        setValidator(this::formError);
        setWarningSupplier(this::formWarning);
        setButtonHandler(button -> {
            if (button.equals(RESET)) {
                if (existing != null) {
                    close(new Outcome(key, null));
                }
                return true;
            }
            return false;
        });
        setResultConverter(button -> button.isDefaultButton() ? build() : null);
        setButtonTypes(RESET, SwingButtonType.OK, SwingButtonType.CANCEL);
        setInitialFocus(action);
    }

    private String header(Optional<RecurringRule> rule, LocalDate originalDate, LocalDate scheduled) {
        if (rule.isEmpty()) {
            return "Правило «" + key.ruleId().value() + "» не найдено в плане. Корректировка сохранится, "
                    + "но на прогноз не повлияет.";
        }
        RecurringRule r = rule.get();
        StringBuilder text = new StringBuilder("Событие «").append(r.title()).append("» (")
                .append(r.kind().label()).append(" ").append(r.amount().format(plan.currency()))
                .append(") по графику ").append(DateFormats.ru(originalDate));
        if (!scheduled.equals(originalDate)) {
            text.append(", с учётом выходных ").append(DateFormats.ru(scheduled));
        }
        text.append('.');
        if (existing != null) {
            text.append("\nУже задано: ").append(RuFormats.formatAction(existing.action()))
                    .append(". «Сбросить» вернёт событие к правилу.");
        }
        return text.toString();
    }

    private static String actionTitle(RuFormats.ActionType type) {
        return switch (type) {
            case SKIP -> "Пропустить событие";
            case CHANGE_AMOUNT -> "Изменить сумму";
            case MOVE_DATE -> "Перенести на другую дату";
            case REPLACE -> "Заменить сумму и дату";
        };
    }

    /** {@inheritDoc} */
    @Override
    protected boolean isButtonEnabled(SwingButtonType button, String error) {
        if (button.equals(RESET)) {
            return existing != null;
        }
        return super.isButtonEnabled(button, error);
    }

    /** {@inheritDoc} */
    @Override
    protected void onFieldsChanged() {
        RuFormats.ActionType type = Combos.selected(action);
        amount.setEnabled(type != null && type.requiresAmount());
        date.setEnabled(type != null && type.requiresDate());
    }

    private String formError() {
        RuFormats.ActionType type = Combos.selected(action);
        if (type == null) {
            return "Выберите действие";
        }
        if (type.requiresAmount()) {
            String error = amount.validationError("Новая сумма", true, true);
            if (error != null) {
                return error;
            }
        }
        if (type.requiresDate()) {
            return date.validationError("Новая дата", true);
        }
        return null;
    }

    private String formWarning() {
        RuFormats.ActionType type = Combos.selected(action);
        if (type != null && type.requiresDate()) {
            Optional<LocalDate> newDate = date.value();
            if (newDate.isPresent() && (newDate.get().isBefore(plan.startDate()) || newDate.get().isAfter(plan.endDate()))) {
                return "Новая дата вне горизонта прогноза: событие выпадет из прогноза";
            }
        }
        return null;
    }

    private Outcome build() {
        String error = formError();
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
        RuFormats.ActionType type = Combos.selected(action);
        Money newAmount = type.requiresAmount() ? amount.value().orElseThrow() : null;
        LocalDate newDate = type.requiresDate() ? date.value().orElseThrow() : null;
        return new Outcome(key, new Adjustment(key, RuFormats.buildAction(type, newAmount, newDate), note.getText()));
    }
}
