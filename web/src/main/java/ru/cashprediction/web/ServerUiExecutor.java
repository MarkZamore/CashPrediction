package ru.cashprediction.web;

import java.util.Objects;
import ru.cashprediction.core.session.UiExecutor;

/**
 * «UI-поток» web-сервера для {@code SessionRecorder}: у сервера нет настоящего UI-потока, его роль играет
 * монитор {@link ServerState}.
 *
 * <p>{@link #execute(Runnable)} выполняет задачу под монитором — так снятие снимка сериализовано со всеми
 * запросами API, и снимок никогда не видит план «наполовину изменённым». {@link #isUiThread()} возвращает
 * {@code true}, если текущий поток уже держит монитор: тогда {@code saveNow()}, вызванный из обработчика запроса,
 * снимает свежий снимок синхронно, а вызванный из shutdown hook — пишет последний снятый.</p>
 *
 * <p>Взаимоблокировки нет: рекордер ждёт монитор только в своём фоновом потоке и при этом не держит блокировку
 * записи, а поток запроса, держащий монитор, ждёт только блокировку записи.</p>
 *
 * <p>Класс потокобезопасен.</p>
 */
public final class ServerUiExecutor implements UiExecutor {

    private final Object lock;

    /**
     * Создаёт исполнитель над монитором состояния.
     *
     * @param lock монитор {@link ServerState}
     */
    public ServerUiExecutor(Object lock) {
        this.lock = Objects.requireNonNull(lock, "lock");
    }

    @Override
    public void execute(Runnable task) {
        synchronized (lock) {
            task.run();
        }
    }

    @Override
    public boolean isUiThread() {
        return Thread.holdsLock(lock);
    }
}
