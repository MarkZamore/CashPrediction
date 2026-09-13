package ru.cashprediction.core.session;

import java.time.Instant;
import java.util.Objects;
import ru.cashprediction.core.text.Texts;

/**
 * Маркер сеанса: «программа работает» или «программа закрыта корректно».
 *
 * <p>При старте рекордер пишет в каждое хранилище маркер {@link #RUNNING} с pid процесса, а при
 * явном выходе пользователя (меню «Выход», закрытие главного окна) — {@link #CLOSED}. Если при
 * следующем запуске в хранилище остался {@code running}, а процесса с этим pid уже нет, значит
 * прошлый сеанс завершился аварийно и можно предложить восстановление.</p>
 *
 * <p>Сообщения проверок берутся из каталога текстов: маркер читается из файлов и реестра, и повреждённое значение
 * попадает в сообщение пользователю.</p>
 *
 * <p>Запись неизменяема и потокобезопасна.</p>
 *
 * @param state     {@link #RUNNING} или {@link #CLOSED}
 * @param pid       идентификатор процесса, записавшего маркер
 * @param startedAt момент начала сеанса
 * @param client    клиент: {@code fx}, {@code swing} или {@code web}
 */
public record SessionMarker(String state, long pid, Instant startedAt, String client) {

    /** Сеанс идёт (или оборвался, не успев отметить выход). */
    public static final String RUNNING = "running";

    /** Сеанс завершён корректно. */
    public static final String CLOSED = "closed";

    /** Проверяет состояние, pid и клиента. */
    public SessionMarker {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(startedAt, "startedAt");
        SnapshotSchema.requireClient(client);
        if (!RUNNING.equals(state) && !CLOSED.equals(state)) {
            throw new IllegalArgumentException(Texts.get("session.marker.error.state", state));
        }
        if (pid < 0) {
            throw new IllegalArgumentException(Texts.get("session.marker.error.pid", pid));
        }
    }

    /**
     * Создаёт маркер работающего сеанса.
     *
     * @param pid       идентификатор текущего процесса
     * @param startedAt момент начала сеанса
     * @param client    клиент
     * @return маркер {@code running}
     */
    public static SessionMarker running(long pid, Instant startedAt, String client) {
        return new SessionMarker(RUNNING, pid, startedAt, client);
    }

    /**
     * Проверяет, что маркер означает незавершённый сеанс.
     *
     * @return {@code true} для {@link #RUNNING}
     */
    public boolean isRunning() {
        return RUNNING.equals(state);
    }

    /**
     * Возвращает тот же маркер в состоянии «закрыт корректно».
     *
     * @return маркер {@code closed} с теми же pid, временем и клиентом
     */
    public SessionMarker closed() {
        return new SessionMarker(CLOSED, pid, startedAt, client);
    }
}
