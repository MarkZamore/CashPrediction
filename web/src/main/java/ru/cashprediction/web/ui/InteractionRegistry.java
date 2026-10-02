package ru.cashprediction.web.ui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import ru.cashprediction.core.ui.alert.AlertSpec;

/** Проверяет точный id доступной кнопки; первый ответ закрывает ожидание до вызова продолжения. */
public final class InteractionRegistry {
    private final Map<String, Pending> pending = new LinkedHashMap<>();

    /** Ожидающее сообщение и его продолжение. */
    private record Pending(AlertSpec spec, Consumer<String> callback) { }

    /** Регистрирует сообщение. Вызывается только в потоке контроллера. */
    public void open(String id, AlertSpec spec, Consumer<String> callback) {
        if (pending.putIfAbsent(id, new Pending(spec, callback)) != null) throw new IllegalStateException("alert id");
    }
    /** Меняет модель сообщения, сохраняя продолжение. */
    public void update(String id, AlertSpec spec) {
        Pending old = pending.get(id);
        if (old != null) pending.put(id, new Pending(spec, old.callback()));
    }
    /** Принимает первый действительный ответ, неизвестный или закрытый id игнорируется. */
    public boolean answer(String id, String button) {
        Pending value = pending.get(id);
        if (value == null) return false;
        if (value.spec().buttons().stream().noneMatch(item -> item.id().equals(button) && item.enabled()))
            throw new IllegalArgumentException("buttonId");
        pending.remove(id);
        value.callback().accept(button);
        return true;
    }
    /** Убирает закрытое сообщение без ответа. */
    public void close(String id) { pending.remove(id); }
}
