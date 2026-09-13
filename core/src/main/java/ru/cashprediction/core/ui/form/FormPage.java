package ru.cashprediction.core.ui.form;

import java.util.List;
import java.util.Objects;

/**
 * Страница формы. У обычной формы одна страница, у мастера — три (§6.1); заголовок страницы приходит в
 * {@code FormView.header}.
 *
 * @param id   id страницы ({@code main}, {@code page1}, …)
 * @param rows строки сетки сверху вниз
 */
public record FormPage(String id, List<FormRow> rows) {

    /** Проверяет поля и копирует список. */
    public FormPage {
        Objects.requireNonNull(id, "id");
        rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
    }
}
