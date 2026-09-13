package ru.cashprediction.core.ui.form;

import java.util.Map;
import java.util.Optional;

/**
 * Поведение одной формы (архитектура §3.5): раскладка, значения по умолчанию, проверка и кнопки. Реализации —
 * классы {@code core.ui.forms.*}; тексты только из каталога. Логика не хранит состояния ввода: всё в
 * {@link FormState} (значения, страница, выбор в предпросмотре), поэтому восстановление после сбоя — это просто
 * {@code evaluate} над значениями из снимка.
 *
 * <p>Методы вызываются в потоке контроллера и не должны бросать исключений из-за ввода или плана: ошибки
 * расчёта показываются в строке проблем (например, «✖ Прогноз не рассчитан: …» калькулятора цели).</p>
 */
public interface FormLogic {

    /**
     * Неизменная раскладка формы (может зависеть от контекста: режим создания или изменения).
     *
     * @param context окружение формы
     * @return раскладка
     */
    FormSpec spec(FormContext context);

    /**
     * Значения полей по умолчанию для новой формы (канонические формы {@code FieldCodec}).
     *
     * @param context окружение формы
     * @return значения по id поля
     */
    Map<String, String> defaults(FormContext context);

    /**
     * Модель формы для введённых значений: видимость, доступность, строка проблем, кнопки, предпросмотр.
     *
     * @param state   введённые значения
     * @param context окружение формы
     * @return модель; ревизию проставляет {@code FormSession}
     */
    FormView evaluate(FormState state, FormContext context);

    /**
     * Нажата кнопка панели или кнопка внутри формы (для CANCEL вызывается тоже).
     *
     * @param buttonId id кнопки
     * @param state    введённые значения
     * @param context  окружение формы
     * @return что делать дальше
     */
    FormOutcome onButton(String buttonId, FormState state, FormContext context);

    /**
     * Выбран элемент предпросмотра (одиночный выбор) или активирован (двойной щелчок, пункт контекстного меню).
     * Выбранный индекс уже записан в {@code state.previewIndex()}.
     *
     * @param index     номер элемента
     * @param activated {@code true} для двойного щелчка или «Скорректировать эту дату…»
     * @param state     введённые значения
     * @param context   окружение формы
     * @return что делать дальше; по умолчанию — остаться
     */
    default FormOutcome onPreview(int index, boolean activated, FormState state, FormContext context) {
        return FormOutcome.stay();
    }

    /**
     * Элемент списка ({@code FieldKind.LIST}) активирован двойным щелчком или Enter; выбранное значение уже в
     * {@code state}. Например, «Открыть план» возвращает {@code onButton("open", …)}, «Выбор файла» для папки —
     * {@code FormOutcome.SetFields} с новым путём.
     *
     * @param fieldId id поля-списка
     * @param index   номер элемента
     * @param state   введённые значения
     * @param context окружение формы
     * @return что делать дальше; по умолчанию — остаться
     */
    default FormOutcome onFieldActivated(String fieldId, int index, FormState state, FormContext context) {
        return FormOutcome.stay();
    }

    /**
     * Enter в однострочном поле; текст поля уже в {@code state}.
     *
     * @param fieldId id поля
     * @param state   введённые значения
     * @param context окружение формы
     * @return свой ответ или пусто — «как Enter по форме»: {@code FormSession} нажимает кнопку по умолчанию, если она
     *         задана и доступна (по умолчанию так для всех полей)
     */
    default Optional<FormOutcome> onFieldSubmitted(String fieldId, FormState state, FormContext context) {
        return Optional.empty();
    }

    /**
     * Нужно ли пересчитывать форму при изменении плана извне (калькулятор цели — да, §6.6).
     *
     * @return {@code true}, если {@code FormSession} должен вызвать {@code evaluate} после события документа
     */
    default boolean reevaluateOnDocumentChange() {
        return false;
    }
}
