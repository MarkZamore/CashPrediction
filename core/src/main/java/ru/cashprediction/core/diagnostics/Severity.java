package ru.cashprediction.core.diagnostics;

import ru.cashprediction.core.text.Texts;

/**
 * Важность диагностического сообщения.
 *
 * <p>Используется и при чтении файлов плана (нераспознанные строки), и при проверке плана
 * ({@code PlanValidator}), и в предупреждениях прогноза.</p>
 *
 * <p>Подпись берётся из общего каталога текстов ({@code diagnostics_ru.properties}) при каждом вызове
 * {@link #title()}, а не в конструкторе константы: отсутствующий ключ не должен ломать загрузку перечисления.</p>
 */
public enum Severity {
    /** Справка: программа что-то подставила сама, действий не требуется. */
    INFO,
    /** Предупреждение: план работает, но стоит проверить. */
    WARNING,
    /** Ошибка: часть данных не попала в прогноз, нужно исправить. */
    ERROR;

    /** @return подпись для интерфейса: «Сведения», «Предупреждение», «Ошибка» */
    public String title() {
        return switch (this) {
            case INFO -> Texts.get("severity.info");
            case WARNING -> Texts.get("severity.warning");
            case ERROR -> Texts.get("severity.error");
        };
    }
}
