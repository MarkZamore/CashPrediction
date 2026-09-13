package ru.cashprediction.core.session;

import java.util.List;

/**
 * Итог восстановления сессии: что удалось и о чём предупредить пользователя.
 *
 * <p>Запись неизменяема и потокобезопасна.</p>
 *
 * @param warnings        предупреждения на русском в порядке возникновения
 * @param windowsRestored сколько окон показано
 */
public record RestoreReport(List<String> warnings, int windowsRestored) {

    /** Копирует список предупреждений. */
    public RestoreReport {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        if (windowsRestored < 0) {
            throw new IllegalArgumentException("Число окон не может быть отрицательным");
        }
    }

    /**
     * Проверяет, прошло ли восстановление без замечаний.
     *
     * @return {@code true}, если предупреждений нет
     */
    public boolean clean() {
        return warnings.isEmpty();
    }
}
