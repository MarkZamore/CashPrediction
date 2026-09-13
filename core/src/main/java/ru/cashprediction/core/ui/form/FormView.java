package ru.cashprediction.core.ui.form;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Модель формы в конкретный момент (архитектура §3.5): всё, что меняется при вводе, — заголовок, поля, строка
 * проблем, кнопки, результаты, предпросмотр, подробности. Клиент применяет её целиком при каждом
 * {@code WindowHandle.update}.
 *
 * @param revision        монотонный номер модели; web отвечает эхом {@code form.view{echoOf}} и не перезаписывает поле в фокусе
 * @param page            номер текущей страницы (0 для обычной формы)
 * @param header          текст полосы заголовка ({@code font.header}, до 3 строк; переводы строк сохраняются)
 * @param fields          состояния полей по id (поля без записи — видимые, доступные, без изменения текста)
 * @param problem         строка проблем
 * @param buttons         состояния кнопок панели и кнопок внутри формы по id
 * @param results         строки результата
 * @param preview         элементы списка предпросмотра
 * @param details         текст подробностей (моноширинное поле 80×16) или пустая строка — ссылки нет
 * @param detailsExpanded раскрыты ли подробности
 */
public record FormView(long revision, int page, String header, Map<String, FieldView> fields, Problem problem,
                       Map<String, ButtonView> buttons, List<ResultLine> results, List<PreviewItem> preview,
                       String details, boolean detailsExpanded) {

    /** Проверяет поля и копирует коллекции. */
    public FormView {
        header = Objects.requireNonNullElse(header, "");
        fields = fields == null ? Map.of() : Map.copyOf(fields);
        problem = problem == null ? Problem.NONE : problem;
        buttons = buttons == null ? Map.of() : Map.copyOf(buttons);
        results = results == null ? List.of() : List.copyOf(results);
        preview = preview == null ? List.of() : List.copyOf(preview);
        details = Objects.requireNonNullElse(details, "");
    }
}
