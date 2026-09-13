package ru.cashprediction.core.app;

import ru.cashprediction.core.session.SnapshotSchema;

/**
 * Вид клиента, который отрисовывает модели ядра (архитектура §3.6, §4).
 *
 * <p>От вида зависят только различия из закрытого списка спецификации v2, §10: показываемые ускорители (колонка
 * Desktop или Web, §3, §7), хранилища снимков (§6.28), способ выбора файлов (§6.21) и тексты web-экранов.
 * Всё остальное одинаково.</p>
 *
 * <p>Перечисление неизменяемо и потокобезопасно.</p>
 */
public enum ClientKind {

    /** JavaFX: снимки в реестре {@code fx} и {@code session-fx.xml}. */
    FX(SnapshotSchema.CLIENT_FX, true),
    /** Swing: снимки в реестре {@code swing} и {@code session-swing.xml}. */
    SWING(SnapshotSchema.CLIENT_SWING, true),
    /** Web (тонкий клиент в браузере): снимок на сервере {@code web-session.md}. */
    WEB(SnapshotSchema.CLIENT_WEB, false);

    private final String snapshotClient;
    private final boolean desktop;

    ClientKind(String snapshotClient, boolean desktop) {
        this.snapshotClient = snapshotClient;
        this.desktop = desktop;
    }

    /** @return идентификатор клиента в снимке сеанса: {@code fx}, {@code swing} или {@code web} */
    public String snapshotClient() {
        return snapshotClient;
    }

    /** @return {@code true} для JavaFX и Swing: показываются ускорители колонки Desktop */
    public boolean isDesktop() {
        return desktop;
    }
}
