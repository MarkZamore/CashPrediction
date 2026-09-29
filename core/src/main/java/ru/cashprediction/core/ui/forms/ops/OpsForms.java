package ru.cashprediction.core.ui.forms.ops;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurrenceKind;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.WeekendPolicy;
import ru.cashprediction.core.ui.form.FieldCodec;
import ru.cashprediction.core.ui.form.FieldView;
import ru.cashprediction.core.ui.form.Option;
import ru.cashprediction.core.ui.form.Problem;
import ru.cashprediction.core.ui.form.PreviewItem;
import ru.cashprediction.core.ui.form.FormState;
import ru.cashprediction.core.ui.text.UiFormats;

/**
 * Общие маленькие операции редакторов §5.6.1 и §6.3-§6.5.
 *
 * <p>Класс намеренно пакетный: он не является отдельным контрактом ядра, а не даёт четырём формам расходиться в
 * выборе кодов перечислений, форматировании и преобразовании введённых данных в модель плана.</p>
 */
final class OpsForms {

    private OpsForms() {
    }

    /** @return дата, с которой редакторы начинают предлагать операции */
    static LocalDate effectiveToday(LocalDate today, LocalDate planStart) {
        return today.isAfter(planStart) ? today : planStart;
    }

    /** @return варианты типа операции */
    static List<Option> kindOptions() {
        return List.of(Option.of(Kind.INCOME.name(), Kind.INCOME.title()), Option.of(Kind.EXPENSE.name(), Kind.EXPENSE.title()));
    }

    /** @return варианты вида повторения */
    static List<Option> recurrenceOptions() {
        List<Option> result = new ArrayList<>();
        for (RecurrenceKind kind : RecurrenceKind.values()) {
            result.add(Option.of(kind.name(), kind.title()));
        }
        return List.copyOf(result);
    }

    /** @return варианты дня недели */
    static List<Option> weekdayOptions() {
        List<Option> result = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            result.add(Option.of(day.name(), UiFormats.weekdayFull(LocalDate.of(2024, 1, 1).with(day))));
        }
        return List.copyOf(result);
    }

    /** @return варианты поведения на выходных */
    static List<Option> weekendOptions() {
        List<Option> result = new ArrayList<>();
        for (WeekendPolicy policy : WeekendPolicy.values()) {
            result.add(Option.of(policy.name(), policy.title()));
        }
        return List.copyOf(result);
    }

    /** @return варианты категорий плана */
    static List<Option> categoryOptions(List<String> categories) {
        return categories.stream().map(value -> Option.of(value, value)).toList();
    }

    /** @return значение перечисления или запасное */
    static <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Enum.valueOf(type, value.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    /** @return целое в заданном диапазоне или запасное */
    static int integer(String value, int fallback) {
        var parsed = FieldCodec.parseLong(value);
        if (parsed.isEmpty()) {
            return fallback;
        }
        long number = parsed.getAsLong();
        return number < Integer.MIN_VALUE || number > Integer.MAX_VALUE ? Integer.MIN_VALUE : (int) number;
    }

    /** @return введённая сумма или {@code null} */
    static Money money(String value) {
        return FieldCodec.parseMoney(value).orElse(null);
    }

    /** @return введённая дата или {@code null} */
    static LocalDate date(String value) {
        return FieldCodec.parseDate(value).orElse(null);
    }

    /** @return введённый день года или {@code null} */
    static MonthDay monthDay(String value) {
        return FieldCodec.parseMonthDay(value).orElse(null);
    }

    /** @return обычный набор отображаемых полей со значениями состояния */
    static Map<String, FieldView> values(FormState state, String... fieldIds) {
        Map<String, FieldView> result = new LinkedHashMap<>();
        for (String id : fieldIds) {
            result.put(id, FieldView.of(state.value(id)));
        }
        return result;
    }

    /** @return первая ошибка либо предупреждение */
    static Problem problem(Optional<String> error, String warning) {
        if (error.isPresent()) {
            return Problem.error(error.get());
        }
        return warning == null || warning.isBlank() ? Problem.NONE : Problem.warning(warning);
    }

    /** @return действие корректировки по коду формы или {@code null}, когда обязательное поле некорректно */
    static Adjustment.Action adjustmentAction(String action, Money amount, LocalDate date) {
        return switch (action) {
            case "SKIP" -> new Adjustment.Skip();
            case "CHANGE_AMOUNT" -> amount == null ? null : new Adjustment.ChangeAmount(amount);
            case "MOVE" -> date == null ? null : new Adjustment.MoveDate(date);
            case "REPLACE" -> amount == null || date == null ? null : new Adjustment.Replace(amount, date);
            default -> null;
        };
    }

    /** @return действие корректировки в понятных для пользователя словах */
    static String adjustmentDescription(Adjustment.Action action) {
        if (action instanceof Adjustment.Skip) {
            return "adjustment.current.skip";
        }
        if (action instanceof Adjustment.ChangeAmount) {
            return "adjustment.current.amount";
        }
        if (action instanceof Adjustment.MoveDate) {
            return "adjustment.current.move";
        }
        return "adjustment.current.replace";
    }

    /** @return правило, собранное из формы, или {@code null} при некорректных данных */
    static RecurringRule rule(String id, FormState state, LocalDate planStart) {
        Kind kind = enumValue(Kind.class, state.value("kind"), Kind.EXPENSE);
        Money amount = money(state.value("amount"));
        Recurrence recurrence = recurrence(state, planStart);
        if (amount == null || recurrence == null) {
            return null;
        }
        LocalDate from = FieldCodec.parseBoolean(state.value("fromEnabled")) ? date(state.value("from")) : null;
        LocalDate until = FieldCodec.parseBoolean(state.value("untilEnabled")) ? date(state.value("until")) : null;
        WeekendPolicy weekend = enumValue(WeekendPolicy.class, state.value("weekendPolicy"), WeekendPolicy.NONE);
        return new RecurringRule(new ru.cashprediction.core.model.RuleId(id), state.value("title"), kind, amount,
                state.value("category"), recurrence, from, until, weekend, FieldCodec.parseBoolean(state.value("enabled")),
                state.value("note"));
    }

    /** @return правило повторения или {@code null}, если его параметры нельзя разобрать */
    static Recurrence recurrence(FormState state, LocalDate planStart) {
        RecurrenceKind kind = enumValue(RecurrenceKind.class, state.value("recurrenceKind"), RecurrenceKind.MONTHLY);
        int n = integer(state.value("everyN"), 1);
        try {
            return switch (kind) {
                case MONTHLY -> new Recurrence.Monthly(integer(state.value("dayOfMonth"), effectiveToday(planStart, planStart).getDayOfMonth()), n);
                case WEEKLY -> new Recurrence.Weekly(enumValue(DayOfWeek.class, state.value("weekday"), planStart.getDayOfWeek()), n);
                case EVERY_N_DAYS -> new Recurrence.EveryNDays(n);
                case YEARLY -> {
                    MonthDay day = monthDay(state.value("monthDay"));
                    yield day == null ? null : new Recurrence.Yearly(day);
                }
            };
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
