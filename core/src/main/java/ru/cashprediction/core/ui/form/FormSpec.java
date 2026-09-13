package ru.cashprediction.core.ui.form;

import java.util.List;
import java.util.Objects;
import ru.cashprediction.core.session.WindowType;

/**
 * Неизменная раскладка формы (архитектура §3.5; спецификация v2, §6.0 «Каркас формы»): заголовок окна, значок,
 * ширина, модальность, страницы и панель кнопок. Меняющиеся тексты и состояния приходят в {@link FormView}.
 *
 * @param formId          id формы для дампа и сценариев ({@code ruleEditor}, {@code rename}, …)
 * @param windowType      тип окна снимка
 * @param purpose         назначение для TEXT_INPUT/CHOICE/ALERT ({@code rename}, {@code currency}, …) или пустая строка
 * @param presentation    каким классом инструмента показывать
 * @param windowTitle     заголовок окна ({@code dialog.<id>.title})
 * @param glyph           значок полосы заголовка из {@code DesignTokens.GLYPHS} (26 px, {@code accent}) или пустая строка
 * @param width           минимальная ширина контента ({@code DesignTokens.dialogWidth})
 * @param modal           модальное ли (калькулятор цели и быстрая правка — нет)
 * @param resizable       изменяемый ли размер
 * @param restorable      восстанавливается ли после сбоя (регистрируется в записи сеанса после показа)
 * @param pages           страницы (у мастера три)
 * @param buttons         кнопки в визуальном порядке
 * @param defaultButtonId id кнопки по умолчанию (Enter) или пустая строка («Enter ничего не делает», §6.6)
 */
public record FormSpec(String formId, WindowType windowType, String purpose, Presentation presentation,
                       String windowTitle, String glyph, int width, boolean modal, boolean resizable,
                       boolean restorable, List<FormPage> pages, List<ButtonSpec> buttons, String defaultButtonId) {

    /** Проверяет поля и копирует списки. */
    public FormSpec {
        Objects.requireNonNull(formId, "formId");
        Objects.requireNonNull(windowType, "windowType");
        purpose = Objects.requireNonNullElse(purpose, "");
        Objects.requireNonNull(presentation, "presentation");
        windowTitle = Objects.requireNonNullElse(windowTitle, "");
        glyph = Objects.requireNonNullElse(glyph, "");
        pages = List.copyOf(Objects.requireNonNull(pages, "pages"));
        buttons = List.copyOf(Objects.requireNonNull(buttons, "buttons"));
        defaultButtonId = Objects.requireNonNullElse(defaultButtonId, "");
    }
}
