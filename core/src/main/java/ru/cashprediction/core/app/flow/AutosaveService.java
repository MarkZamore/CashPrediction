package ru.cashprediction.core.app.flow;

import java.util.Objects;

/**
 * Автосохранение (спецификация v2, §6.31; решение L4 — поведение одинаково во всех клиентах).
 *
 * <p>Через 1 с после изменения ({@code DesignTokens.AUTOSAVE_DELAY_MS}), если включено и план изменён: файл есть —
 * запись или §6.14 (окно не открывается повторно, пока предыдущее открыто); файла нет — запись, только если
 * {@code <имя>.md} не существует, иначе статус {@code status.msg.autosaveSkipped}. Молча не пропускается никогда:
 * каждый пропуск или ошибка видны в строке состояния ({@code status.autosave.problem} с подсказкой).</p>
 *
 * <p>Не потокобезопасен: только поток контроллера; таймер — {@code port.scheduler()}.</p>
 */
public final class AutosaveService {

    private final FlowContext context;

    /**
     * Создаёт службу.
     *
     * @param context контекст контроллера
     */
    public AutosaveService(FlowContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /** @return контекст контроллера */
    public FlowContext context() {
        return context;
    }

    /** План изменился: перезапустить таймер 1 с. */
    public void documentChanged() {
        throw new UnsupportedOperationException("S2: core-app-file - AutosaveService.documentChanged");
    }

    /**
     * Включает или выключает автосохранение.
     *
     * @param enabled включено ли
     */
    public void setEnabled(boolean enabled) {
        throw new UnsupportedOperationException("S2: core-app-file - AutosaveService.setEnabled");
    }

    /** Останавливает таймер (выход). */
    public void stop() {
        throw new UnsupportedOperationException("S2: core-app-file - AutosaveService.stop");
    }
}
