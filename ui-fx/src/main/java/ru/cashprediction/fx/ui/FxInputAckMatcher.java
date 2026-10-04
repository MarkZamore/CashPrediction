package ru.cashprediction.fx.ui;

/**
 * Сопоставляет наблюдения ввода с поколением установленного listener без toolkit.
 * Время относится к callback, а не к рождению native event: JavaFX его не предоставляет.
 * Сопоставление не удостоверяет краску или свежесть события до доставки в JavaFX.
 */
final class FxInputAckMatcher {
    private long sequence, armedNanos, pressedNanos;
    private String modality = "none", reason = "unarmed";
    private Point requested, observed, physical, delivered;
    private boolean pointerAccepted, keyAccepted, pressed;
    private long pointerSequence;
    private long staleCallbacks, lastRejectedSequence, callbackNanos;

    /** Координаты без зависимости от JavaFX и без замены недостоверного значения нулём. */
    record Point(double x, double y) { }

    /** Открывает новую pointer-команду, сбрасывая подтверждение предыдущего перемещения. */
    long armPointer(Point target, long now) {
        arm("pointer", now); requested = target;
        observed = null; physical = null; delivered = null; pointerAccepted = false; pointerSequence = sequence;
        return sequence;
    }

    /** Открывает новый Tab; pointer-факт сохраняется отдельно от клавиатурного поколения. */
    long armKey(long now) { arm("keyboard", now); return sequence; }

    /** Сбрасывает состояние клавиатуры и диагностическую причину на каждой команде. */
    private void arm(String kind, long now) {
        sequence++; modality = kind; armedNanos = now;
        callbackNanos = 0; pressedNanos = 0;
        pressed = false; keyAccepted = false; reason = "awaiting-event";
    }

    /** Проверяет координаты, pick и поколение listener; отказ не оставляет старое подтверждение. */
    boolean pointer(long listenerSequence, long callbackNanos, Point event, Point actual,
                    boolean synthesized, boolean hasPick) {
        if (listenerSequence != sequence) { staleCallbacks++; lastRejectedSequence = listenerSequence; return false; }
        this.callbackNanos = callbackNanos;
        observed = event; physical = actual; pointerAccepted = false; delivered = null;
        if (!"pointer".equals(modality)) reason = "wrong-modality";
        else if (callbackNanos - armedNanos <= 0) reason = "callback-before-arm";
        else if (synthesized) reason = "synthesized-event";
        else if (!finite(requested) || !finite(event) || !finite(actual)) reason = "non-finite-coordinates";
        else if (!hasPick) reason = "missing-pick";
        else if (!near(event, actual)) reason = "event-physical-mismatch";
        else if (!near(requested, actual)) reason = "requested-physical-mismatch";
        else { delivered = event; pointerAccepted = true; reason = "pointer-observed"; }
        return pointerAccepted;
    }

    /** Требует пару press/release одного listener-поколения; одиночный release ничего не подтверждает. */
    void key(long listenerSequence, long callbackNanos, boolean tab, boolean press) {
        if (listenerSequence != sequence) { staleCallbacks++; lastRejectedSequence = listenerSequence; return; }
        this.callbackNanos = callbackNanos;
        if (!"keyboard".equals(modality)) { reason = "wrong-modality"; return; }
        if (callbackNanos - armedNanos <= 0) { keyAccepted = false; pressed = false; reason = "callback-before-arm"; return; }
        if (!tab) { reason = "wrong-key"; return; }
        if (press) {
            pressed = true; pressedNanos = callbackNanos; keyAccepted = false; reason = "tab-pressed";
        } else if (!pressed || callbackNanos - pressedNanos <= 0) {
            keyAccepted = false; reason = "release-without-current-press";
        } else { pressed = false; keyAccepted = true; reason = "tab-release-observed"; }
    }

    /** Проверяет сохранённый pointer-факт по новому физическому чтению без его переприсвоения Tab. */
    boolean pointerAcknowledged(Point actual) {
        return pointerAccepted && finite(actual) && near(requested, actual) && near(delivered, actual);
    }

    /** Подтверждает только текущую клавиатурную команду, а не предыдущий Tab. */
    boolean keyAcknowledged(long expected) {
        return "keyboard".equals(modality) && expected == sequence && keyAccepted;
    }

    /** Снимок диагностики не служит доказательством свежести native event или успешной краски. */
    String status(Point actual) {
        return "sequence=" + sequence + "; pointerSequence=" + pointerSequence + "; modality=" + modality
                + "; armedCallbackNanos=" + armedNanos + "; observedCallbackNanos=" + callbackNanos
                + "; pressedCallbackNanos=" + pressedNanos + "; requested=" + requested + "; physical=" + actual
                + "; event=" + observed + "; eventPhysical=" + physical + "; delivered=" + delivered
                + "; reason=" + reason + "; staleCallbacks=" + staleCallbacks
                + "; lastRejectedSequence=" + lastRejectedSequence + "; nativeEventTime=unavailable";
    }

    /** Отвергает NaN и бесконечность до сравнения координат. */
    private static boolean finite(Point value) {
        return value != null && Double.isFinite(value.x()) && Double.isFinite(value.y());
    }

    /** Сохраняет исходный допуск одного экранного пикселя для каждой координаты. */
    private static boolean near(Point a, Point b) {
        return finite(a) && finite(b) && Math.abs(a.x() - b.x()) <= 1 && Math.abs(a.y() - b.y()) <= 1;
    }
}
