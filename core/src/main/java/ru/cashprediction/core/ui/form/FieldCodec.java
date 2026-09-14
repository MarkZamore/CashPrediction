package ru.cashprediction.core.ui.form;

import ru.cashprediction.core.session.WindowType;

/**
 * Канонические и показываемые формы значений полей (архитектура §3.5).
 *
 * <table>
 *   <caption>Формы значений</caption>
 *   <tr><th>Вид</th><th>Каноническая форма (FormState, снимок, протокол)</th><th>Показ</th></tr>
 *   <tr><td>MONEY</td><td>{@code 95000,00} ({@code Money.formatPlain})</td><td>«95 000,00»</td></tr>
 *   <tr><td>DATE</td><td>ISO {@code 2026-10-05}</td><td>«05.10.2026»</td></tr>
 *   <tr><td>MONTH_DAY</td><td>{@code 03-15}</td><td>«15.03»</td></tr>
 *   <tr><td>CHECK</td><td>{@code true}/{@code false}</td><td>флажок</td></tr>
 *   <tr><td>SPINNER</td><td>целое без пробелов</td><td>число</td></tr>
 *   <tr><td>CHOICE, RADIO, LIST</td><td>{@code Option.value}</td><td>{@code Option.text}</td></tr>
 *   <tr><td>TEXT, MULTILINE, EDITABLE_CHOICE</td><td>текст как есть</td><td>текст</td></tr>
 * </table>
 *
 * <p>Некорректный ввод не теряется: {@link #canonical} возвращает сырой текст, {@link #display} показывает его
 * дословно. {@link #acceptLegacy} переводит значения из снимков прежних клиентов (фикстуры этапа S1) в
 * канонические формы.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class FieldCodec {

    private FieldCodec() {
    }

    /**
     * Каноническая форма введённого текста. Принимает «80000», «80 000,5», «80000.50», «-1 200»; даты ISO и
     * ДД.ММ.ГГГГ; день года ДД.ММ и ММ-ДД.
     *
     * @param kind вид поля
     * @param raw  текст виджета
     * @return каноническое значение или сырой текст, если он некорректен
     */
    public static String canonical(FieldKind kind, String raw) {
        throw new UnsupportedOperationException("S1: core-forms-framework - FieldCodec.canonical");
    }

    /**
     * Текст для виджета по канонической форме.
     *
     * @param kind      вид поля
     * @param canonical каноническое значение (или сохранённый некорректный текст)
     * @return показываемый текст
     */
    public static String display(FieldKind kind, String canonical) {
        throw new UnsupportedOperationException("S1: core-forms-framework - FieldCodec.display");
    }

    /**
     * Значение поля из снимка прежнего клиента в канонической форме нового интерфейса.
     *
     * @param type    тип окна
     * @param fieldId id поля
     * @param value   значение из снимка
     * @return каноническое значение (некорректное — как есть)
     */
    public static String acceptLegacy(WindowType type, String fieldId, String value) {
        throw new UnsupportedOperationException("S1: core-forms-framework - FieldCodec.acceptLegacy");
    }
}
