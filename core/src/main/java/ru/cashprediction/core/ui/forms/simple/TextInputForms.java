package ru.cashprediction.core.ui.forms.simple;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import ru.cashprediction.core.diagnostics.PlanValidator;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.form.ButtonRole;
import ru.cashprediction.core.ui.form.ButtonSpec;
import ru.cashprediction.core.ui.form.ButtonSpecs;
import ru.cashprediction.core.ui.form.FieldChecks;
import ru.cashprediction.core.ui.form.FieldCodec;
import ru.cashprediction.core.ui.form.FieldSpecs;
import ru.cashprediction.core.ui.form.FieldView;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormLogic;
import ru.cashprediction.core.ui.form.FormOutcome;
import ru.cashprediction.core.ui.form.FormPage;
import ru.cashprediction.core.ui.form.FormRow;
import ru.cashprediction.core.ui.form.FormSpec;
import ru.cashprediction.core.ui.form.FormState;
import ru.cashprediction.core.ui.form.FormView;
import ru.cashprediction.core.ui.form.Presentation;
import ru.cashprediction.core.ui.form.Problem;
import ru.cashprediction.core.ui.text.UiFormats;
import ru.cashprediction.core.ui.text.UiText;

/** Логики окон ввода одного значения. */
public final class TextInputForms {
    public static final String PURPOSE_RENAME = "rename";
    public static final String PURPOSE_RECONCILE = "reconcile";
    public static final String PURPOSE_CUSTOM_MONTHS = "customMonths";
    public static final String PURPOSE_CUSTOM_CURRENCY = "customCurrency";
    private TextInputForms() { }
    /**
     * Создаёт форму ввода имени плана с проверкой допустимости имени.
     *
     * @return новая логика переименования, возвращающая строку имени
     */
    public static FormLogic rename() { return new Single(PURPOSE_RENAME); }
    /**
     * Создаёт форму сверки фактического остатка на текущую дату.
     *
     * @return новая логика ввода суммы, возвращающая {@link Money}
     */
    public static FormLogic reconcile() { return new Single(PURPOSE_RECONCILE); }
    /**
     * Создаёт форму ввода целого числа месяцев в диапазоне от 1 до 600.
     *
     * @return новая логика выбора периода, возвращающая {@link Integer}
     */
    public static FormLogic customMonths() { return new Single(PURPOSE_CUSTOM_MONTHS); }
    /**
     * Создаёт форму собственного обозначения валюты с проверкой длины и запрещённых символов.
     *
     * @return новая логика ввода валюты, возвращающая строку обозначения
     */
    public static FormLogic customCurrency() { return new Single(PURPOSE_CUSTOM_CURRENCY); }
    /**
     * Выбирает логику одно-полевой формы по сохранённому назначению при восстановлении окна.
     *
     * @param purpose одно из назначений переименования, сверки, ввода месяцев или валюты
     * @return новый экземпляр соответствующей логики
     * @throws IllegalArgumentException если назначение неизвестно или равно {@code null};
     *                                  сообщение содержит ключ {@code restore.warn.unknownPurpose}
     */
    public static FormLogic forPurpose(String purpose) {
        return switch (purpose == null ? "" : purpose) {
            case PURPOSE_RENAME -> rename(); case PURPOSE_RECONCILE -> reconcile(); case PURPOSE_CUSTOM_MONTHS -> customMonths(); case PURPOSE_CUSTOM_CURRENCY -> customCurrency();
            default -> throw new IllegalArgumentException("restore.warn.unknownPurpose");
        };
    }

    /** Общая реализация закрытого набора одно-полевых форм. */
    private static final class Single implements FormLogic {
        private final String purpose;
        private Single(String purpose) { this.purpose = purpose; }
        /**
         * Описывает одно поле и кнопки согласно назначению формы, сохраняя назначение в спецификации.
         * Поле получает начальный фокус, подтверждение назначается кнопкой Enter.
         *
         * @param context контекст для заголовка и типа поля
         * @return спецификация ввода имени, суммы, месяцев или обозначения валюты
         */
        @Override public FormSpec spec(FormContext context) {
            return new FormSpec(purpose, WindowType.TEXT_INPUT, purpose, Presentation.TEXT_INPUT, title(context), "", 460, true, false, true,
                    List.of(new FormPage("main", List.of(new FormRow.Field(FieldSpecs.focused(field(context)))))), buttons(), okId());
        }
        /**
         * Заполняет поле текущим именем или валютой плана, прогнозным остатком на сегодня
         * либо числом 12 для месяцев. При недоступном прогнозе сумма остаётся пустой.
         *
         * @param context контекст с планом, прогнозом и текущей датой
         * @return неизменяемая карта с начальным значением {@code value}
         */
        @Override public Map<String, String> defaults(FormContext context) {
            return Map.of("value", switch (purpose) {
                case PURPOSE_RENAME -> context.app().document().plan().name();
                case PURPOSE_RECONCILE -> context.app().document().forecastAvailable() ? context.app().document().forecast().balanceAt(context.app().today()).formatPlain() : "";
                case PURPOSE_CUSTOM_MONTHS -> "12";
                case PURPOSE_CUSTOM_CURRENCY -> context.app().document().plan().currency();
                default -> "";
            });
        }
        /**
         * Проверяет значение согласно назначению: имя плана, денежную сумму, диапазон месяцев
         * или непустую валюту длиной до 10 кодовых точек без вертикальной черты и переводов строк.
         * Форматирует отображаемое поле и блокирует подтверждение при ошибке.
         *
         * @param state текущее значение поля
         * @param context контекст для пояснения и типа поля
         * @return представление с локализованной ошибкой либо без проблемы
         */
        @Override public FormView evaluate(FormState state, FormContext context) {
            Optional<String> error = validation(state.value("value"));
            return new FormView(0, 0, header(context), Map.of("value", FieldView.of(FieldCodec.display(field(context).kind(), state.value("value")))), error.map(Problem::error).orElse(Problem.NONE), Map.of(okId(), error.isEmpty() ? ru.cashprediction.core.ui.form.ButtonView.ENABLED : ru.cashprediction.core.ui.form.ButtonView.DISABLED), List.of(), List.of(), "", false);
        }
        /**
         * При отмене закрывает форму без результата; для любой другой кнопки повторно проверяет значение.
         * Ошибка оставляет форму открытой с пояснением. Корректное значение возвращает как сумму,
         * целое число месяцев или строку имени/валюты с удалёнными краевыми пробелами.
         * Изменение плана по результату выполняет вызывающий код.
         *
         * @param buttonId идентификатор нажатой кнопки
         * @param state текущее значение поля
         * @param context контекст формы
         * @return закрытие с типизированным результатом или {@code null}, либо сохранение формы с ошибкой
         */
        @Override public FormOutcome onButton(String buttonId, FormState state, FormContext context) {
            if (ButtonSpecs.CANCEL.equals(buttonId)) return new FormOutcome.Close(null);
            Optional<String> error = validation(state.value("value"));
            if (error.isPresent()) return new FormOutcome.Stay(Problem.error(error.get()));
            Object result = switch (purpose) {
                case PURPOSE_RECONCILE -> FieldCodec.parseMoney(state.value("value")).orElse(Money.ZERO);
                case PURPOSE_CUSTOM_MONTHS -> FieldCodec.parseLong(state.value("value")).isPresent() ? (int) FieldCodec.parseLong(state.value("value")).getAsLong() : 0;
                default -> state.value("value").strip();
            };
            return new FormOutcome.Close(result);
        }
        private String title(FormContext context) { return switch (purpose) {
            case PURPOSE_RENAME -> UiText.get("dialog.rename.title", context.app().document().plan().name());
            case PURPOSE_RECONCILE -> UiText.get("dialog.reconcile.title", UiFormats.date(context.app().today()));
            case PURPOSE_CUSTOM_MONTHS -> UiText.get("dialog.customMonths.title");
            case PURPOSE_CUSTOM_CURRENCY -> UiText.get("dialog.customCurrency.title");
            default -> "";
        }; }
        private String header(FormContext context) { return switch (purpose) {
            case PURPOSE_RECONCILE -> UiText.get("dialog.reconcile.header", context.app().document().forecastAvailable() ? context.app().document().forecast().balanceAt(context.app().today()).format(context.app().document().plan().currency()) : "");
            case PURPOSE_RENAME -> context.app().document().fileOptional().isPresent() ? UiText.get("dialog.rename.file") : "";
            default -> "";
        }; }
        private ru.cashprediction.core.ui.form.FieldSpec field(FormContext context) { return switch (purpose) {
            case PURPOSE_RECONCILE -> FieldSpecs.money("value", UiText.get("dialog.reconcile.value"));
            case PURPOSE_CUSTOM_MONTHS -> FieldSpecs.spinner("value", UiText.get("dialog.customMonths.value"), 1, 600);
            case PURPOSE_CUSTOM_CURRENCY -> FieldSpecs.text("value", UiText.get("dialog.customCurrency.value"), "");
            default -> FieldSpecs.text("value", UiText.get("dialog.rename.value"), "");
        }; }
        private Optional<String> validation(String value) { return switch (purpose) {
            case PURPOSE_RENAME -> PlanValidator.checkPlanName(value);
            case PURPOSE_RECONCILE -> FieldChecks.money(UiText.get("dialog.reconcile.value"), value, FieldChecks.MoneyRule.ANY).map(x -> UiText.get("dialog.reconcile.error"));
            case PURPOSE_CUSTOM_MONTHS -> FieldCodec.parseLong(value).isPresent() && FieldCodec.parseLong(value).getAsLong() >= 1 && FieldCodec.parseLong(value).getAsLong() <= 600 ? Optional.empty() : Optional.of(UiText.get("dialog.customMonths.error"));
            case PURPOSE_CUSTOM_CURRENCY -> currencyError(value);
            default -> Optional.empty();
        }; }
        private Optional<String> currencyError(String value) { if (value == null || value.isBlank()) return Optional.of(UiText.get("dialog.customCurrency.required")); if (value.codePointCount(0, value.length()) > 10) return Optional.of(UiText.get("dialog.customCurrency.long")); return value.contains("|") || value.contains("\n") || value.contains("\r") ? Optional.of(UiText.get("dialog.customCurrency.invalid")) : Optional.empty(); }
        private List<ButtonSpec> buttons() { return List.of(ButtonSpecs.of(okId(), UiText.get(okText()), ButtonRole.OK), ButtonSpecs.cancel()); }
        private String okId() { return switch (purpose) { case PURPOSE_RENAME -> "rename"; case PURPOSE_RECONCILE -> "reconcile"; default -> "apply"; }; }
        private String okText() { return switch (purpose) { case PURPOSE_RENAME -> "button.rename"; case PURPOSE_RECONCILE -> "button.reconcile"; default -> "button.apply"; }; }
    }
}
