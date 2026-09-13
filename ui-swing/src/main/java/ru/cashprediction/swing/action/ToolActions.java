package ru.cashprediction.swing.action;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import ru.cashprediction.core.diagnostics.Diagnostic;
import ru.cashprediction.core.diagnostics.PlanValidator;
import ru.cashprediction.core.forecast.Warning;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.util.RuText;
import ru.cashprediction.swing.dialog.Combos;
import ru.cashprediction.swing.dialog.GoalCalculatorDialog;
import ru.cashprediction.swing.dialog.SwingAlert;
import ru.cashprediction.swing.dialog.SwingChoiceDialog;
import ru.cashprediction.swing.dialog.SwingHostedWindow;
import ru.cashprediction.swing.dialog.SwingTextInputDialog;

/**
 * Команды меню «Инструменты»: калькулятор цели, «что-если», проверка плана, очистка корректировок-сирот, валюта.
 *
 * <p>«Что-если» меняет только вид ({@code ViewState.whatIf}), а не план: доходы −10 %, расходы +10 % и
 * дополнительная ежемесячная экономия. «Применить к плану…» переносит гипотезу в данные через
 * {@code PlanDocument.applyWhatIfToPlan()}.</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
final class ToolActions extends ActionSupport {

    /** Множитель доходов в «что-если»: минус 10 %. */
    public static final BigDecimal INCOME_FACTOR = new BigDecimal("0.90");
    /** Множитель расходов в «что-если»: плюс 10 %. */
    public static final BigDecimal EXPENSE_FACTOR = new BigDecimal("1.10");
    /** Пункт списка валют «другая…». */
    public static final String OTHER_CURRENCY = "другая…";

    /**
     * Создаёт команды инструментов.
     *
     * @param shared общие объекты команд
     */
    public ToolActions(ActionShared shared) {
        super(shared);
    }

    // ------------------------------------------------------------------ калькулятор цели

    /**
     * «Калькулятор цели…» (Ctrl+G).
     */
    public void goalCalculatorCommand() {
        goalCalculator(interactive());
    }

    /**
     * Диалог 6 «Калькулятор цели» — немодальный и единственный: повторная команда поднимает открытое окно.
     *
     * @param request как открыть окно
     */
    public void goalCalculator(OpenRequest request) {
        Optional<SwingHostedWindow> open = host().findOpen(WindowType.GOAL_CALCULATOR);
        if (open.isPresent() && !request.isRestore()) {
            open.get().window().toFront();
            request.onShown().accept(open.get());
            return;
        }
        GoalCalculatorDialog dialog;
        try {
            dialog = new GoalCalculatorDialog(frame(), recorder(), document(),
                    goal -> edit("Цель плана", p -> p.withGoal(goal)));
        } catch (IllegalStateException e) {
            // Калькулятору нужен прогноз; при слишком длинном горизонте его нет.
            cannotOpen(request, "Прогноз не рассчитан", Objects.requireNonNullElse(e.getMessage(), e.toString()));
            return;
        }
        show(dialog, request);
    }

    // ------------------------------------------------------------------ что-если

    /**
     * Включено ли «доходы −10 %».
     *
     * @return {@code true}, если множитель доходов не равен единице
     */
    public boolean isIncomeWhatIf() {
        return document().viewState().whatIf().incomeFactor().compareTo(BigDecimal.ONE) != 0;
    }

    /**
     * Включено ли «расходы +10 %».
     *
     * @return {@code true}, если множитель расходов не равен единице
     */
    public boolean isExpenseWhatIf() {
        return document().viewState().whatIf().expenseFactor().compareTo(BigDecimal.ONE) != 0;
    }

    /**
     * Переключает «Доходы −10 %».
     *
     * @param on включить
     */
    public void setIncomeWhatIf(boolean on) {
        updateView(v -> v.withWhatIf(v.whatIf().withIncomeFactor(on ? INCOME_FACTOR : BigDecimal.ONE)));
    }

    /**
     * Переключает «Расходы +10 %».
     *
     * @param on включить
     */
    public void setExpenseWhatIf(boolean on) {
        updateView(v -> v.withWhatIf(v.whatIf().withExpenseFactor(on ? EXPENSE_FACTOR : BigDecimal.ONE)));
    }

    /**
     * «Откладывать доп.»: дополнительная ежемесячная экономия.
     *
     * @param saving сумма в месяц (0 — выключено)
     */
    public void setExtraSaving(Money saving) {
        Money value = saving == null || saving.isNegative() ? Money.ZERO : saving;
        updateView(v -> v.withWhatIf(v.whatIf().withExtraMonthlySaving(value)));
    }

    /**
     * «Что-если → Сбросить».
     */
    public void resetWhatIf() {
        updateView(v -> v.withWhatIf(WhatIf.NONE));
    }

    /**
     * «Что-если → Применить к плану…»: подтверждение ({@code Alert(CONFIRMATION)}, восстанавливается как окно
     * {@code ALERT} с назначением {@code applyWhatIf}) и перенос гипотезы в план через
     * {@code PlanDocument.applyWhatIfToPlan()}.
     *
     * @param request как открыть окно
     */
    public void applyWhatIf(OpenRequest request) {
        if (document().viewState().whatIf().isNone()) {
            cannotOpen(request, "«Что-если» выключено",
                    "Включите изменение доходов, расходов или дополнительную экономию, чтобы применить их к плану.");
            return;
        }
        // JavaFX: Alert(CONFIRMATION) + ButtonType("Применить", OK_DONE) → Swing: SwingAlert + SwingButtonType → Web: <dialog class="alert">
        SwingAlert alert = new SwingAlert(owner(request), SwingAlert.AlertType.CONFIRMATION, "Что-если",
                "Применить «что-если» к плану?",
                "Суммы правил и корректировок будут пересчитаны, дополнительная экономия станет регулярной операцией.\n"
                        + "Режим «что-если» выключится. Действие можно отменить (Ctrl+Z).",
                AppButtons.APPLY, AppButtons.CANCEL);
        alert.makeRestorable(request.ownerId(), Purposes.APPLY_WHAT_IF, "");
        alert.setOnResult(result -> {
            if (result.filter(AppButtons.APPLY::equals).isEmpty()) {
                return;
            }
            try {
                document().applyWhatIfToPlan();
                // Гипотеза стала данными: второй раз применять её к уже пересчитанным суммам нельзя.
                resetWhatIf();
            } catch (IllegalArgumentException | IllegalStateException e) {
                // Редкий случай округления в ядре: план не меняется, пользователь видит причину.
                alerts().error("Не удалось применить «что-если»", Objects.requireNonNullElse(e.getMessage(), e.toString()));
            }
        });
        show(alert, request);
    }

    // ------------------------------------------------------------------ проверка плана

    /**
     * «Проверить план»: диалог 15 «Диагностика» ({@code Alert(WARNING)} со списком в «Подробнее»).
     */
    public void validatePlanCommand() {
        List<String> lines = new ArrayList<>();
        for (Diagnostic d : PlanValidator.validate(plan())) {
            lines.add("План: " + d.format());
        }
        for (Diagnostic d : document().loadDiagnostics()) {
            lines.add("Файл: " + d.format());
        }
        try {
            for (Warning w : document().forecast().warnings()) {
                lines.add("Прогноз: " + w.format());
            }
        } catch (IllegalStateException e) {
            lines.add("Прогноз не рассчитан: " + e.getMessage());
        }
        if (lines.isEmpty()) {
            alerts().info("Проблем не найдено", "План проверен: ошибок и предупреждений нет.");
            return;
        }
        alerts().warning("Найдено замечаний: " + lines.size(),
                "Проверка плана, замечания чтения файла и предупреждения прогноза — в подробностях.",
                String.join("\n", lines));
    }

    /**
     * «Очистить неиспользуемые корректировки»: удаляет корректировки-сироты.
     */
    public void removeOrphansCommand() {
        int removed = document().removeOrphanAdjustments();
        if (removed == 0) {
            alerts().info("Неиспользуемых корректировок нет", "Все корректировки относятся к существующим событиям правил.");
        } else {
            alerts().info("Корректировки очищены", "Удалено: " + RuText.count(removed, "корректировка", "корректировки", "корректировок")
                    + ". Действие можно отменить (Ctrl+Z).");
        }
    }

    // ------------------------------------------------------------------ валюта

    /**
     * «Валюта…».
     */
    public void currencyCommand() {
        currency(interactive());
    }

    /**
     * Диалог 8 «Валюта» ({@code ChoiceDialog}); «другая…» открывает ввод своей валюты.
     *
     * @param request как открыть окно
     */
    public void currency(OpenRequest request) {
        List<String> items = new ArrayList<>(Combos.CURRENCIES);
        items.add(OTHER_CURRENCY);
        String current = plan().currency();
        // JavaFX: ChoiceDialog<T> → Swing: SwingChoiceDialog<T> → Web: <dialog> с <select>
        SwingChoiceDialog<String> dialog = new SwingChoiceDialog<>(owner(request), request.ownerId(), recorder(),
                Purposes.CURRENCY, "Валюта", "Валюта плана «" + plan().name() + "». Сейчас: " + current,
                "Валюта", items, items.contains(current) ? current : OTHER_CURRENCY, s -> s, s -> s);
        dialog.setOnResult(result -> result.ifPresent(choice -> {
            if (OTHER_CURRENCY.equals(choice)) {
                customCurrency(interactive());
            } else {
                applyCurrency(choice);
            }
        }));
        show(dialog, request);
    }

    /**
     * Ввод своей валюты ({@code TextInputDialog}, назначение {@code customCurrency}).
     *
     * @param request как открыть окно
     */
    public void customCurrency(OpenRequest request) {
        SwingTextInputDialog dialog = new SwingTextInputDialog(owner(request), request.ownerId(), recorder(),
                Purposes.CUSTOM_CURRENCY, false, "Своя валюта", "Обозначение валюты, например «CNY» или «£».",
                "Валюта", plan().currency(), text -> text == null || text.isBlank() ? "Укажите обозначение валюты"
                : text.strip().length() > 12 ? "Не длиннее 12 символов" : null);
        dialog.setOnResult(result -> result.map(String::strip).ifPresent(this::applyCurrency));
        show(dialog, request);
    }

    private void applyCurrency(String currency) {
        if (!currency.equals(plan().currency())) {
            edit("Валюта: " + currency, p -> p.withCurrency(currency));
        }
    }
}
