package ru.cashprediction.core.ui.forms.plan;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.ui.form.FieldView;
import ru.cashprediction.core.ui.form.FieldCodec;
import ru.cashprediction.core.ui.form.FieldSpecs;
import ru.cashprediction.core.ui.form.FormRow;
import ru.cashprediction.core.ui.form.FormState;
import ru.cashprediction.core.ui.form.Option;
import ru.cashprediction.core.ui.form.Orientation;
import ru.cashprediction.core.ui.text.UiText;

/**
 * Общие поля горизонта мастера (§6.1) и параметров плана (§6.2): {@code horizonKind} ({@code MONTHS}/{@code YEARS}/
 * {@code UNTIL}), {@code horizonValue}, {@code horizonUntil}.
 *
 * <p>Раскладка «Горизонт прогноза»: строка 1 — spinner (1..600, для «лет» 1..50) + радио «месяцев» / «лет»;
 * строка 2 — радио «до даты» + date. Спиннер отключён при «до даты», дата отключена иначе. Ошибки: «Горизонт должен
 * быть от 1 до 600 месяцев»; «Горизонт должен быть от 1 до 50 лет»; «Укажите дату окончания горизонта (ДД.ММ.ГГГГ)»;
 * «Дата окончания горизонта раньше даты начала плана». Предупреждение: «Горизонт длиннее 20 лет: таблица будет очень
 * длинной».</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class HorizonFields {

    /** Поле вида горизонта. */
    public static final String KIND = "horizonKind";
    /** Поле числа месяцев или лет. */
    public static final String VALUE = "horizonValue";
    /** Поле даты окончания. */
    public static final String UNTIL = "horizonUntil";

    private HorizonFields() {
    }

    /** @return две строки сетки «Горизонт прогноза» */
    public static java.util.List<FormRow> rows() {
        List<Option> first = List.of(Option.of("MONTHS", UiText.get("form.horizon.months")),
                Option.of("YEARS", UiText.get("form.horizon.years")));
        List<Option> second = List.of(Option.of("UNTIL", UiText.get("form.horizon.until")));
        return List.of(new FormRow.Inline(UiText.get("form.horizon.label"), List.of(
                FieldSpecs.spinner(VALUE, "", 1, Horizon.MAX_MONTHS),
                FieldSpecs.radio(KIND, "", Orientation.HORIZONTAL, first))),
                new FormRow.Inline("", List.of(
                        FieldSpecs.radio(KIND, "", Orientation.HORIZONTAL, second),
                        FieldSpecs.date(UNTIL, ""))));
    }

    /**
     * Значения полей для горизонта плана.
     *
     * @param horizon горизонт
     * @return значения {@link #KIND}, {@link #VALUE}, {@link #UNTIL}
     */
    public static Map<String, String> values(Horizon horizon) {
        if (horizon instanceof Horizon.Months months) {
            return Map.of(KIND, "MONTHS", VALUE, Integer.toString(months.count()), UNTIL, "");
        }
        if (horizon instanceof Horizon.Years years) {
            return Map.of(KIND, "YEARS", VALUE, Integer.toString(years.count()), UNTIL, "");
        }
        Horizon.Until until = (Horizon.Until) horizon;
        return Map.of(KIND, "UNTIL", VALUE, "12", UNTIL, FieldCodec.date(until.end()));
    }

    /**
     * Состояния полей (доступность спиннера и даты, диапазон спиннера).
     *
     * @param state значения формы
     * @return модели трёх полей
     */
    public static Map<String, FieldView> views(FormState state) {
        String kind = state.value(KIND);
        boolean until = "UNTIL".equals(kind);
        long max = "YEARS".equals(kind) ? Horizon.MAX_MONTHS / 12L : Horizon.MAX_MONTHS;
        Map<String, FieldView> result = new LinkedHashMap<>();
        result.put(KIND, FieldView.of(kind));
        result.put(VALUE, new FieldView(state.value(VALUE), true, !until, false, null, null, null, 1L, max));
        result.put(UNTIL, new FieldView(state.value(UNTIL), true, until, false, null, null, null));
        return Map.copyOf(result);
    }

    /**
     * Первая ошибка горизонта.
     *
     * @param state значения формы
     * @param start дата начала плана или {@code null}, если она ещё некорректна
     * @return текст ошибки или пусто
     */
    public static Optional<String> error(FormState state, LocalDate start) {
        String kind = state.value(KIND);
        if ("UNTIL".equals(kind)) {
            Optional<LocalDate> end = FieldCodec.parseDate(state.value(UNTIL));
            if (end.isEmpty()) {
                return Optional.of(UiText.get("form.horizon.endRequired"));
            }
            return start != null && end.get().isBefore(start)
                    ? Optional.of(UiText.get("form.horizon.endBeforeStart")) : Optional.empty();
        }
        java.util.OptionalLong parsed = FieldCodec.parseLong(state.value(VALUE));
        Optional<Long> value = parsed.isPresent() ? Optional.of(parsed.getAsLong()) : Optional.empty();
        long max = "YEARS".equals(kind) ? Horizon.MAX_MONTHS / 12L : Horizon.MAX_MONTHS;
        if (value.isEmpty() || value.get() < 1 || value.get() > max) {
            String errorKey = "YEARS".equals(kind) ? "form.horizon.yearsRange" : "form.horizon.monthsRange";
            return Optional.of(UiText.get(errorKey));
        }
        return Optional.empty();
    }

    /**
     * Предупреждение горизонта (длиннее 20 лет).
     *
     * @param state значения формы
     * @param start дата начала
     * @return текст предупреждения или пусто
     */
    public static Optional<String> warning(FormState state, LocalDate start) {
        if (error(state, start).isPresent()) {
            return Optional.empty();
        }
        Horizon horizon = toHorizon(state);
        // При некорректной дате начала предупреждение не показываем: сначала пользователь должен исправить ошибку.
        if (start == null) {
            return Optional.empty();
        }
        LocalDate effectiveStart = start;
        return horizon.approximateMonths(effectiveStart) > 240
                ? Optional.of(UiText.get("form.horizon.long")) : Optional.empty();
    }

    /**
     * Горизонт из корректных значений.
     *
     * @param state значения формы без ошибок горизонта
     * @return горизонт
     */
    public static Horizon toHorizon(FormState state) {
        String kind = state.value(KIND);
        if ("UNTIL".equals(kind)) {
            return new Horizon.Until(FieldCodec.parseDate(state.value(UNTIL)).orElseThrow());
        }
        int value = Math.toIntExact(FieldCodec.parseLong(state.value(VALUE)).orElseThrow());
        return "YEARS".equals(kind) ? new Horizon.Years(value) : new Horizon.Months(value);
    }
}
