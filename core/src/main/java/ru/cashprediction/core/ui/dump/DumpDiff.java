package ru.cashprediction.core.ui.dump;

import java.util.List;

/**
 * Сравнение двух деревьев JSON дампов с адресами расхождений в виде JSON Pointer (архитектура §6.1).
 *
 * <p>Списки сравниваются по индексу, объекты — по ключам (лишний и отсутствующий ключ — расхождения); числа границ
 * сравниваются с допуском, если он задан; результат упорядочен по указателю.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class DumpDiff {

    /**
     * Одно расхождение.
     *
     * @param pointer  JSON Pointer, например {@code /menuBar/0/children/3/text}
     * @param expected ожидаемое значение (дерево JSON) или {@code null}, если ключа нет
     * @param actual   фактическое значение или {@code null}, если ключа нет
     */
    public record Difference(String pointer, Object expected, Object actual) {
    }

    private DumpDiff() {
    }

    /**
     * Расхождения двух деревьев.
     *
     * @param expected    эталон
     * @param actual      фактический дамп
     * @param boxTolerance допуск для полей {@code x}, {@code y}, {@code width}, {@code height} в пикселях (0 — точно)
     * @return расхождения по возрастанию указателя
     */
    public static List<Difference> diff(Object expected, Object actual, double boxTolerance) {
        throw new UnsupportedOperationException("S2: core-protocol-dump — DumpDiff.diff");
    }
}
