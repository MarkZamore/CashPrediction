package ru.cashprediction.fx.dialog;

import javafx.scene.control.DatePicker;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.util.StringConverter;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.util.DateFormats;
import ru.cashprediction.core.util.RuText;
import ru.cashprediction.fx.session.FieldValues;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Optional;
import java.util.function.Function;

/**
 * Фабрики полей ввода и чтение их значений для форм диалогов.
 *
 * <p>Главная забота — безопасность. Стандартные конвертеры {@link DatePicker} и {@link Spinner} бросают
 * исключение на недопечатанном тексте при потере фокуса; в нашем приложении любое необработанное исключение
 * UI-потока означает «сохранить сессию и аварийно завершиться». Поэтому конвертеры здесь никогда не бросают:
 * некорректный текст оставляет прежнее значение, а форма показывает ошибку через свою проверку.</p>
 *
 * <p>Только FX Application Thread.</p>
 */
public final class FxInputs {

    private FxInputs() {
    }

    /**
     * Поле суммы: выравнивание вправо, подсказка формата.
     *
     * @param initial начальное значение или {@code null}
     * @return поле
     */
    public static TextField moneyField(Money initial) {
        TextField field = new TextField(initial == null ? "" : initial.format());
        field.setPromptText("0,00");
        field.setPrefColumnCount(12);
        field.setStyle("-fx-alignment: CENTER-RIGHT;");
        return field;
    }

    /**
     * Выбор даты с русским форматом {@code ДД.ММ.ГГГГ}; понимает и {@code ГГГГ-ММ-ДД}.
     *
     * @param initial начальная дата или {@code null}
     * @return элемент выбора даты
     */
    public static DatePicker datePicker(LocalDate initial) {
        DatePicker picker = new DatePicker(initial);
        picker.setEditable(true);
        picker.setPromptText("ДД.ММ.ГГГГ");
        picker.setPrefWidth(150);
        picker.setConverter(new StringConverter<>() {
            /**
             * Дата для показа в поле в формате ДД.ММ.ГГГГ.
             *
             * @param date дата или {@code null}
             * @return текст поля
             */
            @Override
            public String toString(LocalDate date) {
                return DateFormats.ru(date);
            }

            /**
             * Дата из введённого текста: пустой текст — {@code null}, недопечатанный — прежнее значение без исключения.
             *
             * @param text текст редактора
             * @return дата или {@code null}
             */
            @Override
            public LocalDate fromString(String text) {
                if (text == null || text.isBlank()) {
                    return null;
                }
                // Недопечатанная дата не должна ни бросать исключение, ни стирать прежнее значение.
                return FieldValues.parseDate(text).orElse(picker.getValue());
            }
        });
        return picker;
    }

    /**
     * Редактируемый Spinner целых чисел с безопасным конвертером.
     *
     * @param min     минимум
     * @param max     максимум
     * @param initial начальное значение
     * @return Spinner
     */
    public static Spinner<Integer> intSpinner(int min, int max, int initial) {
        SpinnerValueFactory.IntegerSpinnerValueFactory factory =
                new SpinnerValueFactory.IntegerSpinnerValueFactory(min, max, Math.clamp(initial, min, max));
        factory.setConverter(new StringConverter<>() {
            /**
             * Число для показа в редакторе Spinner.
             *
             * @param value число или {@code null}
             * @return текст редактора
             */
            @Override
            public String toString(Integer value) {
                return value == null ? "" : value.toString();
            }

            /**
             * Число из текста редактора; нечисловой текст оставляет прежнее значение фабрики.
             *
             * @param text текст редактора
             * @return число
             */
            @Override
            public Integer fromString(String text) {
                return FieldValues.parseInt(text).orElse(factory.getValue());
            }
        });
        Spinner<Integer> spinner = new Spinner<>(factory);
        spinner.setEditable(true);
        spinner.setPrefWidth(100);
        return spinner;
    }

    /**
     * Сумма из поля.
     *
     * @param control поле
     * @return сумма или пусто
     */
    public static Optional<Money> money(TextInputControl control) {
        return FieldValues.parseMoney(control.getText());
    }

    /**
     * Дата из редактора DatePicker (по тексту, а не по ещё не зафиксированному {@code value}).
     *
     * @param picker элемент выбора даты
     * @return дата или пусто
     */
    public static Optional<LocalDate> date(DatePicker picker) {
        return FieldValues.parseDate(picker.getEditor().getText());
    }

    /**
     * Число из редактора Spinner.
     *
     * @param spinner Spinner
     * @return число или пусто
     */
    public static Optional<Integer> integer(Spinner<Integer> spinner) {
        return FieldValues.parseInt(spinner.getEditor().getText());
    }

    /**
     * Пустое ли текстовое поле.
     *
     * @param control поле
     * @return {@code true}, если текста нет или только пробелы
     */
    public static boolean isBlank(TextInputControl control) {
        return control.getText() == null || control.getText().isBlank();
    }

    /**
     * Пустой ли редактор даты.
     *
     * @param picker элемент выбора даты
     * @return {@code true}, если текста нет
     */
    public static boolean isBlank(DatePicker picker) {
        return isBlank(picker.getEditor());
    }

    /**
     * Конвертер только для показа элементов списка.
     *
     * @param toText элемент → подпись
     * @param <T>    тип элементов
     * @return конвертер; обратное преобразование не используется нередактируемыми списками
     */
    public static <T> StringConverter<T> displayConverter(Function<T, String> toText) {
        return new StringConverter<>() {
            /**
             * Подпись элемента списка.
             *
             * @param object элемент или {@code null}
             * @return подпись (пустая для {@code null})
             */
            @Override
            public String toString(T object) {
                return object == null ? "" : toText.apply(object);
            }

            /**
             * Обратное преобразование нередактируемому списку не нужно.
             *
             * @param string текст
             * @return всегда {@code null}
             */
            @Override
            public T fromString(String string) {
                return null;
            }
        };
    }

    /**
     * Название дня недели с заглавной буквы.
     *
     * @param day день недели
     * @return «Понедельник»
     */
    public static String weekdayTitle(DayOfWeek day) {
        String full = RuText.weekdayFull(day);
        return full.isEmpty() ? full : Character.toUpperCase(full.charAt(0)) + full.substring(1);
    }
}
