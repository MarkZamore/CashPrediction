package ru.cashprediction.core.ui.form;

import java.util.List;
import java.util.Objects;

/**
 * Неизменное описание поля формы (архитектура §3.5). Изменяемые свойства (значение, видимость, доступность,
 * подпись, варианты, подсказка, диапазон спиннера) приходят в {@link FieldView}.
 *
 * <p><b>Где показывается {@link #label()}.</b> Для большинства видов — подпись слева в сетке формы. Исключения:
 * {@link FieldKind#CHECK} — это собственный текст флажка справа от квадрата («с даты», «Операция активна…»), без
 * двоеточия и без подписи слева; внутри {@link FormRow.Inline} подпись слева одна на строку ({@code Inline.label}),
 * поэтому у CHECK {@code label} — текст флажка, у RADIO тексты берутся из вариантов, у остальных видов
 * {@code label} не рисуется (но остаётся ключом поиска поля в сценариях самотеста).</p>
 *
 * <p><b>Одна группа RADIO в нескольких строках.</b> Поле RADIO может встречаться в раскладке несколько раз с одним
 * {@code id} и разными подмножествами {@link #options()}: «Горизонт прогноза» — «месяцев»/«лет» в первой строке и
 * «до даты» во второй (§6.1, §6.2). Клиент объединяет все такие появления в одну группу переключателей по {@code id}
 * (FX {@code ToggleGroup} → Swing {@code ButtonGroup} → Web один {@code name}); значение поля одно, запись
 * {@code FormView.fields} и строка дампа одна, варианты дампа — в порядке появления в раскладке. Остальные виды полей
 * с повторным {@code id} недопустимы.</p>
 *
 * @param id          id поля; совпадает с {@code WindowType.fieldIds()} для восстанавливаемых окон
 * @param kind        вид элемента
 * @param label       подпись слева (без «:», двоеточие добавляет клиент по §6.0 п. 3), текст флажка CHECK или пустая
 *                    строка (см. описание класса)
 * @param prompt      подсказка в пустом поле («например, Зарплата») или пустая строка
 * @param tooltip     всплывающая подсказка или пустая строка
 * @param options     варианты для CHOICE, EDITABLE_CHOICE, RADIO, LIST (начальные; актуальные — в {@link FieldView})
 * @param min         минимум SPINNER (актуальный может прийти в {@link FieldView#min()})
 * @param max         максимум SPINNER (актуальный может прийти в {@link FieldView#max()})
 * @param step        шаг SPINNER
 * @param columns     ширина в символах (0 — по раскладке)
 * @param textRows    строк MULTILINE или видимых строк LIST (0 — по умолчанию)
 * @param wide        занимает обе колонки сетки формы
 * @param suffix      подпись справа от поля (валюта у суммы) или пустая строка
 * @param orientation направление группы RADIO
 * @param focusFirst  получает фокус при открытии формы; текст однострочного поля при этом выделяется целиком, чтобы
 *                    ввод заменял его (§5.6.1 быстрая правка, §6.7 сверка, §6.9 переименование)
 */
public record FieldSpec(String id, FieldKind kind, String label, String prompt, String tooltip, List<Option> options,
                        long min, long max, long step, int columns, int textRows, boolean wide, String suffix,
                        Orientation orientation, boolean focusFirst) {

    /** Проверяет поля и заменяет {@code null}. */
    public FieldSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        label = Objects.requireNonNullElse(label, "");
        prompt = Objects.requireNonNullElse(prompt, "");
        tooltip = Objects.requireNonNullElse(tooltip, "");
        options = options == null ? List.of() : List.copyOf(options);
        suffix = Objects.requireNonNullElse(suffix, "");
        orientation = orientation == null ? Orientation.HORIZONTAL : orientation;
    }
}
