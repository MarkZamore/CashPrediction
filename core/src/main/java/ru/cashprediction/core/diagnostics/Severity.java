package ru.cashprediction.core.diagnostics;

/**
 * Важность диагностического сообщения.
 *
 * <p>Используется и при чтении файлов плана (нераспознанные строки), и при проверке плана
 * ({@code PlanValidator}), и в предупреждениях прогноза.</p>
 */
public enum Severity {
    /** Справка: программа что-то подставила сама, действий не требуется. */
    INFO("Сведения"),
    /** Предупреждение: план работает, но стоит проверить. */
    WARNING("Предупреждение"),
    /** Ошибка: часть данных не попала в прогноз, нужно исправить. */
    ERROR("Ошибка");

    private final String title;

    Severity(String title) {
        this.title = title;
    }

    /** @return подпись для интерфейса */
    public String title() {
        return title;
    }
}
