package ru.cashprediction.core.ui.menu;

import java.util.Objects;

/**
 * Объект, для которого открывается контекстное меню (спецификация v2, §5.5). Меню строит ядро
 * ({@code MenuModels.contextMenu}); клиент показывает его JavaFX {@code ContextMenu} по {@code ContextMenuEvent}
 * (мышь или клавиатура) → Swing {@code JPopupMenu} по {@code isPopupTrigger}/Shift+F10 → Web {@code contextmenu}.
 */
public sealed interface ContextTarget
        permits ContextTarget.Row, ContextTarget.Total, ContextTarget.PastHeader, ContextTarget.Card,
        ContextTarget.Chart, ContextTarget.Preview {

    /** @return ключ цели для дампа и web-протокола: {@code row}, {@code total}, {@code pastHeader}, {@code card}, {@code chart}, {@code preview} */
    String kind();

    /**
     * Строка события (START, RULE, ONE_TIME, WHAT_IF). Правая кнопка сначала выделяет строку.
     *
     * @param rowId идентификатор строки
     */
    record Row(String rowId) implements ContextTarget {
        /** Проверяет поле. */
        public Row {
            Objects.requireNonNull(rowId, "rowId");
        }

        @Override
        public String kind() {
            return "row";
        }
    }

    /**
     * Строка итога месяца.
     *
     * @param rowId идентификатор строки итога
     */
    record Total(String rowId) implements ContextTarget {
        /** Проверяет поле. */
        public Total {
            Objects.requireNonNull(rowId, "rowId");
        }

        @Override
        public String kind() {
            return "total";
        }
    }

    /**
     * Строка группы «Прошедшие события».
     *
     * @param rowId идентификатор строки группы
     */
    record PastHeader(String rowId) implements ContextTarget {
        /** Проверяет поле. */
        public PastHeader {
            Objects.requireNonNull(rowId, "rowId");
        }

        @Override
        public String kind() {
            return "pastHeader";
        }
    }

    /**
     * Карточка сводки.
     *
     * @param cardId идентификатор карточки ({@code now}, {@code m1}, …, {@code goal})
     */
    record Card(String cardId) implements ContextTarget {
        /** Проверяет поле. */
        public Card {
            Objects.requireNonNull(cardId, "cardId");
        }

        @Override
        public String kind() {
            return "card";
        }
    }

    /**
     * График: точка указателя и размер области рисования; дату под указателем вычисляет ядро
     * ({@code PlotTransform.dateAt}); вне области построения даты нет и пункты 1–2 отключены.
     *
     * @param x      координата x указателя
     * @param y      координата y указателя
     * @param width  ширина области рисования
     * @param height высота области рисования
     */
    record Chart(double x, double y, double width, double height) implements ContextTarget {
        @Override
        public String kind() {
            return "chart";
        }
    }

    /**
     * Элемент предпросмотра дат в редакторе правила (§6.3).
     *
     * @param windowId id окна редактора
     * @param index    номер элемента списка
     */
    record Preview(String windowId, int index) implements ContextTarget {
        /** Проверяет поле. */
        public Preview {
            Objects.requireNonNull(windowId, "windowId");
        }

        @Override
        public String kind() {
            return "preview";
        }
    }
}
