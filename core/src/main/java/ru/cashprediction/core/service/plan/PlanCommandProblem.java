package ru.cashprediction.core.service.plan;

import java.util.Map;
import java.util.Objects;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.TxId;

/** Код, предметный адрес и неизменяемые аргументы проблемы; message взят из общего русского каталога. */
public record PlanCommandProblem(PlanCommandError code, RuleId ruleId, TxId transactionId,
                                 OccurrenceKey occurrenceKey, Map<String, String> arguments, String message) {
    /** Защищает карту аргументов от изменения вызывающим кодом. */
    public PlanCommandProblem {
        Objects.requireNonNull(code, "code");
        arguments = Map.copyOf(arguments);
        Objects.requireNonNull(message, "message");
    }
}
