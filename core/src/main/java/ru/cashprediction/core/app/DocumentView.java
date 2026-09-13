package ru.cashprediction.core.app;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import ru.cashprediction.core.diagnostics.Diagnostic;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.model.Plan;

/**
 * Неизменяемый срез {@code PlanDocument} для {@link AppState}: построители моделей читают план и прогноз только
 * отсюда, а не из изменяемого документа.
 *
 * @param plan            план
 * @param file            файл плана или {@code null}, если план не сохранён
 * @param dirty           есть ли несохранённые изменения
 * @param canUndo         есть ли что отменять
 * @param undoText        описание отменяемого действия ({@code undo.*}) или пустая строка
 * @param canRedo         есть ли что повторять
 * @param redoText        описание повторяемого действия или пустая строка
 * @param forecast        прогноз или {@code null}, если он не рассчитан
 * @param forecastError   причина, по которой прогноз не рассчитан (текст исключения движка), или пустая строка
 * @param loadDiagnostics замечания при чтении файла плана
 */
public record DocumentView(Plan plan, Path file, boolean dirty, boolean canUndo, String undoText, boolean canRedo,
                           String redoText, Forecast forecast, String forecastError, List<Diagnostic> loadDiagnostics) {

    /** Проверяет поля и заменяет {@code null}. */
    public DocumentView {
        Objects.requireNonNull(plan, "plan");
        undoText = Objects.requireNonNullElse(undoText, "");
        redoText = Objects.requireNonNullElse(redoText, "");
        forecastError = Objects.requireNonNullElse(forecastError, "");
        loadDiagnostics = loadDiagnostics == null ? List.of() : List.copyOf(loadDiagnostics);
    }

    /** @return файл плана, если план сохранён */
    public Optional<Path> fileOptional() {
        return Optional.ofNullable(file);
    }

    /** @return рассчитан ли прогноз (от этого зависят CSV, PNG, график, сводка, §3.1) */
    public boolean forecastAvailable() {
        return forecast != null;
    }
}
