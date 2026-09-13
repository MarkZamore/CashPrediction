package ru.cashprediction.core.markdown;

import java.util.List;
import java.util.Objects;
import ru.cashprediction.core.diagnostics.Diagnostic;
import ru.cashprediction.core.diagnostics.Severity;
import ru.cashprediction.core.model.Plan;

/**
 * Результат чтения файла плана: сам план и всё, что читатель заметил по дороге.
 *
 * <p>Чтение никогда не «проваливается» из-за отдельной испорченной строки: план строится из того,
 * что удалось разобрать, а проблемы перечисляются в {@code diagnostics}, чтобы интерфейс показал их
 * пользователю списком (с номерами строк).</p>
 *
 * <p>Record неизменяем и потокобезопасен.</p>
 *
 * @param plan        разобранный план
 * @param diagnostics сообщения читателя в порядке строк файла
 */
public record ReadResult(Plan plan, List<Diagnostic> diagnostics) {

    /** Проверяет обязательные поля и делает список неизменяемым. */
    public ReadResult {
        Objects.requireNonNull(plan, "plan");
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    /**
     * Есть ли среди сообщений ошибки, то есть данные, не попавшие в план.
     *
     * @return {@code true}, если есть хотя бы одно сообщение уровня {@link Severity#ERROR}
     */
    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> d.severity() == Severity.ERROR);
    }

    /**
     * Есть ли сообщения, заслуживающие внимания пользователя.
     *
     * @return {@code true}, если есть предупреждения или ошибки
     */
    public boolean hasWarnings() {
        return diagnostics.stream().anyMatch(d -> d.severity() != Severity.INFO);
    }
}
