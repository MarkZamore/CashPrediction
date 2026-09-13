package ru.cashprediction.web;

import java.util.Objects;

/**
 * Конфликт состояния, который пользователь должен разрешить сам (ответ API 409).
 *
 * <p>Бросается {@link ServerState}, который ничего не знает об HTTP; {@link ApiHandler} превращает его в
 * {@code {"error": "...", "conflict": "<вид>"}}. Web-аналог диалога 18 «Файл изменён снаружи»
 * ({@code Alert} в JavaFX): браузер по полю {@code conflict} показывает кнопки «Перезаписать» и «Перечитать».</p>
 */
public final class ConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Файл плана изменён на диске после загрузки или последнего сохранения. */
    public static final String EXTERNAL_CHANGE = "externalChange";

    /** Файл с таким именем уже существует (новый план или «Сохранить как»). */
    public static final String EXISTS = "exists";

    /** Действие невозможно в текущем состоянии сеанса (например, нечего восстанавливать). */
    public static final String STATE = "state";

    /** Вид конфликта. */
    private final String kind;

    /**
     * Создаёт конфликт.
     *
     * @param kind    вид: {@link #EXTERNAL_CHANGE}, {@link #EXISTS} или {@link #STATE}
     * @param message сообщение на русском
     */
    public ConflictException(String kind, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    /** @return вид конфликта */
    public String kind() {
        return kind;
    }
}
