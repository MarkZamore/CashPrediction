package ru.cashprediction.core.service.plan;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Неизменяемый результат; отказ содержит исходный снимок, успешный запрос содержит итоговый. */
public record PlanCommandResult(UUID requestId, Status status, PlanCommandSnapshot snapshot,
                                PlanCommandEffect effect, List<PlanCommandProblem> problems) {
    /** Исход исполнения или проверки без записи. */
    public enum Status {
        /** План и один шаг истории записаны. */
        APPLIED,
        /** Результат равен исходному плану, история и ревизия сохранены. */
        UNCHANGED,
        /** Запрос отклонён до изменения документа. */
        REJECTED,
        /** Получен проверенный проект результата без записи и без дедупликации. */
        PREVIEW
    }

    /** Копирует список проблем и проверяет обязательные значения. */
    public PlanCommandResult {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(effect, "effect");
        problems = List.copyOf(problems);
    }

    /** @return запрос принят, включая равный план и предпросмотр */
    public boolean accepted() { return status != Status.REJECTED; }

    /** @return выполнение создало изменение текущего плана */
    public boolean changed() { return status == Status.APPLIED; }
}
