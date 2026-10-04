package ru.cashprediction.core.service.storage;

import java.util.Objects;

/** Локальный переход от структурированного исхода хранения к существующим обработчикам ошибок потоков UI. */
public final class PlanStorageException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final PlanStorage.Problem problem;

    /** @param problem структурированная причина отказа */
    public PlanStorageException(PlanStorage.Problem problem) {
        super(Objects.requireNonNull(problem, "problem").detail());
        this.problem = problem;
    }

    /** @return структурированная причина отказа */
    public PlanStorage.Problem problem() { return problem; }
}
