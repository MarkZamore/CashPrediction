package ru.cashprediction.fx.dialog;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.DatePicker;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import ru.cashprediction.core.diagnostics.PlanValidator;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.fx.session.FxFieldBinder;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Группа полей «Горизонт плана»: N месяцев, N лет или до даты.
 *
 * <p>Общая для мастера нового плана и параметров плана, чтобы проверка горизонта (1..600 месяцев,
 * 1..50 лет, дата окончания не раньше начала, предупреждение о горизонте длиннее 20 лет) была одна.
 * Поля словаря: {@code horizonKind} (MONTHS/YEARS/UNTIL), {@code horizonValue}, {@code horizonUntil}.</p>
 *
 * <p>Только FX Application Thread.</p>
 */
final class HorizonEditor {

    private final ToggleGroup kindGroup = new ToggleGroup();
    private final RadioButton months = radio("месяцев", "MONTHS");
    private final RadioButton years = radio("лет", "YEARS");
    private final RadioButton until = radio("до даты", "UNTIL");
    private final Spinner<Integer> value = FxInputs.intSpinner(1, Horizon.MAX_MONTHS, 12);
    private final DatePicker untilDate = FxInputs.datePicker(null);
    private final VBox node;

    /**
     * Создаёт редактор с начальным горизонтом.
     *
     * @param initial горизонт плана
     */
    HorizonEditor(Horizon initial) {
        switch (initial) {
            case Horizon.Months m -> {
                months.setSelected(true);
                value.getValueFactory().setValue(m.count());
            }
            case Horizon.Years y -> {
                years.setSelected(true);
                value.getValueFactory().setValue(y.count());
            }
            case Horizon.Until u -> {
                until.setSelected(true);
                untilDate.setValue(u.end());
            }
        }
        // Число не имеет смысла для «до даты», а дата — для «N месяцев»: лишнее поле недоступно.
        value.disableProperty().bind(until.selectedProperty());
        untilDate.disableProperty().bind(until.selectedProperty().not());
        HBox countRow = new HBox(8, value, months, years);
        HBox untilRow = new HBox(8, until, untilDate);
        countRow.setAlignment(Pos.CENTER_LEFT);
        untilRow.setAlignment(Pos.CENTER_LEFT);
        // Две строки: в одну строку при ширине диалога 560 подписи переключателей обрезались до «ме…» и «до…».
        node = new VBox(6, countRow, untilRow);
    }

    /**
     * Привязывает поля к словарю окна.
     *
     * @param binder связыватель диалога
     */
    void bind(FxFieldBinder binder) {
        binder.bindToggle("horizonKind", kindGroup);
        binder.bindSpinner("horizonValue", value);
        binder.bindDate("horizonUntil", untilDate);
    }

    /** @return узел для формы */
    Node node() {
        return node;
    }

    /**
     * Читает и проверяет горизонт.
     *
     * @param start    дата начала плана (может быть неизвестна, если поле даты некорректно)
     * @param errors   приёмник ошибок
     * @param warnings приёмник предупреждений
     * @return горизонт или пусто, если есть ошибка
     */
    Optional<Horizon> read(Optional<LocalDate> start, List<String> errors, List<String> warnings) {
        Horizon horizon;
        try {
            if (until.isSelected()) {
                Optional<LocalDate> end = FxInputs.date(untilDate);
                if (end.isEmpty()) {
                    errors.add("Укажите дату окончания горизонта (ДД.ММ.ГГГГ)");
                    return Optional.empty();
                }
                if (start.isPresent() && end.get().isBefore(start.get())) {
                    errors.add("Дата окончания горизонта раньше даты начала плана");
                    return Optional.empty();
                }
                horizon = new Horizon.Until(end.get());
            } else {
                Optional<Integer> number = FxInputs.integer(value);
                if (number.isEmpty()) {
                    errors.add("Укажите длину горизонта целым числом");
                    return Optional.empty();
                }
                horizon = years.isSelected() ? new Horizon.Years(number.get()) : new Horizon.Months(number.get());
            }
        } catch (IllegalArgumentException e) {
            // Конструкторы Horizon сами знают допустимые пределы и объясняют их по-русски.
            errors.add(e.getMessage());
            return Optional.empty();
        }
        if (start.isPresent() && horizon.approximateMonths(start.get()) > PlanValidator.LONG_HORIZON_MONTHS) {
            warnings.add("Горизонт длиннее " + PlanValidator.LONG_HORIZON_MONTHS / 12 + " лет: таблица будет очень длинной");
        }
        return Optional.of(horizon);
    }

    private RadioButton radio(String text, String key) {
        RadioButton button = new RadioButton(text);
        button.setUserData(key);
        button.setToggleGroup(kindGroup);
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }
}
