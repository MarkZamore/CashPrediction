package ru.cashprediction.core.ui.alert;

import java.util.List;
import java.util.Objects;

/**
 * Описание сообщения (архитектура §3.5; спецификация v2, §6.0 «Каркас сообщения»): JavaFX {@code Alert} → Swing
 * {@code SwingAlert} → Web {@code <dialog>}.
 *
 * @param kind            тип
 * @param purpose         назначение ({@code unsavedChanges}, {@code deleteRule}, …) — ключ дампа и снимка
 * @param targetId        id удаляемой операции для deleteRule/deleteOneTime или пустая строка
 * @param windowTitle     заголовок окна
 * @param glyph           значок полосы заголовка из {@code DesignTokens.GLYPHS} (например ⟲ у «Восстановление
 *                        сеанса», §1.4, §6.29) или пустая строка — стандартный значок типа {@code kind}
 * @param header          заголовок-текст (жирно)
 * @param content         содержимое (переводы строк сохраняются)
 * @param details         подробности (моноширинно) или пустая строка
 * @param detailsExpanded раскрыты ли подробности
 * @param minWidth        минимальная ширина (460, для «Последний снимок» 760, для восстановления 720)
 * @param buttons         кнопки в визуальном порядке
 * @param defaultButtonId кнопка по умолчанию (Enter)
 * @param restorable      восстанавливается ли после сбоя (deleteRule, deleteOneTime, actualize, applyWhatIf, clearSnapshots)
 */
public record AlertSpec(AlertKind kind, String purpose, String targetId, String windowTitle, String glyph,
                        String header, String content, String details, boolean detailsExpanded, int minWidth,
                        List<AlertButton> buttons, String defaultButtonId, boolean restorable) {

    /** Проверяет поля и копирует список. */
    public AlertSpec {
        Objects.requireNonNull(kind, "kind");
        purpose = Objects.requireNonNullElse(purpose, "");
        targetId = Objects.requireNonNullElse(targetId, "");
        windowTitle = Objects.requireNonNullElse(windowTitle, "");
        glyph = Objects.requireNonNullElse(glyph, "");
        header = Objects.requireNonNullElse(header, "");
        content = Objects.requireNonNullElse(content, "");
        details = Objects.requireNonNullElse(details, "");
        buttons = List.copyOf(Objects.requireNonNull(buttons, "buttons"));
        defaultButtonId = Objects.requireNonNullElse(defaultButtonId, "");
    }
}
