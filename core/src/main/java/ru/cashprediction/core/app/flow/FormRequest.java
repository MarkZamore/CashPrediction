package ru.cashprediction.core.app.flow;

import java.util.Map;
import java.util.Objects;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.form.FormLogic;

/**
 * Запрос на открытие формы — общий путь меню, дочерних окон и восстановления (архитектура §3.8,
 * {@code FlowContext.openForm}, {@code CoreWindowFactory}).
 *
 * @param logic    логика формы
 * @param type     тип окна
 * @param modal    модальное ли (для GOAL_CALCULATOR и QUICK_EDIT_POPUP — {@code false})
 * @param context  контекст окна ({@code mode}, {@code ruleId}, {@code purpose}, …)
 * @param restored состояние из снимка (id окна, значения, страница, границы) или {@code null} для новой формы
 */
public record FormRequest(FormLogic logic, WindowType type, boolean modal, Map<String, String> context,
                          WindowState restored) {

    /** Проверяет поля и копирует карту. */
    public FormRequest {
        Objects.requireNonNull(logic, "logic");
        Objects.requireNonNull(type, "type");
        context = context == null ? Map.of() : Map.copyOf(context);
    }

    /**
     * Новая форма (не из снимка).
     *
     * @param logic   логика формы
     * @param type    тип окна
     * @param modal   модальное ли
     * @param context контекст окна
     * @return запрос
     */
    public static FormRequest fresh(FormLogic logic, WindowType type, boolean modal, Map<String, String> context) {
        return new FormRequest(logic, type, modal, context, null);
    }
}
