package ru.cashprediction.fx.dialog;

import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import ru.cashprediction.core.document.DocumentEvent;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.ForecastEngine;
import ru.cashprediction.core.forecast.GoalCalculator;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.model.Goal;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.util.DateFormats;
import ru.cashprediction.core.util.RuText;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Диалог 6 «Калькулятор цели» — единственный немодальный диалог: с ним удобно смотреть на таблицу и график.
 *
 * <p>Отвечает на вопросы «когда накоплю N?», «сколько будет к дате?», «сколько откладывать дополнительно,
 * чтобы успеть к дате?» и «когда накоплю, если откладывать ещё X в месяц?». Считает по {@link GoalCalculator}
 * и пересчитывается сам при каждом изменении плана или вида (подписка на {@link PlanDocument}).</p>
 *
 * <p>Кнопки «Записать цель в план» и «Показать с доп. экономией» не закрывают окно. Результата у диалога нет.
 * Только FX Application Thread.</p>
 */
// JavaFX: Dialog<R> (немодальный) + DialogPane (AppDialogPane) + ButtonType → Swing: SwingDialog<R> (JDialog MODELESS) → Web: <dialog>.show() без showModal
public final class GoalCalculatorDialog extends FxStatefulDialog<Void> {

    private final PlanDocument document;
    private final Consumer<Goal> saveGoal;
    private final Consumer<Money> showWithSaving;
    private final Consumer<DocumentEvent> documentListener = e -> revalidate();

    private final TextField target;
    private final CheckBox byDateEnabled = new CheckBox("к дате");
    private final DatePicker byDate;
    private final TextField extraSaving = FxInputs.moneyField(null);
    private final Label nowLine = resultLabel();
    private final Label reachLine = resultLabel();
    private final Label byDateLine = resultLabel();
    private final Label requiredLine = resultLabel();
    private final Label extraLine = resultLabel();

    /**
     * Создаёт калькулятор.
     *
     * @param document       документ плана (калькулятор следит за его изменениями)
     * @param saveGoal       записать цель в план (через {@code PlanDocument.edit})
     * @param showWithSaving включить «что-если» с дополнительной ежемесячной экономией
     */
    public GoalCalculatorDialog(PlanDocument document, Consumer<Goal> saveGoal, Consumer<Money> showWithSaving) {
        super(WindowType.GOAL_CALCULATOR, new AppDialogPane("Когда я накоплю нужную сумму?", "◎"));
        this.document = document;
        this.saveGoal = saveGoal;
        this.showWithSaving = showWithSaving;
        Plan plan = document.plan();
        Goal goal = plan.goal();
        target = FxInputs.moneyField(goal == null ? null : goal.target());
        target.setPromptText("например, 300 000");
        LocalDate defaultDate = goal != null && goal.wishDate() != null ? goal.wishDate() : plan.endDate();
        byDate = FxInputs.datePicker(defaultDate);
        byDateEnabled.setSelected(goal != null && goal.wishDate() != null);
        byDate.disableProperty().bind(byDateEnabled.selectedProperty().not());
        extraSaving.setPromptText("0,00");
        HBox dateBox = new HBox(8, byDateEnabled, byDate);
        dateBox.setAlignment(Pos.CENTER_LEFT);

        Node results = new VBox(6, nowLine, reachLine, byDateLine, requiredLine, extraLine);
        appPane().setForm(new FormGrid()
                .row("Нужная сумма", target)
                .row("Срок", dateBox)
                .row("Откладывать ещё в месяц", extraSaving)
                .section("Результат")
                .wide(results));
        // Три кнопки с длинными подписями: ButtonBar делает их одинаковой ширины, и при стандартной ширине
        // панели (560) подпись «Показать с доп. экономией» обрезалась. Панель шире — подписи видны целиком.
        appPane().setPrefWidth(720);
        // JavaFX: ButtonType → Swing: SwingButtonType (текст + роль) + JOptionPane.showOptionDialog → Web: <button value> → returnValue
        appPane().getButtonTypes().setAll(AppButtonTypes.GOAL_TO_PLAN, AppButtonTypes.WHAT_IF_ON, AppButtonTypes.CLOSE);
        Node toPlan = appPane().lookupButton(AppButtonTypes.GOAL_TO_PLAN);
        Node whatIf = appPane().lookupButton(AppButtonTypes.WHAT_IF_ON);
        toPlan.disableProperty().bind(appPane().formValidProperty().not());
        // Кнопки действий не закрывают немодальный калькулятор: событие поглощается фильтром.
        toPlan.addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            readTarget().ifPresent(value -> this.saveGoal.accept(new Goal(goalTitle(), value,
                    byDateEnabled.isSelected() ? FxInputs.date(byDate).orElse(null) : null)));
        });
        whatIf.addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            FxInputs.money(extraSaving).filter(Money::isPositive).ifPresent(value -> {
                this.showWithSaving.accept(value);
                // Экономия уже включена в «что-если» и учтена в прогнозе: повторное нажатие не должно удвоить её.
                extraSaving.clear();
            });
        });
        whatIf.disableProperty().bind(extraSaving.textProperty().isEmpty());

        // Порядок привязки = порядок полей словаря WindowType.GOAL_CALCULATOR.
        binder().bindMoney("target", target);
        binder().bindCheck("byDateEnabled", byDateEnabled);
        binder().bindDate("byDate", byDate);
        binder().bindMoney("extraSaving", extraSaving);

        // Подписка на документ живёт ровно столько, сколько окно на экране: закрытое окно не должно пересчитываться.
        showingProperty().addListener((o, wasShowing, isShowing) -> {
            if (isShowing) {
                document.addListener(documentListener);
            } else {
                document.removeListener(documentListener);
            }
        });
        setResultConverter(button -> null);
        activate();
    }

    /** {@inheritDoc} */
    @Override
    protected void validateForm(List<String> errors, List<String> warnings) {
        clearResults();
        Optional<Money> goal = readTarget();
        if (FxInputs.isBlank(target)) {
            errors.add("Укажите нужную сумму");
            return;
        }
        if (goal.isEmpty()) {
            errors.add("Нужная сумма должна быть числом больше нуля");
            return;
        }
        Optional<Money> extra = FxInputs.money(extraSaving);
        if (!FxInputs.isBlank(extraSaving) && (extra.isEmpty() || extra.get().isNegative())) {
            errors.add("Дополнительная экономия должна быть неотрицательным числом");
            return;
        }
        Forecast forecast;
        try {
            forecast = document.forecast();
        } catch (IllegalStateException e) {
            errors.add(e.getMessage());
            return;
        }
        String currency = document.plan().currency();
        LocalDate anchor = forecast.anchor();
        nowLine.setText("Сейчас (" + DateFormats.ru(anchor) + "): " + GoalCalculator.balanceAt(forecast, anchor).format(currency));
        reachLine.setText(GoalCalculator.reachDate(forecast, goal.get())
                .map(date -> "Цель будет достигнута " + DateFormats.ru(date) + monthsFrom(anchor, date))
                .orElse("До конца горизонта (" + DateFormats.ru(forecast.endDate()) + ") цель не достигается"));

        if (byDateEnabled.isSelected()) {
            Optional<LocalDate> deadline = FxInputs.date(byDate);
            if (deadline.isEmpty()) {
                errors.add("Укажите дату срока (ДД.ММ.ГГГГ) или снимите флажок «к дате»");
                return;
            }
            byDateLine.setText("Баланс на " + DateFormats.ru(deadline.get()) + ": "
                    + GoalCalculator.balanceAt(forecast, deadline.get()).format(currency));
            requiredLine.setText(GoalCalculator.requiredExtraMonthly(forecast, goal.get(), deadline.get())
                    .map(sum -> sum.isZero()
                            ? "К этой дате цель достигается без дополнительной экономии"
                            : "Чтобы успеть, откладывайте дополнительно " + sum.format(currency) + " в месяц")
                    .orElse("Срок позже конца горизонта или до него нет ни одного конца месяца"));
            if (deadline.get().isAfter(forecast.endDate())) {
                warnings.add("Срок позже конца горизонта плана: баланс взят на последний день прогноза");
            }
        }

        if (extra.isPresent() && extra.get().isPositive()) {
            WhatIf base = document.viewState().whatIf();
            try {
                Forecast withSaving = ForecastEngine.forecast(document.plan(),
                        base.withExtraMonthlySaving(base.extraMonthlySaving().plus(extra.get())), document.today(), false);
                extraLine.setText(GoalCalculator.reachDate(withSaving, goal.get())
                        .map(date -> "Если откладывать ещё " + extra.get().format(currency) + " в месяц — цель "
                                + DateFormats.ru(date) + monthsFrom(anchor, date))
                        .orElse("Даже с доп. экономией " + extra.get().format(currency) + " цель в горизонте не достигается"));
            } catch (RuntimeException e) {
                warnings.add(e.getMessage());
            }
        }
    }

    private Optional<Money> readTarget() {
        return FxInputs.money(target).filter(Money::isPositive);
    }

    private String goalTitle() {
        Goal goal = document.plan().goal();
        return goal != null && !goal.title().isBlank() ? goal.title() : "Цель";
    }

    private void clearResults() {
        for (Label label : List.of(nowLine, reachLine, byDateLine, requiredLine, extraLine)) {
            label.setText("");
        }
    }

    private static String monthsFrom(LocalDate anchor, LocalDate date) {
        long months = ChronoUnit.MONTHS.between(anchor, date);
        return months <= 0 ? "" : " (через " + RuText.count(months, "месяц", "месяца", "месяцев") + ")";
    }

    private static Label resultLabel() {
        Label label = new Label();
        label.setWrapText(true);
        label.managedProperty().bind(label.textProperty().isNotEmpty());
        label.visibleProperty().bind(label.textProperty().isNotEmpty());
        return label;
    }
}
