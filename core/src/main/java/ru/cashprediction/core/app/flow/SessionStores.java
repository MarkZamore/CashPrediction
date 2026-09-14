package ru.cashprediction.core.app.flow;

import java.util.List;
import ru.cashprediction.core.app.AppEnvironment;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.session.SessionStore;

/**
 * Хранилища снимков клиента (архитектура §3.8; решения L2, L12).
 *
 * <ul>
 *   <li>FX: реестр {@code fx} + {@code CashMemory/session-fx.xml};</li>
 *   <li>Swing: реестр {@code swing} + {@code CashMemory/session-swing.xml};</li>
 *   <li>Web: {@code CashMemory/web-session.md}.</li>
 * </ul>
 * <p>Реестр — {@code AppEnvironment.registryStore(client)}: узел этой установки
 * {@code ru/cashprediction/session/<клиент>-<8 hex>}, явный префикс {@code --registry-node} или память процесса
 * {@code --registry memory}.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class SessionStores {

    private SessionStores() {
    }

    /**
     * Хранилища клиента в порядке {@code CrashDetector} (реестр первым).
     *
     * @param profile     профиль клиента
     * @param environment окружение
     * @return хранилища
     */
    public static List<SessionStore> forClient(ClientProfile profile, AppEnvironment environment) {
        throw new UnsupportedOperationException("S2: core-app-session - SessionStores.forClient");
    }
}
