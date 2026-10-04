package ru.cashprediction.core.service.plan;

import java.util.Objects;
import java.util.UUID;

/**
 * Запрос изменения: идентификатор повтора, ожидаемая предметная ревизия, текст истории и команда.
 * Один повтор обязан передавать весь тот же запрос, включая описание и ожидаемую ревизию.
 */
public record PlanCommandRequest(UUID requestId, long expectedRevision, String description, PlanCommand command) {
    /** Проверяет оболочку запроса; бизнес-значения внутри команды проверяются службой. */
    public PlanCommandRequest {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(command, "command");
    }
}
