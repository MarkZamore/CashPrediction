package ru.cashprediction.swing.dialog;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Window;
import java.time.LocalDate;
import java.util.function.Predicate;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import ru.cashprediction.core.diagnostics.PlanValidator;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.WeekendPolicy;
import ru.cashprediction.core.session.SessionRecorder;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;

/**
 * Диалог 1 «Новый план» — мастер из трёх страниц: название и дата начала; баланс, горизонт и подушка;
 * быстрые доход и расход. Результат — готовый {@link Plan}.
 *
 * <p>Кнопки «Назад»/«Далее»/«Готово» — типы {@link SwingButtonType} с ролями {@code BACK_PREVIOUS},
 * {@code NEXT_FORWARD}, {@code FINISH}: «Далее» доступна, только если текущая страница заполнена верно,
 * «Готово» — на последней странице при верных данных всех страниц. Номер страницы хранится в контексте окна
 * ({@code page} = «0».."2»), поэтому после сбоя мастер открывается на той же странице.</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
// JavaFX: Dialog<Plan> + DialogPane + ButtonType("Назад"/"Далее"/"Готово") → Swing: SwingDialog<Plan> + SwingDialogPane + SwingButtonType → Web: <dialog> мастера
public final class NewPlanWizard extends SwingDialog<Plan> {

    /** «Назад». */
    public static final SwingButtonType BACK = new SwingButtonType("< Назад", SwingButtonType.Role.BACK_PREVIOUS);
    /** «Далее». */
    public static final SwingButtonType NEXT = new SwingButtonType("Далее >", SwingButtonType.Role.NEXT_FORWARD);
    /** «Готово». */
    public static final SwingButtonType FINISH = new SwingButtonType("Готово", SwingButtonType.Role.FINISH);

    private static final int LAST_PAGE = 2;
    private static final String[] HEADERS = {
        "Шаг 1 из 3. Название плана и дата, на которую известен баланс",
        "Шаг 2 из 3. Сколько денег сейчас, на какой срок прогноз и какой запас держать",
        "Шаг 3 из 3. Основной доход и основной расход (можно пропустить и добавить позже)"
    };

    private final CardLayout cards = new CardLayout();
    private final JPanel pages = new JPanel(cards);
    private final Predicate<String> nameTaken;

    private final JTextField name = new JTextField(24);
    private final JComboBox<String> currency = Combos.editable(Combos.CURRENCIES, Plan.DEFAULT_CURRENCY);
    private final DateField startDate = new DateField();
    private final MoneyField startBalance = new MoneyField();
    private final HorizonEditor horizon = new HorizonEditor();
    private final MoneyField cushion = new MoneyField();
    private final JTextField quickIncomeTitle = new JTextField("Зарплата", 16);
    private final MoneyField quickIncomeAmount = new MoneyField();
    private final JSpinner quickIncomeDay = new JSpinner(new SpinnerNumberModel(5, 1, 31, 1));
    private final JTextField quickExpenseTitle = new JTextField("Аренда", 16);
    private final MoneyField quickExpenseAmount = new MoneyField();
    private final JSpinner quickExpenseDay = new JSpinner(new SpinnerNumberModel(1, 1, 31, 1));

    private int page;

    /**
     * Создаёт мастер.
     *
     * @param owner       окно-владелец
     * @param ownerId     идентификатор владельца для снимка
     * @param recorder    рекордер сессии или {@code null}
     * @param today       сегодняшняя дата (дата начала по умолчанию)
     * @param defaultName предлагаемое имя плана
     * @param nameTaken   проверка «план с таким именем уже есть в CashMemory»
     */
    public NewPlanWizard(Window owner, String ownerId, SessionRecorder recorder, LocalDate today, String defaultName,
                         Predicate<String> nameTaken) {
        super(owner, ownerId, WindowType.NEW_PLAN_WIZARD, true, "Новый план", recorder);
        this.nameTaken = nameTaken == null ? n -> false : nameTaken;
        name.setText(defaultName == null ? "" : defaultName);
        startDate.setValue(today);
        startBalance.setValue(Money.ZERO);
        cushion.setValue(Money.ZERO);

        FormPanel page0 = new FormPanel();
        page0.addRow("Название плана", name);
        page0.addRow("Валюта", currency);
        page0.addRow("Дата начала", startDate);

        FormPanel page1 = new FormPanel();
        page1.addRow("Баланс на дату начала", startBalance);
        page1.addRow("Горизонт прогноза", horizon.component());
        page1.addRow("Подушка безопасности", cushion);

        FormPanel page2 = new FormPanel();
        page2.addSection("Доход каждый месяц");
        page2.addRow("Название", quickIncomeTitle);
        page2.addRow("Сумма", quickIncomeAmount);
        page2.addRow("День месяца", quickIncomeDay);
        page2.addSection("Расход каждый месяц");
        page2.addRow("Название", quickExpenseTitle);
        page2.addRow("Сумма", quickExpenseAmount);
        page2.addRow("День месяца", quickExpenseDay);

        pages.add(topAligned(page0), "0");
        pages.add(topAligned(page1), "1");
        pages.add(topAligned(page2), "2");
        pane().setContent(pages);

        // Порядок привязки совпадает со словарём окон (WindowType.NEW_PLAN_WIZARD.fieldIds()).
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

        setButtonTypes(BACK, NEXT, FINISH, SwingButtonType.CANCEL);
        setValidator(() -> pageError(page));
        setButtonHandler(button -> {
            if (button.equals(BACK)) {
                showPage(Math.max(0, page - 1));
                return true;
            }
            if (button.equals(NEXT)) {
                if (pageError(page) == null) {
                    showPage(Math.min(LAST_PAGE, page + 1));
                }
                return true;
            }
            return false;
        });
        setResultConverter(button -> button.equals(FINISH) ? buildPlan() : null);
        setInitialFocus(name);
        showPage(0);
    }

    /** {@inheritDoc} */
    @Override
    protected boolean isButtonEnabled(SwingButtonType button, String error) {
        if (button.equals(BACK)) {
            return page > 0;
        }
        if (button.equals(NEXT)) {
            return page < LAST_PAGE && error == null;
        }
        if (button.equals(FINISH)) {
            return page == LAST_PAGE && allPagesError() == null;
        }
        return true;
    }

    /** {@inheritDoc} */
    @Override
    protected void onFieldsChanged() {
        horizon.updateUi();
    }

    /** {@inheritDoc} */
    @Override
    protected void afterStateApplied(WindowState state) {
        int restoredPage;
        try {
            restoredPage = Integer.parseInt(state.contextValue(WindowType.CONTEXT_PAGE).strip());
        } catch (NumberFormatException e) {
            restoredPage = 0;
        }
        showPage(Math.max(0, Math.min(LAST_PAGE, restoredPage)));
    }

    /** Переключает страницу, заголовок и кнопку по умолчанию; номер страницы попадает в контекст окна. */
    private void showPage(int newPage) {
        page = newPage;
        cards.show(pages, Integer.toString(page));
        putContext(WindowType.CONTEXT_PAGE, Integer.toString(page));
        pane().setHeaderText(HEADERS[page]);
        // Enter на промежуточной странице — «Далее», на последней — «Готово».
        JButton defaultButton = pane().lookupButton(page == LAST_PAGE ? FINISH : NEXT);
        window().getRootPane().setDefaultButton(defaultButton);
        revalidateForm();
        touch();
    }

    /**
     * Прижимает страницу к верху. Высоту мастера задаёт самая высокая (третья) страница; без этого короткие страницы
     * 1 и 2 центрировались бы по вертикали {@code GridBagLayout} с пустыми полосами сверху и снизу.
     */
    private static JPanel topAligned(JPanel page) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(page, BorderLayout.NORTH);
        return wrapper;
    }

    // ------------------------------------------------------------------ проверка

    private String pageError(int index) {
        return switch (index) {
            case 0 -> firstError(
                    PlanValidator.checkPlanName(name.getText()).orElse(null),
                    nameTaken.test(name.getText().strip())
                            ? "План «" + name.getText().strip() + "» уже есть в папке CashMemory: выберите другое название" : null,
                    Combos.editorText(currency).isEmpty() ? "Укажите валюту" : null,
                    startDate.validationError("Дата начала", true));
            case 1 -> firstError(
                    startBalance.validationError("Баланс на дату начала", true, false),
                    horizon.validationError(startDate.value().orElse(null)),
                    cushion.validationError("Подушка безопасности", false, false),
                    cushion.value().filter(Money::isNegative).isPresent() ? "Подушка безопасности не может быть отрицательной" : null);
            default -> firstError(
                    quickError(quickIncomeTitle, quickIncomeAmount, "дохода"),
                    quickError(quickExpenseTitle, quickExpenseAmount, "расхода"));
        };
    }

    private String allPagesError() {
        return firstError(pageError(0), pageError(1), pageError(2));
    }

    private static String quickError(JTextField title, MoneyField amount, String what) {
        if (amount.isBlank()) {
            return null;
        }
        String error = amount.validationError("Сумма " + what, true, true);
        if (error != null) {
            return error;
        }
        return title.getText().isBlank() ? "Укажите название " + what : null;
    }

    private static String firstError(String... errors) {
        for (String error : errors) {
            if (error != null) {
                return error;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ результат

    private Plan buildPlan() {
        String error = allPagesError();
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
        LocalDate start = startDate.value().orElseThrow();
        Plan plan = Plan.empty(name.getText().strip(), start)
                .withCurrency(Combos.editorText(currency))
                .withStart(start, startBalance.value().orElse(Money.ZERO))
                .withHorizon(horizon.horizon())
                .withCushion(cushion.value().orElse(Money.ZERO));
        if (quickIncomeAmount.value().isPresent()) {
            // Зарплату обычно переводят в пятницу, если день выплаты выпал на выходной.
            plan = plan.withRuleAdded(new RecurringRule(plan.nextRuleId(), quickIncomeTitle.getText(), Kind.INCOME,
                    quickIncomeAmount.value().get(), "", new Recurrence.Monthly(((Number) quickIncomeDay.getValue()).intValue(), 1),
                    null, null, WeekendPolicy.PREVIOUS_BUSINESS_DAY, true, ""));
        }
        if (quickExpenseAmount.value().isPresent()) {
            plan = plan.withRuleAdded(new RecurringRule(plan.nextRuleId(), quickExpenseTitle.getText(), Kind.EXPENSE,
                    quickExpenseAmount.value().get(), "", new Recurrence.Monthly(((Number) quickExpenseDay.getValue()).intValue(), 1),
                    null, null, WeekendPolicy.NONE, true, ""));
        }
        return plan;
    }
}
