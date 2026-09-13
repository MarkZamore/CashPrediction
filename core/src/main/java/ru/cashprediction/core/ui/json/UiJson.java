package ru.cashprediction.core.ui.json;

import java.util.Map;

/**
 * JSON-представление моделей и протокола web-клиента (архитектура §5) поверх мини-JSON ядра ({@code core.json}).
 *
 * <p><b>Правила записи (этап S2, core-protocol-dump):</b> record → объект с полями в порядке компонентов; вид
 * sealed-интерфейса → поле {@code kind} с простым именем записи (например {@code MenuNode.Check} →
 * {@code "kind":"Check"}); enum → имя константы; {@code LocalDate} → ISO {@code 2026-10-05}; {@code YearMonth} →
 * {@code 2026-10}; {@code Path} → строка; {@code null} → {@code null}; {@code TableModel} → колонки, {@code rowCount},
 * {@code revision}, выделение и заглушка без строк; {@code ChartModel} → только {@code revision}. Намерения и запросы
 * читаются обратно в {@link WebIntent} и {@link WebQuery}; неизвестный {@code type} — {@code IllegalArgumentException}.
 * Примеры JSON — {@code docs/ui-protocol.md}; фикстуры — {@code core/src/test/resources/ui-json}.</p>
 *
 * <p><b>Два исключения из правил выше — одна форма во всём протоколе:</b></p>
 * <ul>
 *   <li>{@code CommandId} пишется своим id спецификации ({@code CommandId.id()}: {@code "file.save"},
 *       {@code "row.edit"}), а не именем константы, и читается через {@code CommandId.byId}; неизвестный id —
 *       {@code IllegalArgumentException}. Так команда узла меню, тулбара и контекстного меню, которую вкладка
 *       возвращает намерением {@code command}, совпадает байт в байт.</li>
 *   <li>{@code ContextTarget} пишется полем {@code kind} со значением {@code ContextTarget.kind()} ({@code "row"},
 *       {@code "total"}, {@code "pastHeader"}, {@code "card"}, {@code "chart"}, {@code "preview"}) — тем же ключом,
 *       что у цели в дампе и сценариях ({@code row:<rowId>}), — и полями записи.</li>
 * </ul>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class UiJson {

    private UiJson() {
    }

    /**
     * Дерево JSON ({@code Map}/{@code List}/строки/числа/булевы/{@code null}) для записи, модели или эффекта.
     *
     * @param value значение
     * @return дерево для {@code JsonWriter}
     */
    public static Object toTree(Object value) {
        throw new UnsupportedOperationException("S2: core-protocol-dump — UiJson.toTree");
    }

    /**
     * Текст JSON значения.
     *
     * @param value значение
     * @return компактный JSON
     */
    public static String write(Object value) {
        throw new UnsupportedOperationException("S2: core-protocol-dump — UiJson.write");
    }

    /**
     * Эффект с номером для журнала эффектов: {@code {seq, type, …поля}}.
     *
     * @param seq    номер
     * @param effect эффект
     * @return дерево JSON
     */
    public static Map<String, Object> effect(long seq, WebEffect effect) {
        throw new UnsupportedOperationException("S2: core-protocol-dump — UiJson.effect");
    }

    /**
     * Читает намерение {@code intent:{type, …}}.
     *
     * @param json объект намерения
     * @return намерение
     * @throws IllegalArgumentException если тип неизвестен или поля некорректны
     */
    public static WebIntent readIntent(Map<String, Object> json) {
        throw new UnsupportedOperationException("S2: core-protocol-dump — UiJson.readIntent");
    }

    /**
     * Читает запрос {@code {type, …}}.
     *
     * @param json объект запроса
     * @return запрос
     * @throws IllegalArgumentException если тип неизвестен или поля некорректны
     */
    public static WebQuery readQuery(Map<String, Object> json) {
        throw new UnsupportedOperationException("S2: core-protocol-dump — UiJson.readQuery");
    }
}
