package ru.cashprediction.swing.dialog;

import java.awt.Window;
import java.time.LocalDate;
import java.util.Optional;
import java.util.function.Consumer;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.ForecastEngine;
import ru.cashprediction.core.forecast.GoalCalculator;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.model.Goal;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.session.SessionRecorder;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.util.DateFormats;

/**
 * Диалог 6 «Калькулятор цели» — <b>немодальный</b>: можно менять план, не закрывая калькулятор, и ответы
 * пересчитываются по каждому изменению документа.
 *
 * <p>Отвечает на три вопроса ({@link GoalCalculator}): когда баланс достигнет цели при текущем плане; сколько
 * откладывать дополнительно в месяц, чтобы успеть к дате; когда цель будет достигнута, если откладывать ещё
 * заданную сумму в месяц (прогноз с «что-если»). Кнопка «Сохранить как цель плана» записывает цель в план.</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
// JavaFX: Dialog<R> (немодальный, initModality NONE) → Swing: SwingDialog<Void> (JDialog MODELESS) → Web: <dialog> через show() без showModal()
public final class GoalCalculatorDialog extends SwingDialog<Void> {

    /** «Сохранить как цель плана». */
    public static final SwingButtonType SAVE_AS_GOAL = new SwingButtonType("Сохранить как цель плана", SwingButtonType.Role.OTHER);

    private final PlanDocument document;

    private final MoneyField target = new MoneyField();
    private final JCheckBox byDateEnabled = new JCheckBox("успеть к дате");
    private final DateField byDate = new DateField();
    private final MoneyField extraSaving = new MoneyField();
    private final JLabel reachLabel = new JLabel(" ");
    private final JLabel requiredLabel = new JLabel(" ");
    private final JLabel extraLabel = new JLabel(" ");

    /**
     * Создаёт калькулятор.
     *
     * @param owner      главное окно
     * @param recorder   рекордер сессии или {@code null}
     * @param document   документ плана (прогноз и изменения)
     * @param onSaveGoal получатель цели для записи в план
     */
    public GoalCalculatorDialog(Window owner, SessionRecorder recorder, PlanDocument document, Consumer<Goal> onSaveGoal) {
        super(owner, WindowState.MAIN_OWNER, WindowType.GOAL_CALCULATOR, false, "Калькулятор цели", recorder);
        this.document = document;

        Forecast forecast = document.forecast();
        byDate.setValue(forecast.anchor().plusMonths(12));
        document.plan().goalOptional().ifPresent(goal -> {
            target.setValue(goal.target());
            if (goal.wishDate() != null) {
                byDateEnabled.setSelected(true);
                byDate.setValue(goal.wishDate());
            }
        });

        FormPanel form = new FormPanel();
        form.addSection("Цель");
        form.addRow("Сумма цели", target);
        form.addRow("Срок", byDateEnabled, byDate);
        form.addRow("Откладывать ещё в месяц", extraSaving);
        form.addSection("Ответ");
        form.addWide(reachLabel, false);
        form.addWide(requiredLabel, false);
        form.addWide(extraLabel, false);
        pane().setHeaderText("Сколько и когда удастся накопить. Расчёт идёт от сегодняшнего дня (или от начала плана) "
                + "и обновляется при изменении плана.");
        pane().setContent(form);

        binder().bindMoney("target", target);
        binder().bindCheckBox("byDateEnabled", byDateEnabled);
        binder().bindDate("byDate", byDate);
        binder().bindMoney("extraSaving", extraSaving);

        // Немодальное окно живёт рядом с главным: при любом изменении плана или вида ответ пересчитывается.
        Consumer<ru.cashprediction.core.document.DocumentEvent> listener = event -> {
            if (!isClosed()) {
                refreshAnswers();
            }
        };
        document.addListener(listener);
        addCloseAction(() -> document.removeListener(listener));

        setValidator(this::formError);
        setButtonHandler(button -> {
            if (button.equals(SAVE_AS_GOAL)) {
                String title = document.plan().goalOptional().map(Goal::title).orElse("");
                target.value().ifPresent(money -> onSaveGoal.accept(new Goal(title, money,
                        byDateEnabled.isSelected() ? byDate.value().orElse(null) : null)));
                return true;
            }
            return false;
        });
        setButtonTypes(SAVE_AS_GOAL, SwingButtonType.CLOSE);
        setInitialFocus(target);
    }

    /** {@inheritDoc} */
    @Override
    protected boolean isButtonEnabled(SwingButtonType button, String error) {
        return !button.equals(SAVE_AS_GOAL) || error == null;
    }

    /** {@inheritDoc} */
    @Override
    protected void onFieldsChanged() {
        byDate.setEnabled(byDateEnabled.isSelected());
        refreshAnswers();
    }

    private void refreshAnswers() {
        Optional<Money> goal = target.value().filter(Money::isPositive);
        if (goal.isEmpty()) {
            reachLabel.setText("Введите сумму цели.");
            requiredLabel.setText(" ");
            extraLabel.setText(" ");
            return;
        }
        Forecast forecast = document.forecast();
        String currency = document.plan().currency();
        Money money = goal.get();

        reachLabel.setText(SwingText.htmlWrapped(GoalCalculator.reachDate(forecast, money)
                .map(date -> "С текущим планом цель достигается " + DateFormats.ru(date) + ".")
                .orElse("С текущим планом цель не достигается до конца горизонта (" + DateFormats.ru(forecast.endDate())
                        + "): к этому дню будет " + forecast.endBalance().format(currency) + "."), 420));

        Optional<LocalDate> deadline = byDateEnabled.isSelected() ? byDate.value() : Optional.empty();
        if (deadline.isPresent()) {
            LocalDate d = deadline.get();
            Money balance = GoalCalculator.balanceAt(forecast, d);
            String text = GoalCalculator.requiredExtraMonthly(forecast, money, d)
                    .map(extra -> extra.isZero()
                            ? "К " + DateFormats.ru(d) + " будет " + balance.format(currency) + " — цель достигается без дополнительной экономии."
                            : "Чтобы успеть к " + DateFormats.ru(d) + ", откладывайте дополнительно " + extra.format(currency)
                            + " в месяц (без этого к дате будет " + balance.format(currency) + ").")
                    .orElse("К " + DateFormats.ru(d) + " рассчитать нельзя: дата позже конца горизонта или до неё нет ни одного конца месяца.");
            requiredLabel.setText(SwingText.htmlWrapped(text, 420));
        } else {
            requiredLabel.setText(" ");
        }

        Optional<Money> extra = extraSaving.value().filter(Money::isPositive);
        if (extra.isPresent()) {
            WhatIf whatIf = forecast.whatIf().withExtraMonthlySaving(forecast.whatIf().extraMonthlySaving().plus(extra.get()));
            try {
                Forecast withSaving = ForecastEngine.forecast(document.plan(), whatIf, document.today(),
                        document.viewState().showSkipped());
                String text = GoalCalculator.reachDate(withSaving, money)
                        .map(date -> "Если откладывать ещё " + extra.get().format(currency) + " в месяц, цель будет достигнута "
                                + DateFormats.ru(date) + ".")
                        .orElse("Даже с дополнительными " + extra.get().format(currency) + " в месяц цель не достигается до конца горизонта.");
                extraLabel.setText(SwingText.htmlWrapped(text, 420));
            } catch (RuntimeException e) {
                extraLabel.setText(SwingText.htmlWrapped("Не удалось рассчитать: " + e.getMessage(), 420));
            }
        } else {
            extraLabel.setText(" ");
        }
    }

    private String formError() {
        String error = target.validationError("Сумма цели", true, true);
        if (error == null) {
            error = extraSaving.validationError("Откладывать ещё в месяц", false, false);
        }
        if (error == null && extraSaving.value().filter(Money::isNegative).isPresent()) {
            error = "Дополнительная экономия не может быть отрицательной";
        }
        if (error == null && byDateEnabled.isSelected()) {
            error = byDate.validationError("Срок", true);
        }
        return error;
    }
}
