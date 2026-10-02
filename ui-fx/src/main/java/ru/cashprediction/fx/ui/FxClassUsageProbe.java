package ru.cashprediction.fx.ui;

import java.util.LinkedHashMap;
import java.util.Map;

/** Перепись действительно созданных виджетов нового клиента. */
public final class FxClassUsageProbe {
    private final Map<String, Integer> counts = new LinkedHashMap<>();

    /** Учитывает экземпляр и возвращает его без изменения. */
    public <T> T created(T widget) {
        for (Class<?> type = widget.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getPackageName().startsWith("javafx.")) counts.merge(type.getSimpleName(), 1, Integer::sum);
        }
        return widget;
    }

    /** Возвращает независимый снимок переписи. */
    public Map<String, Integer> snapshot() { return Map.copyOf(counts); }
}
