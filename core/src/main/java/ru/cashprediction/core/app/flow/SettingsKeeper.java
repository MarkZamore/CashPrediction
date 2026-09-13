package ru.cashprediction.core.app.flow;

import java.util.Objects;

/**
 * Запись настроек {@code CashMemory/settings.md} (спецификация v2, §3.3, §6.31): через 700 мс после последнего
 * изменения ({@code DesignTokens.SETTINGS_DELAY_MS}); ошибка — постоянный статус {@code status.msg.settingsFailed},
 * который снимается после успешной записи.
 *
 * <p>Не потокобезопасен: только поток контроллера.</p>
 */
public final class SettingsKeeper {

    private final FlowContext context;

    /**
     * Создаёт службу.
     *
     * @param context контекст контроллера
     */
    public SettingsKeeper(FlowContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /** @return контекст контроллера */
    public FlowContext context() {
        return context;
    }

    /** Настройки изменились: перезапустить таймер 700 мс. */
    public void changed() {
        throw new UnsupportedOperationException("S2: core-app-file — SettingsKeeper.changed");
    }

    /** Записать немедленно (выход) и остановить таймер. */
    public void flushNow() {
        throw new UnsupportedOperationException("S2: core-app-file — SettingsKeeper.flushNow");
    }
}
