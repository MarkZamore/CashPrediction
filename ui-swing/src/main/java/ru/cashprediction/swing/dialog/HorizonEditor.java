package ru.cashprediction.swing.dialog;

import java.time.LocalDate;
import java.util.Optional;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import ru.cashprediction.core.diagnostics.PlanValidator;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.swing.session.SwingFieldBinder;

/**
 * Редактор горизонта плана: «12 [месяцев]», «2 [лет]» или «[до даты] 31.08.2027». Общий для мастера
 * «Новый план» и диалога «Параметры плана», чтобы оба писали в снимок одинаковые поля
 * {@code horizonKind}, {@code horizonValue}, {@code horizonUntil}.
 *
 * <p>Не окно, а группа компонентов; используется только в потоке EDT.</p>
 */
public final class HorizonEditor {

    /** Вид горизонта; имя константы — каноническое значение поля {@code horizonKind}. */
    public enum HorizonKind {
        /** N месяцев. */
        MONTHS("месяцев"),
        /** N лет. */
        YEARS("лет"),
        /** До конкретной даты. */
        UNTIL("до даты");

        private final String title;

        HorizonKind(String title) {
            this.title = title;
        }

        /** @return подпись в списке */
        public String title() {
            return title;
        }
    }

    private final JSpinner value = new JSpinner(new SpinnerNumberModel(12, 1, Horizon.MAX_MONTHS, 1));
    private final JComboBox<HorizonKind> kind = Combos.choice(HorizonKind::title, HorizonKind.MONTHS, HorizonKind.values());
    private final DateField until = new DateField();
    private final JComponent component;

    /**
     * Создаёт редактор с горизонтом 12 месяцев.
     */
    public HorizonEditor() {
        // Граница спиннера — наибольшее число месяцев; для лет ограничение «до 50» проверяет форма,
        // а не модель: менять границы модели на лету означало бы молча портить введённое число.
        component = FormPanel.inline(value, kind, new JLabel(" "), until);
    }

    /**
     * Компонент для строки формы.
     *
     * @return панель редактора
     */
    public JComponent component() {
        return component;
    }

    /**
     * Привязывает поля к снимку сессии в порядке словаря окон.
     *
     * @param binder связыватель диалога
     */
    public void bind(SwingFieldBinder binder) {
        binder.bindEnumCombo("horizonKind", kind, HorizonKind.class);
        binder.bindSpinner("horizonValue", value);
        binder.bindDate("horizonUntil", until);
    }

    /**
     * Показывает горизонт.
     *
     * @param horizon горизонт плана
     */
    public void setHorizon(Horizon horizon) {
        switch (horizon) {
            case Horizon.Months months -> {
                kind.setSelectedItem(HorizonKind.MONTHS);
                value.setValue(months.count());
            }
            case Horizon.Years years -> {
                kind.setSelectedItem(HorizonKind.YEARS);
                value.setValue(years.count());
            }
            case Horizon.Until u -> {
                kind.setSelectedItem(HorizonKind.UNTIL);
                until.setValue(u.end());
            }
        }
        updateUi();
    }

    /**
     * Включает поле числа или даты в зависимости от вида горизонта.
     */
    public void updateUi() {
        boolean byDate = Combos.selected(kind) == HorizonKind.UNTIL;
        value.setEnabled(!byDate);
        until.setEnabled(byDate);
    }

    /**
     * Проверяет горизонт.
     *
     * @param start дата начала плана (для проверки «до даты»); {@code null}, если она сейчас неверна
     * @return текст ошибки или {@code null}
     */
    public String validationError(LocalDate start) {
        int number = ((Number) value.getValue()).intValue();
        HorizonKind selected = Combos.selected(kind);
        if (selected == HorizonKind.MONTHS && (number < 1 || number > Horizon.MAX_MONTHS)) {
            return "Горизонт должен быть от 1 до " + Horizon.MAX_MONTHS + " месяцев";
        }
        if (selected == HorizonKind.YEARS && (number < 1 || number > Horizon.MAX_MONTHS / 12)) {
            return "Горизонт должен быть от 1 до " + (Horizon.MAX_MONTHS / 12) + " лет";
        }
        if (selected == HorizonKind.UNTIL) {
            String error = until.validationError("Горизонт до даты", true);
            if (error != null) {
                return error;
            }
            if (start != null && until.value().orElseThrow().isBefore(start)) {
                return "Дата окончания горизонта раньше даты начала плана";
            }
        }
        return null;
    }

    /**
     * Горизонт из полей.
     *
     * @return горизонт
     * @throws IllegalArgumentException если поля неверны
     */
    public Horizon horizon() {
        int number = ((Number) value.getValue()).intValue();
        return switch (Combos.selected(kind)) {
            case YEARS -> new Horizon.Years(number);
            case UNTIL -> new Horizon.Until(until.value()
                    .orElseThrow(() -> new IllegalArgumentException("Укажите дату окончания горизонта")));
            case null, default -> new Horizon.Months(number);
        };
    }

    /**
     * Предупреждение о слишком длинном горизонте.
     *
     * @param start дата начала плана или {@code null}
     * @return текст предупреждения или пусто
     */
    public Optional<String> longHorizonWarning(LocalDate start) {
        if (start == null || validationError(start) != null) {
            return Optional.empty();
        }
        long months = horizon().approximateMonths(start);
        return months > PlanValidator.LONG_HORIZON_MONTHS
                ? Optional.of("Горизонт больше " + (PlanValidator.LONG_HORIZON_MONTHS / 12) + " лет: прогноз будет длинным")
                : Optional.empty();
    }
}
