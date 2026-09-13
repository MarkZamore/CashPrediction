package ru.cashprediction.core.ui.form;

import java.util.Objects;

/**
 * Элемент списка предпросмотра (спецификация v2, §6.3): «пн, 05.10.2026», «  ⇄ с сб 03.10.2026», «  ✎ корректировка».
 *
 * @param text       готовый текст элемента
 * @param selectable можно ли выбрать элемент (заглушка «Заполните форму — здесь появятся даты» — нельзя)
 */
public record PreviewItem(String text, boolean selectable) {

    /** Заменяет {@code null}. */
    public PreviewItem {
        text = Objects.requireNonNullElse(text, "");
    }
}
