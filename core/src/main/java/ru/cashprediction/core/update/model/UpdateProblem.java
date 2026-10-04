package ru.cashprediction.core.update.model;

import java.util.Objects;

/**
 * Машинная причина отказа lifecycle/подготовки без Throwable, пути или транспорта.
 * Не является уведомлением пользователю и не изменяет политику повторов и безопасного запуска.
 * @param code категория отказа
 * @param detail исходная диагностика для локального журнала или адаптера службы
 */
public record UpdateProblem(Code code, String detail) {
    /** Категории ожидаемых отказов разных стадий. */
    public enum Code {
        /** Не удалось подключить локальный установщик. */ INITIALIZATION_FAILED,
        /** Локальный барьер отказал, решение допуска UI сохраняется отдельно. */ BARRIER_FAILED,
        /** Подготовка не завершилась после действующей политики повторов. */ PREPARATION_FAILED,
        /** Закрытие подготовителя не завершилось. */ CANCELLATION_FAILED,
        /** Планирование установки после выхода не завершилось. */ EXIT_FAILED
    }
    /** Проверяет обязательную категорию и нормализует отсутствие описания. */
    public UpdateProblem { Objects.requireNonNull(code, "code"); detail = Objects.requireNonNullElse(detail, ""); }
}
