package ru.cashprediction.core.session;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Состояние одного открытого окна (диалога, всплывающего окна) в снимке.
 *
 * <p>{@code context} описывает, <em>какое</em> это окно (например, {@code mode=edit, ruleId=r3}),
 * а {@code fields} — что пользователь успел ввести (ключ — идентификатор поля из
 * {@link WindowType#fieldIds()}, значение — строка в канонической форме: деньги {@code 95000,00},
 * даты ISO, флажки {@code true/false}, выбор — ключ перечисления).</p>
 *
 * <p>Тип может быть {@code null}: так кодеки представляют окно неизвестного типа (например, снимок
 * записан более новой версией программы). Такое окно не мешает разобрать остальные, а
 * {@link RestoreCoordinator} пропускает его с предупреждением.</p>
 *
 * <p>Запись неизменяема и потокобезопасна: обе карты копируются в неизменяемые
 * {@link LinkedHashMap}, порядок ключей сохраняется (он совпадает с порядком полей на форме
 * и делает XML/Markdown-снимок читаемым).</p>
 *
 * @param id      идентификатор окна в сеансе: {@code w1}, {@code w2}, ...
 * @param type    тип окна из общего словаря; {@code null} — неизвестный тип
 * @param modal   модальное ли окно
 * @param ownerId владелец: {@code main} или идентификатор другого окна
 * @param bounds  положение и размер; {@code null}, если неизвестно или не имеет смысла
 * @param context параметры, определяющие окно
 * @param fields  введённые пользователем значения
 */
public record WindowState(String id, WindowType type, boolean modal, String ownerId, WindowBounds bounds,
                          Map<String, String> context, Map<String, String> fields) {

    /** Идентификатор владельца «главное окно». */
    public static final String MAIN_OWNER = "main";

    /** Проверяет идентификатор и копирует карты. */
    public WindowState {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Идентификатор окна не может быть пустым");
        }
        ownerId = ownerId == null || ownerId.isBlank() ? MAIN_OWNER : ownerId;
        context = copy(context, "context");
        fields = copy(fields, "fields");
    }

    /**
     * Возвращает копию с другими идентификатором и владельцем (при восстановлении окна получают
     * новые идентификаторы текущего сеанса).
     *
     * @param newId      новый идентификатор
     * @param newOwnerId новый владелец
     * @return новое состояние
     */
    public WindowState withIds(String newId, String newOwnerId) {
        return new WindowState(newId, type, modal, newOwnerId, bounds, context, fields);
    }

    /**
     * Возвращает копию с другим контекстом.
     *
     * @param newContext новый контекст
     * @return новое состояние
     */
    public WindowState withContext(Map<String, String> newContext) {
        return new WindowState(id, type, modal, ownerId, bounds, newContext, fields);
    }

    /**
     * Возвращает копию с другими значениями полей.
     *
     * @param newFields новые значения полей
     * @return новое состояние
     */
    public WindowState withFields(Map<String, String> newFields) {
        return new WindowState(id, type, modal, ownerId, bounds, context, newFields);
    }

    /**
     * Значение контекста по ключу.
     *
     * @param key ключ контекста
     * @return значение или пустая строка
     */
    public String contextValue(String key) {
        return context.getOrDefault(key, "");
    }

    /**
     * Значение поля по идентификатору.
     *
     * @param fieldId идентификатор поля
     * @return значение или пустая строка
     */
    public String field(String fieldId) {
        return fields.getOrDefault(fieldId, "");
    }

    private static Map<String, String> copy(Map<String, String> source, String what) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(Objects.requireNonNull(key, what + ": ключ"),
                Objects.requireNonNullElse(value, "")));
        return Collections.unmodifiableMap(result);
    }
}
