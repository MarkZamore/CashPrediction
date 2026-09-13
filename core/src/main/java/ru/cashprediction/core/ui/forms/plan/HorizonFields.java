package ru.cashprediction.core.ui.forms.plan;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.ui.form.FieldView;
import ru.cashprediction.core.ui.form.FormRow;
import ru.cashprediction.core.ui.form.FormState;

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
        throw new UnsupportedOperationException("S1: core-forms-plan — HorizonFields.rows");
    }

    /**
     * Значения полей для горизонта плана.
     *
     * @param horizon горизонт
     * @return значения {@link #KIND}, {@link #VALUE}, {@link #UNTIL}
     */
    public static Map<String, String> values(Horizon horizon) {
        throw new UnsupportedOperationException("S1: core-forms-plan — HorizonFields.values");
    }

    /**
     * Состояния полей (доступность спиннера и даты, диапазон спиннера).
     *
     * @param state значения формы
     * @return модели трёх полей
     */
    public static Map<String, FieldView> views(FormState state) {
        throw new UnsupportedOperationException("S1: core-forms-plan — HorizonFields.views");
    }

    /**
     * Первая ошибка горизонта.
     *
     * @param state значения формы
     * @param start дата начала плана или {@code null}, если она ещё некорректна
     * @return текст ошибки или пусто
     */
    public static Optional<String> error(FormState state, LocalDate start) {
        throw new UnsupportedOperationException("S1: core-forms-plan — HorizonFields.error");
    }

    /**
     * Предупреждение горизонта (длиннее 20 лет).
     *
     * @param state значения формы
     * @param start дата начала
     * @return текст предупреждения или пусто
     */
    public static Optional<String> warning(FormState state, LocalDate start) {
        throw new UnsupportedOperationException("S1: core-forms-plan — HorizonFields.warning");
    }

    /**
     * Горизонт из корректных значений.
     *
     * @param state значения формы без ошибок горизонта
     * @return горизонт
     */
    public static Horizon toHorizon(FormState state) {
        throw new UnsupportedOperationException("S1: core-forms-plan — HorizonFields.toHorizon");
    }
}
