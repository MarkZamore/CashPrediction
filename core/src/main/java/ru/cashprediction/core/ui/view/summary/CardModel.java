package ru.cashprediction.core.ui.view.summary;

import java.time.LocalDate;
import java.util.Objects;
import ru.cashprediction.core.ui.token.ColorToken;

/**
 * Карточка панели сводки (спецификация v2, §5.1): три строки — заголовок, значение, подпись.
 *
 * <p>Всплывающее окно при наведении (JavaFX {@code PopupControl} + Skin → Swing {@code SwingPopupControl} → Web
 * div-popover) получает график через запрос {@code UiIntents.sparkline(id)}; первая жирная строка и пояснение
 * лежат здесь, чтобы клиент не собирал тексты сам.</p>
 *
 * @param id           идентификатор: {@code now}, {@code m1}, {@code m3}, {@code m6}, {@code m12}, {@code min},
 *                     {@code firstNegative}, {@code avg}, {@code goal}
 * @param title        заголовок («Сейчас», «Через 3 месяца», …)
 * @param value        значение («177 000 ₽», «—», «не задана»)
 * @param valueColor   цвет значения
 * @param caption      подпись («на 13.09.2026», «dd.MM.yyyy · +46 654»)
 * @param captionColor цвет подписи
 * @param date         дата карточки для «Показать в таблице» или {@code null}
 * @param popupHeader  первая жирная строка всплывающего окна («Сейчас: 177 000,00 ₽»)
 * @param explanation  пояснение всплывающего окна
 */
public record CardModel(String id, String title, String value, ColorToken valueColor, String caption,
                        ColorToken captionColor, LocalDate date, String popupHeader, String explanation) {

    /** Проверяет обязательные поля. */
    public CardModel {
        Objects.requireNonNull(id, "id");
        title = Objects.requireNonNullElse(title, "");
        value = Objects.requireNonNullElse(value, "");
        valueColor = valueColor == null ? ColorToken.TEXT_PRIMARY : valueColor;
        caption = Objects.requireNonNullElse(caption, "");
        captionColor = captionColor == null ? ColorToken.TEXT_MUTED : captionColor;
        popupHeader = Objects.requireNonNullElse(popupHeader, "");
        explanation = Objects.requireNonNullElse(explanation, "");
    }
}
