package ru.cashprediction.core.document;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Событие открытого документа: набор видов изменений, случившихся за одну операцию.
 *
 * <p>Одна операция может менять сразу несколько аспектов (открытие плана меняет план, файл и признак
 * несохранённых изменений), поэтому событие несёт множество, а не один вид: слушатель получает одно
 * уведомление и перерисовывает всё нужное за один проход.</p>
 *
 * <p>Record неизменяем и потокобезопасен.</p>
 *
 * @param kinds виды изменений; непустое неизменяемое множество
 */
public record DocumentEvent(Set<EventKind> kinds) {

    /** Проверяет, что множество непустое, и делает неизменяемую копию. */
    public DocumentEvent {
        Objects.requireNonNull(kinds, "kinds");
        // EnumSet.copyOf не принимает пустую обычную коллекцию, поэтому копируем через noneOf + addAll.
        EnumSet<EventKind> copy = EnumSet.noneOf(EventKind.class);
        for (EventKind kind : kinds) {
            copy.add(Objects.requireNonNull(kind, "kind"));
        }
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("Событие документа должно содержать хотя бы один вид изменения");
        }
        kinds = Collections.unmodifiableSet(copy);
    }

    /**
     * Создаёт событие из перечня видов.
     *
     * @param first первый вид
     * @param rest  остальные виды
     * @return событие
     */
    public static DocumentEvent of(EventKind first, EventKind... rest) {
        EnumSet<EventKind> set = EnumSet.of(first, rest);
        return new DocumentEvent(set);
    }

    /**
     * Проверяет, входит ли вид изменения в событие.
     *
     * @param kind вид изменения
     * @return {@code true}, если входит
     */
    public boolean has(EventKind kind) {
        return kinds.contains(kind);
    }
}
