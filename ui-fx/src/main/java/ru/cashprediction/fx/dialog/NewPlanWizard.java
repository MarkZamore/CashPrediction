package ru.cashprediction.fx.dialog;

import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import ru.cashprediction.core.diagnostics.PlanValidator;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.WeekendPolicy;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Диалог 1 «Новый план»: мастер из трёх страниц.
 *
 * <ol>
 *   <li>Название и валюта.</li>
 *   <li>Дата начала, начальный баланс, горизонт, подушка безопасности.</li>
 *   <li>Необязательные быстрые операции: ежемесячный доход и ежемесячный расход.</li>
 * </ol>
 *
 * <p>Кнопки «Назад»/«Далее» не закрывают диалог: их нажатие перехватывается фильтром события и
 * переключает страницу. «Готово» доступно, когда все страницы заполнены без ошибок. Номер страницы
 * хранится в контексте окна ({@code page}), поэтому после сбоя мастер открывается на той же странице.</p>
 *
 * <p>Результат — готовый {@link Plan}. Только FX Application Thread.</p>
 */
// JavaFX: Dialog<R> + DialogPane (AppDialogPane) + ButtonType «Назад/Далее/Готово» → Swing: SwingDialog<R> (NewPlanWizard, CardLayout) → Web: openDialog('newPlan') со страницами
public final class NewPlanWizard extends FxStatefulDialog<Plan> {

    /** Валюты, предлагаемые в списке (можно ввести свою). */
    public static final List<String> CURRENCIES = List.of("₽", "$", "€", "₸", "BYN");

    private static final String[] PAGE_TITLES = {
        "Шаг 1 из 3. Название плана и валюта",
        "Шаг 2 из 3. С чего начинаем и на сколько вперёд считаем",
        "Шаг 3 из 3. Регулярные доход и расход (можно пропустить)"
    };

    private final Predicate<String> nameTaken;

    private final TextField name = new TextField();
    private final ComboBox<String> currency = new ComboBox<>();
    private final DatePicker startDate;
    private final TextField startBalance = FxInputs.moneyField(Money.ZERO);
    private final HorizonEditor horizon = new HorizonEditor(new Horizon.Months(12));
    private final TextField cushion = FxInputs.moneyField(Money.ZERO);
    private final TextField quickIncomeTitle = new TextField("Зарплата");
    private final TextField quickIncomeAmount = FxInputs.moneyField(null);
    private final Spinner<Integer> quickIncomeDay = FxInputs.intSpinner(1, 31, 5);
    private final TextField quickExpenseTitle = new TextField("Аренда");
    private final TextField quickExpenseAmount = FxInputs.moneyField(null);
    private final Spinner<Integer> quickExpenseDay = FxInputs.intSpinner(1, 31, 1);

    private final List<Node> pages = new ArrayList<>();
    private int page;

    /**
     * Создаёт мастер.
     *
     * @param today         сегодняшняя дата (дата начала по умолчанию)
     * @param suggestedName предлагаемое имя плана
     * @param nameTaken     проверка «план с таким именем уже есть в CashMemory»
     */
    public NewPlanWizard(LocalDate today, String suggestedName, Predicate<String> nameTaken) {
        super(WindowType.NEW_PLAN_WIZARD, new AppDialogPane(PAGE_TITLES[0], "₽"));
        this.nameTaken = nameTaken;
        this.startDate = FxInputs.datePicker(today);

        name.setText(suggestedName);
        name.setPromptText("например, Семейный бюджет 2026");
        currency.getItems().setAll(CURRENCIES);
        currency.setEditable(true);
        currency.setValue(Plan.DEFAULT_CURRENCY);

        FormGrid first = new FormGrid()
                .row("Название плана", name)
                .row("Валюта", currency)
                .wide(hint("Файл плана появится в папке CashMemory под этим именем (расширение .md)."));
        FormGrid second = new FormGrid()
                .row("Дата начала", startDate)
                .row("Баланс на эту дату", startBalance)
                .row("Горизонт прогноза", horizon.node())
                .row("Подушка безопасности", cushion)
                .wide(hint("Подушка - сумма, ниже которой баланс опускаться не должен: такие дни подсвечиваются."));
        FormGrid third = new FormGrid()
                .section("Ежемесячный доход")
                .row("Название", quickIncomeTitle)
                .row("Сумма", quickIncomeAmount)
                .row("День месяца", quickIncomeDay)
                .section("Ежемесячный расход")
                .row("Название", quickExpenseTitle)
                .row("Сумма", quickExpenseAmount)
                .row("День месяца", quickExpenseDay)
                .wide(hint("Пустая сумма - операция не создаётся. Остальное добавите позже: Правка → Добавить доход/расход."));
        pages.addAll(List.of(first, second, third));
        appPane().setForm(new StackPane(first, second, third));
        // JavaFX: ButtonType → Swing: SwingButtonType (текст + роль) + JOptionPane.showOptionDialog → Web: <button value> → returnValue
        appPane().getButtonTypes().setAll(AppButtonTypes.BACK, AppButtonTypes.NEXT, AppButtonTypes.FINISH,
                AppButtonTypes.CANCEL);

        // «Назад»/«Далее» не должны закрывать диалог: фильтр поглощает событие до обработчика DialogPane.
        appPane().lookupButton(AppButtonTypes.BACK).addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            showPage(page - 1);
        });
        appPane().lookupButton(AppButtonTypes.NEXT).addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            showPage(page + 1);
        });

        // Порядок привязки = порядок полей словаря WindowType.NEW_PLAN_WIZARD.
        binder().bindText("name", name);
        binder().bindEditableCombo("currency", currency);
        binder().bindDate("startDate", startDate);
        binder().bindMoney("startBalance", startBalance);
        horizon.bind(binder());
        binder().bindMoney("cushion", cushion);
        binder().bindText("quickIncomeTitle", quickIncomeTitle);
        binder().bindMoney("quickIncomeAmount", quickIncomeAmount);
        binder().bindSpinner("quickIncomeDay", quickIncomeDay);
        binder().bindText("quickExpenseTitle", quickExpenseTitle);
        binder().bindMoney("quickExpenseAmount", quickExpenseAmount);
        binder().bindSpinner("quickExpenseDay", quickExpenseDay);

        setResultConverter(button -> button == AppButtonTypes.FINISH ? buildPlan(new ArrayList<>(), new ArrayList<>()).orElse(null) : null);
        showPage(0);
        activate();
    }

    /**
     * Применяет снимок: поля и страницу из контекста {@code page}.
     *
     * @param state состояние из снимка
     */
    @Override
    public void applyState(WindowState state) {
        stateSupport().apply(state);
        int restoredPage = 0;
        try {
            restoredPage = Integer.parseInt(state.contextValue(WindowType.CONTEXT_PAGE));
        } catch (NumberFormatException e) {
            // Нет номера страницы (снимок другого клиента) — начинаем с первой.
        }
        showPage(restoredPage);
    }

    /** {@inheritDoc} */
    @Override
    protected void validateForm(List<String> errors, List<String> warnings) {
        List<String> current = new ArrayList<>();
        pageProblems(page, current, warnings);
        errors.addAll(current);
        appPane().nextAllowedProperty().set(current.isEmpty() && page < pages.size() - 1);
        appPane().backAllowedProperty().set(page > 0);
        if (current.isEmpty()) {
            // «Готово» доступно, только если и остальные страницы без ошибок: подсказываем, где ошибка.
            for (int other = 0; other < pages.size(); other++) {
                List<String> otherErrors = new ArrayList<>();
                pageProblems(other, otherErrors, new ArrayList<>());
                if (!otherErrors.isEmpty()) {
                    errors.add("Шаг " + (other + 1) + ": " + otherErrors.getFirst());
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------ страницы

    private void showPage(int requested) {
        page = Math.clamp(requested, 0, pages.size() - 1);
        for (int i = 0; i < pages.size(); i++) {
            pages.get(i).setVisible(i == page);
        }
        appPane().setHeaderText(PAGE_TITLES[page]);
        stateSupport().putContext(WindowType.CONTEXT_PAGE, Integer.toString(page));
        revalidate();
        stateSupport().touch();
    }

    private void pageProblems(int index, List<String> errors, List<String> warnings) {
        switch (index) {
            case 0 -> {
                Optional<String> nameError = PlanValidator.checkPlanName(name.getText());
                if (nameError.isPresent()) {
                    errors.add(nameError.get());
                } else if (nameTaken.test(name.getText().strip())) {
                    errors.add("План «" + name.getText().strip() + "» уже есть в CashMemory - выберите другое имя");
                }
                if (FxInputs.isBlank(currency.getEditor())) {
                    errors.add("Укажите валюту");
                }
            }
            case 1 -> {
                Optional<LocalDate> start = FxInputs.date(startDate);
                if (start.isEmpty()) {
                    errors.add("Укажите дату начала (ДД.ММ.ГГГГ)");
                }
                if (!FxInputs.isBlank(startBalance) && FxInputs.money(startBalance).isEmpty()) {
                    errors.add("Некорректный начальный баланс");
                }
                horizon.read(start, errors, warnings);
                Optional<Money> cushionValue = FxInputs.money(cushion);
                if (!FxInputs.isBlank(cushion) && cushionValue.isEmpty()) {
                    errors.add("Некорректная сумма подушки безопасности");
                } else if (cushionValue.isPresent() && cushionValue.get().isNegative()) {
                    errors.add("Подушка безопасности не может быть отрицательной");
                }
            }
            default -> {
                checkQuick("дохода", quickIncomeTitle, quickIncomeAmount, quickIncomeDay, errors);
                checkQuick("расхода", quickExpenseTitle, quickExpenseAmount, quickExpenseDay, errors);
            }
        }
    }

    private static void checkQuick(String what, TextField title, TextField amount, Spinner<Integer> day, List<String> errors) {
        if (FxInputs.isBlank(amount)) {
            return;
        }
        Optional<Money> value = FxInputs.money(amount);
        if (value.isEmpty() || !value.get().isPositive()) {
            errors.add("Сумма " + what + " должна быть числом больше нуля");
        } else if (value.get().compareTo(PlanValidator.MAX_AMOUNT) > 0) {
            errors.add("Сумма " + what + " слишком большая");
        }
        if (FxInputs.isBlank(title)) {
            errors.add("Укажите название " + what);
        }
        if (FxInputs.integer(day).filter(d -> d >= 1 && d <= 31).isEmpty()) {
            errors.add("День " + what + " - число от 1 до 31");
        }
    }

    // ------------------------------------------------------------------ результат

    private Optional<Plan> buildPlan(List<String> errors, List<String> warnings) {
        for (int i = 0; i < pages.size(); i++) {
            pageProblems(i, errors, warnings);
        }
        if (!errors.isEmpty()) {
            return Optional.empty();
        }
        LocalDate start = FxInputs.date(startDate).orElseThrow();
        Plan plan = Plan.empty(name.getText().strip(), start)
                .withCurrency(currency.getEditor().getText().strip())
                .withStart(start, FxInputs.money(startBalance).orElse(Money.ZERO))
                .withHorizon(horizon.read(Optional.of(start), errors, warnings).orElseThrow())
                .withCushion(FxInputs.money(cushion).orElse(Money.ZERO));
        plan = withQuickRule(plan, Kind.INCOME, quickIncomeTitle, quickIncomeAmount, quickIncomeDay);
        plan = withQuickRule(plan, Kind.EXPENSE, quickExpenseTitle, quickExpenseAmount, quickExpenseDay);
        return Optional.of(plan);
    }

    private static Plan withQuickRule(Plan plan, Kind kind, TextField title, TextField amount, Spinner<Integer> day) {
        Optional<Money> value = FxInputs.money(amount);
        if (value.isEmpty()) {
            return plan;
        }
        // Выходные не сдвигаются (NONE): мастер не спрашивает об этом, а все три клиента должны создать одинаковый план.
        RecurringRule rule = new RecurringRule(plan.nextRuleId(), title.getText().strip(), kind, value.get(), "",
                new Recurrence.Monthly(FxInputs.integer(day).orElse(1), 1), null, null, WeekendPolicy.NONE, true, "");
        return plan.withRuleAdded(rule);
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setStyle("-fx-text-fill: #555555;");
        return label;
    }
}
