package ru.cashprediction.core.ui.form;

import java.util.Map;
import java.util.Objects;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.session.WindowState;

/**
 * Окружение формы для {@link FormLogic}: где открыта форма, её контекст снимка и текущее состояние приложения.
 *
 * @param windowId id окна ({@code w1}, …)
 * @param ownerId  владелец: {@link WindowState#MAIN_OWNER} или id родительского окна
 * @param context  контекст окна снимка ({@code mode}, {@code ruleId}, {@code txId}, {@code originalDate},
 *                 {@code purpose}, {@code targetId}; ключи {@code WindowType.CONTEXT_*})
 * @param app      текущий снимок состояния приложения (план, прогноз, сегодня, профиль клиента)
 */
public record FormContext(String windowId, String ownerId, Map<String, String> context, AppState app) {

    /** Проверяет поля и копирует карту. */
    public FormContext {
        Objects.requireNonNull(windowId, "windowId");
        ownerId = ownerId == null || ownerId.isBlank() ? WindowState.MAIN_OWNER : ownerId;
        context = context == null ? Map.of() : Map.copyOf(context);
        Objects.requireNonNull(app, "app");
    }

    /**
     * Значение контекста.
     *
     * @param key ключ, например {@code ruleId}
     * @return значение или пустая строка
     */
    public String contextValue(String key) {
        return context.getOrDefault(key, "");
    }

    /**
     * Та же форма с новым снимком состояния приложения (после изменения плана).
     *
     * @param newApp новое состояние
     * @return новый контекст
     */
    public FormContext withApp(AppState newApp) {
        return new FormContext(windowId, ownerId, context, newApp);
    }
}
