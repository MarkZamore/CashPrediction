package ru.cashprediction.core.service.plan;

import java.util.Objects;
import java.util.Optional;
import ru.cashprediction.core.model.Plan;

/** Неизменяемый снимок плана и доступной истории; ревизия не зависит от настроек и перерисовки. */
public record PlanCommandSnapshot(long revision, Plan plan, Optional<String> undoDescription,
                                  Optional<String> redoDescription) {
    /** Проверяет обязательные неизменяемые значения снимка. */
    public PlanCommandSnapshot {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(undoDescription, "undoDescription");
        Objects.requireNonNull(redoDescription, "redoDescription");
    }
}
