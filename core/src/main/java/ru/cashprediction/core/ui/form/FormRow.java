package ru.cashprediction.core.ui.form;

import java.util.List;
import java.util.Objects;

/**
 * Строка сетки формы (архитектура §3.5; спецификация v2, §6.0 «Каркас формы»): подписи выровнены вправо с «:»,
 * минимальная ширина 150, промежутки 10/8; раздел — разделитель и жирная подпись; «широкая» строка — обе колонки.
 */
public sealed interface FormRow
        permits FormRow.Field, FormRow.Inline, FormRow.Section, FormRow.Hint, FormRow.SideColumn, FormRow.Results {

    /**
     * Одно поле: подпись слева, элемент справа (или на обе колонки, если {@code field.wide()}).
     *
     * @param field поле
     */
    record Field(FieldSpec field) implements FormRow {
        /** Проверяет поле. */
        public Field {
            Objects.requireNonNull(field, "field");
        }
    }

    /**
     * Несколько элементов в одной строке под общей подписью, например «Горизонт прогноза»: спиннер + радио
     * «месяцев»/«лет», или «Начало»: флажок «с даты» + дата.
     *
     * <p>Подпись слева одна — {@link #label()}; у элементов своя подпись не рисуется, кроме текста флажка CHECK.
     * Радио с тем же {@code id} в следующей строке («до даты» во второй строке горизонта; у второй строки
     * {@code label} пустой) — та же группа переключателей, см. {@link FieldSpec}.</p>
     *
     * @param label  общая подпись или пустая строка — строка-продолжение без подписи слева
     * @param fields элементы слева направо
     */
    record Inline(String label, List<FieldSpec> fields) implements FormRow {
        /** Проверяет поля и копирует список. */
        public Inline {
            label = Objects.requireNonNullElse(label, "");
            fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
        }
    }

    /**
     * Раздел: разделитель и жирная подпись («Когда повторяется», «Результат»).
     *
     * @param caption подпись раздела
     */
    record Section(String caption) implements FormRow {
        /** Заменяет {@code null}. */
        public Section {
            caption = Objects.requireNonNullElse(caption, "");
        }
    }

    /**
     * Неизменная широкая подсказка ({@code text.muted}). Меняющийся широкий текст делается полем
     * {@link FieldKind#PREVIEW} с {@code wide = true}.
     *
     * @param id   id подсказки (для дампа)
     * @param text текст
     */
    record Hint(String id, String text) implements FormRow {
        /** Проверяет поля. */
        public Hint {
            Objects.requireNonNull(id, "id");
            text = Objects.requireNonNullElse(text, "");
        }
    }

    /**
     * Колонка справа от формы через вертикальный разделитель (предпросмотр дат редактора правила, ширина 300).
     *
     * @param caption     жирная подпись («Ближайшие даты»)
     * @param preview     поле {@link FieldKind#PREVIEW}, элементы — {@code FormView.preview}
     * @param buttons     кнопки на всю ширину под списком
     * @param contextMenu есть ли у списка контекстное меню ({@code MenuModels.contextMenu(ContextTarget.Preview)})
     */
    record SideColumn(String caption, FieldSpec preview, List<FormButtonSpec> buttons, boolean contextMenu)
            implements FormRow {
        /** Проверяет поля и копирует список. */
        public SideColumn {
            caption = Objects.requireNonNullElse(caption, "");
            Objects.requireNonNull(preview, "preview");
            buttons = List.copyOf(Objects.requireNonNull(buttons, "buttons"));
        }
    }

    /**
     * Место для строк результата ({@code FormView.results}).
     *
     * @param id id блока
     */
    record Results(String id) implements FormRow {
        /** Проверяет id. */
        public Results {
            Objects.requireNonNull(id, "id");
        }
    }

    /**
     * Кнопка внутри формы (не в панели кнопок): id, текст и подсказка; доступность — в {@code FormView.buttons}.
     *
     * @param id      id кнопки (нажатие приходит в {@code FormLogic.onButton})
     * @param text    текст
     * @param tooltip подсказка
     */
    record FormButtonSpec(String id, String text, String tooltip) {
        /** Проверяет поля. */
        public FormButtonSpec {
            Objects.requireNonNull(id, "id");
            text = Objects.requireNonNullElse(text, "");
            tooltip = Objects.requireNonNullElse(tooltip, "");
        }
    }
}
