package ru.cashprediction.core.ui.form;

import java.util.List;
import ru.cashprediction.core.ui.text.UiText;

/**
 * Короткие фабрики {@link FieldSpec} для логик форм ({@code core.ui.forms.*}) с умолчаниями спецификации v2, §6.0
 * «Закрытый набор элементов формы»: у суммы подсказка «0,00», у даты «ДД.ММ.ГГГГ». Изменение одного свойства -
 * методы {@code with*} (запись неизменяема, они возвращают копию).
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class FieldSpecs {

    private FieldSpecs() {
    }

    /**
     * Однострочное поле.
     *
     * @param id     id поля
     * @param label  подпись
     * @param prompt подсказка в пустом поле или пустая строка
     * @return поле
     */
    public static FieldSpec text(String id, String label, String prompt) {
        return of(id, FieldKind.TEXT, label, prompt, List.of(), 0, 0, 0, 0);
    }

    /**
     * Многострочное поле.
     *
     * @param id    id поля
     * @param label подпись
     * @param rows  видимых строк
     * @return поле
     */
    public static FieldSpec multiline(String id, String label, int rows) {
        return of(id, FieldKind.MULTILINE, label, "", List.of(), 0, 0, 0, rows);
    }

    /**
     * Сумма с подсказкой «0,00» ({@code field.money.prompt}).
     *
     * @param id    id поля
     * @param label подпись
     * @return поле
     */
    public static FieldSpec money(String id, String label) {
        return of(id, FieldKind.MONEY, label, UiText.get("field.money.prompt"), List.of(), 0, 0, 0, 0);
    }

    /**
     * Дата с подсказкой «ДД.ММ.ГГГГ» ({@code field.date.prompt}) и кнопкой календаря.
     *
     * @param id    id поля
     * @param label подпись
     * @return поле
     */
    public static FieldSpec date(String id, String label) {
        return of(id, FieldKind.DATE, label, UiText.get("field.date.prompt"), List.of(), 0, 0, 0, 0);
    }

    /**
     * День года (ДД.ММ или ММ-ДД).
     *
     * @param id     id поля
     * @param label  подпись
     * @param prompt подсказка
     * @return поле
     */
    public static FieldSpec monthDay(String id, String label, String prompt) {
        return of(id, FieldKind.MONTH_DAY, label, prompt, List.of(), 0, 0, 0, 0);
    }

    /**
     * Целое с шагом 1.
     *
     * @param id    id поля
     * @param label подпись
     * @param min   минимум
     * @param max   максимум
     * @return поле
     */
    public static FieldSpec spinner(String id, String label, long min, long max) {
        return of(id, FieldKind.SPINNER, label, "", List.of(), min, max, 1, 0);
    }

    /**
     * Выпадающий список.
     *
     * @param id      id поля
     * @param label   подпись
     * @param options варианты
     * @return поле
     */
    public static FieldSpec choice(String id, String label, List<Option> options) {
        return of(id, FieldKind.CHOICE, label, "", options, 0, 0, 0, 0);
    }

    /**
     * Список с вводом.
     *
     * @param id      id поля
     * @param label   подпись
     * @param options варианты
     * @return поле
     */
    public static FieldSpec editableChoice(String id, String label, List<Option> options) {
        return of(id, FieldKind.EDITABLE_CHOICE, label, "", options, 0, 0, 0, 0);
    }

    /**
     * Флажок; {@code text} - собственный текст флажка справа от квадрата.
     *
     * @param id   id поля
     * @param text текст флажка
     * @return поле
     */
    public static FieldSpec check(String id, String text) {
        return of(id, FieldKind.CHECK, text, "", List.of(), 0, 0, 0, 0);
    }

    /**
     * Группа радиокнопок.
     *
     * @param id          id поля
     * @param label       подпись
     * @param orientation направление
     * @param options     варианты
     * @return поле
     */
    public static FieldSpec radio(String id, String label, Orientation orientation, List<Option> options) {
        return new FieldSpec(id, FieldKind.RADIO, label, "", "", options, 0, 0, 0, 0, 0, false, "", orientation, false);
    }

    /**
     * Список с выбором.
     *
     * @param id      id поля
     * @param label   подпись
     * @param rows    видимых строк
     * @param options варианты
     * @return поле
     */
    public static FieldSpec list(String id, String label, int rows, List<Option> options) {
        return of(id, FieldKind.LIST, label, "", options, 0, 0, 0, rows);
    }

    /**
     * Поле только для чтения (текст - {@code FieldView.value}, список - {@code FormView.preview}).
     *
     * @param id    id поля
     * @param label подпись
     * @return поле
     */
    public static FieldSpec preview(String id, String label) {
        return of(id, FieldKind.PREVIEW, label, "", List.of(), 0, 0, 0, 0);
    }

    /**
     * Кнопка внутри формы.
     *
     * @param id   id кнопки (нажатие приходит в {@code FormLogic.onButton})
     * @param text текст
     * @return поле
     */
    public static FieldSpec button(String id, String text) {
        return of(id, FieldKind.BUTTON, text, "", List.of(), 0, 0, 0, 0);
    }

    /**
     * Копия, занимающая обе колонки сетки.
     *
     * @param field поле
     * @return копия
     */
    public static FieldSpec wide(FieldSpec field) {
        return new FieldSpec(field.id(), field.kind(), field.label(), field.prompt(), field.tooltip(), field.options(),
                field.min(), field.max(), field.step(), field.columns(), field.textRows(), true, field.suffix(),
                field.orientation(), field.focusFirst());
    }

    /**
     * Копия, получающая фокус при открытии формы (текст выделяется целиком).
     *
     * @param field поле
     * @return копия
     */
    public static FieldSpec focused(FieldSpec field) {
        return new FieldSpec(field.id(), field.kind(), field.label(), field.prompt(), field.tooltip(), field.options(),
                field.min(), field.max(), field.step(), field.columns(), field.textRows(), field.wide(), field.suffix(),
                field.orientation(), true);
    }

    /**
     * Копия с подсказкой.
     *
     * @param field   поле
     * @param tooltip всплывающая подсказка
     * @return копия
     */
    public static FieldSpec withTooltip(FieldSpec field, String tooltip) {
        return new FieldSpec(field.id(), field.kind(), field.label(), field.prompt(), tooltip, field.options(),
                field.min(), field.max(), field.step(), field.columns(), field.textRows(), field.wide(), field.suffix(),
                field.orientation(), field.focusFirst());
    }

    /**
     * Копия с подсказкой в пустом поле.
     *
     * @param field  поле
     * @param prompt подсказка
     * @return копия
     */
    public static FieldSpec withPrompt(FieldSpec field, String prompt) {
        return new FieldSpec(field.id(), field.kind(), field.label(), prompt, field.tooltip(), field.options(),
                field.min(), field.max(), field.step(), field.columns(), field.textRows(), field.wide(), field.suffix(),
                field.orientation(), field.focusFirst());
    }

    /**
     * Копия с подписью справа (валюта у суммы).
     *
     * @param field  поле
     * @param suffix подпись
     * @return копия
     */
    public static FieldSpec withSuffix(FieldSpec field, String suffix) {
        return new FieldSpec(field.id(), field.kind(), field.label(), field.prompt(), field.tooltip(), field.options(),
                field.min(), field.max(), field.step(), field.columns(), field.textRows(), field.wide(), suffix,
                field.orientation(), field.focusFirst());
    }

    /**
     * Копия с шириной в символах.
     *
     * @param field   поле
     * @param columns ширина
     * @return копия
     */
    public static FieldSpec withColumns(FieldSpec field, int columns) {
        return new FieldSpec(field.id(), field.kind(), field.label(), field.prompt(), field.tooltip(), field.options(),
                field.min(), field.max(), field.step(), columns, field.textRows(), field.wide(), field.suffix(),
                field.orientation(), field.focusFirst());
    }

    private static FieldSpec of(String id, FieldKind kind, String label, String prompt, List<Option> options, long min,
                                long max, long step, int rows) {
        return new FieldSpec(id, kind, label, prompt, "", options, min, max, step, 0, rows, false, "",
                Orientation.HORIZONTAL, false);
    }
}
