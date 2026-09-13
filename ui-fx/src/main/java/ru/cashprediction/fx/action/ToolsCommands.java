package ru.cashprediction.fx.action;

import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import ru.cashprediction.core.diagnostics.Diagnostic;
import ru.cashprediction.core.diagnostics.PlanValidator;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.forecast.Warning;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.model.Goal;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.util.RuText;
import ru.cashprediction.fx.dialog.AppButtonTypes;
import ru.cashprediction.fx.dialog.Dialogs;
import ru.cashprediction.fx.dialog.FxRestorableDialog;
import ru.cashprediction.fx.dialog.GoalCalculatorDialog;
import ru.cashprediction.fx.dialog.NewPlanWizard;
import ru.cashprediction.fx.dialog.OpenRequest;
import ru.cashprediction.fx.dialog.StatefulAlert;
import ru.cashprediction.fx.dialog.StatefulChoiceDialog;
import ru.cashprediction.fx.dialog.StatefulTextInputDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Команды меню «Инструменты»: калькулятор цели, режим «что-если», проверка плана, очистка неиспользуемых
 * корректировок, валюта.
 *
 * <p>Только FX Application Thread.</p>
 */
final class ToolsCommands {

    /** Пункт списка валют, открывающий ввод своей валюты. */
    static final String OTHER_CURRENCY = "другая…";

    private final CommandSupport support;

    ToolsCommands(CommandSupport support) {
        this.support = support;
    }

    /**
     * Диалог 6 «Калькулятор цели» — немодальный. Если он уже открыт, окно просто выводится на передний план.
     *
     * @param request запрос открытия
     */
    void goalCalculator(OpenRequest request) {
        if (!request.isRestore()) {
            for (var window : support.context().recorder().registeredWindows()) {
                if (window.windowType() == WindowType.GOAL_CALCULATOR && window instanceof FxRestorableDialog dialog
                        && dialog.dialogWindow() != null) {
                    dialog.dialogWindow().requestFocus();
                    return;
                }
            }
        }
        PlanDocument document = support.document();
        // JavaFX: Dialog<R> (Modality.NONE) → Swing: SwingDialog<R> (JDialog MODELESS) → Web: <dialog>.show() без showModal
        GoalCalculatorDialog dialog = new GoalCalculatorDialog(document,
                goal -> support.edit("Цель «" + goal.title() + "»", p -> p.withGoal(goalWithTitle(p.goal(), goal))),
                extra -> {
                    WhatIf current = document.viewState().whatIf();
                    document.setViewState(document.viewState().withWhatIf(
                            current.withExtraMonthlySaving(current.extraMonthlySaving().plus(extra))));
                });
        support.host().open(dialog, request, result -> { });
    }

    private static Goal goalWithTitle(Goal old, Goal fresh) {
        // Название цели калькулятор не редактирует: сохраняем прежнее, если оно было.
        String title = old != null && !old.title().isBlank() ? old.title() : fresh.title();
        return new Goal(title, fresh.target(), fresh.wishDate());
    }

    /**
     * «Применить что-если к плану»: подтверждение, затем {@code PlanDocument.applyWhatIfToPlan}.
     *
     * @param request запрос открытия
     */
    void applyWhatIf(OpenRequest request) {
        WhatIf whatIf = support.document().viewState().whatIf();
        if (whatIf.isNone()) {
            support.cannotOpen(request, "Режим «что-если» выключен",
                    "Включите «Доходы −10 %», «Расходы +10 %» или доп. экономию в меню Инструменты → Что-если.");
            return;
        }
        // JavaFX: Alert → Swing: JOptionPane.showConfirmDialog → Web: <dialog class="alert">
        StatefulAlert alert = new StatefulAlert(AlertType.CONFIRMATION, "applyWhatIf", null, "Что-если",
                "Перенести параметры «что-если» в сам план?",
                "Суммы операций будут умножены на коэффициенты, доп. экономия станет регулярным доходом «Доп. экономия».\n"
                        + "Режим «что-если» выключится. Действие можно отменить (Ctrl+Z).",
                AppButtonTypes.APPLY, AppButtonTypes.CANCEL);
        support.host().open(alert, request, result -> {
            if (result.orElse(AppButtonTypes.CANCEL) == AppButtonTypes.APPLY) {
                try {
                    support.document().applyWhatIfToPlan();
                } catch (IllegalArgumentException | IllegalStateException e) {
                    support.error("Не удалось применить «что-если»", e.getMessage());
                }
            }
        });
    }

    /** «Сбросить» режим «что-если». */
    void resetWhatIf() {
        PlanDocument document = support.document();
        document.setViewState(document.viewState().withWhatIf(WhatIf.NONE));
    }

    /** Диалог 15 «Диагностика» по команде «Проверить план». */
    void validatePlan() {
        List<String> lines = new ArrayList<>();
        int problems = 0;
        for (Diagnostic d : PlanValidator.validate(support.plan())) {
            lines.add("План: " + d.format());
            problems++;
        }
        try {
            for (Warning w : support.document().forecast().warnings()) {
                lines.add("Прогноз: " + w.format());
                problems++;
            }
        } catch (IllegalStateException e) {
            lines.add("Прогноз не рассчитан: " + e.getMessage());
            problems++;
        }
        for (Diagnostic d : support.document().loadDiagnostics()) {
            lines.add("Файл: " + d.format());
        }
        if (problems == 0 && lines.isEmpty()) {
            support.info("Замечаний нет", "План корректен, прогноз рассчитан без предупреждений.");
            return;
        }
        // JavaFX: Alert → Swing: JOptionPane.showMessageDialog(WARNING_MESSAGE) → Web: <dialog class="alert">
        Alert alert = Dialogs.withDetails(problems == 0 ? AlertType.INFORMATION : AlertType.WARNING, "Диагностика",
                problems == 0 ? "Замечаний к плану нет" : "Найдено замечаний: " + problems,
                "Подробности — в раскрываемой области ниже.", String.join("\n", lines));
        // JavaFX: DialogPane → Swing: SwingDialogPane (JPanel header/content/кнопки) → Web: <dialog><form method="dialog">
        alert.getDialogPane().setExpanded(true);
        support.host().show(alert, WindowState.MAIN_OWNER, r -> { });
    }

    /** «Очистить неиспользуемые корректировки». */
    void removeOrphanAdjustments() {
        int removed;
        try {
            removed = support.document().removeOrphanAdjustments();
        } catch (IllegalStateException e) {
            support.error("Не удалось проверить корректировки", e.getMessage());
            return;
        }
        support.info(removed == 0 ? "Неиспользуемых корректировок нет" : "Удалено: "
                        + RuText.count(removed, "корректировка", "корректировки", "корректировок"),
                removed == 0 ? "Все корректировки относятся к существующим событиям правил."
                        : "Действие можно отменить: Правка → Отменить (Ctrl+Z).");
    }

    /**
     * Диалог 8 «Валюта» ({@code ChoiceDialog}); «другая…» открывает ввод своей валюты.
     *
     * @param request запрос открытия
     */
    void currency(OpenRequest request) {
        List<String> items = new ArrayList<>(NewPlanWizard.CURRENCIES);
        items.add(OTHER_CURRENCY);
        String current = support.plan().currency();
        // JavaFX: ChoiceDialog<T> → Swing: JOptionPane.showInputDialog(..., selectionValues[], initial) → Web: <dialog> с <select>
        StatefulChoiceDialog<String> dialog = new StatefulChoiceDialog<>("currency", "Валюта плана",
                "Валюта плана «" + support.plan().name() + "» (сейчас: " + current + ")", "Валюта:",
                items.contains(current) ? current : OTHER_CURRENCY, items, AppButtonTypes.CHOOSE, s -> s,
                s -> items.contains(s) ? s : null);
        support.host().open(dialog, request, result -> result.ifPresent(choice -> {
            if (OTHER_CURRENCY.equals(choice)) {
                customCurrency(OpenRequest.fromMain());
            } else {
                applyCurrency(choice);
            }
        }));
    }

    /**
     * Ввод своей валюты ({@code TextInputDialog}, назначение {@code customCurrency}).
     *
     * @param request запрос открытия
     */
    void customCurrency(OpenRequest request) {
        // JavaFX: TextInputDialog → Swing: JOptionPane.showInputDialog → Web: <dialog> с <input>
        StatefulTextInputDialog dialog = new StatefulTextInputDialog("customCurrency", "Своя валюта",
                "Обозначение валюты, например «CNY» или «₺»", "Валюта:", support.plan().currency(), AppButtonTypes.APPLY,
                ToolsCommands::currencyProblem, false);
        support.host().open(dialog, request, result -> result.map(String::strip).ifPresent(this::applyCurrency));
    }

    private static Optional<String> currencyProblem(String text) {
        String value = text.strip();
        if (value.isEmpty()) {
            return Optional.of("Введите обозначение валюты");
        }
        if (value.length() > 10) {
            return Optional.of("Не длиннее 10 символов");
        }
        if (value.contains("|") || value.contains("\n")) {
            return Optional.of("Символ «|» и перевод строки недопустимы");
        }
        return Optional.empty();
    }

    private void applyCurrency(String currency) {
        if (!currency.equals(support.plan().currency())) {
            support.edit("Валюта: " + currency, p -> p.withCurrency(currency));
        }
    }

    /**
     * Нужна ли дополнительная экономия больше нуля.
     *
     * @param amount сумма
     * @return {@code true}, если сумма больше нуля
     */
    static boolean positive(Money amount) {
        return amount != null && amount.isPositive();
    }
}
