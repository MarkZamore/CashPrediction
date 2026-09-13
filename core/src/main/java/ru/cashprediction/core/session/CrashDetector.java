package ru.cashprediction.core.session;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import ru.cashprediction.core.text.Texts;

/**
 * Определяет при запуске, как завершился предыдущий сеанс клиента (раздел 5.6 плана).
 *
 * <p>Правило:</p>
 * <ul>
 *   <li>маркера нет или он {@code closed} → {@link Status#CLEAN_START};</li>
 *   <li>маркер {@code running}, процесс с этим pid жив, это та же программа, не мы сами и процесс запущен
 *       не позже начала сеанса из маркера → {@link Status#ALREADY_RUNNING} (второй экземпляр: «Открыть без
 *       восстановления», рекордер отключается); процесс, запущенный позже, получил pid упавшего сеанса повторно;</li>
 *   <li>маркер {@code running} в остальных случаях → {@link Status#CRASHED} (показывается диалог
 *       восстановления до главного окна).</li>
 * </ul>
 *
 * <p>Проверяются только маркеры своего клиента: JavaFX- и Swing-клиенты, запущенные одновременно,
 * не должны считать друг друга «вторым экземпляром». Если хранилища расходятся (реестр говорит
 * {@code running}, XML — {@code closed} для того же pid и момента начала), побеждает {@code closed}:
 * значит, выход был корректным, но одно хранилище не успело обновиться.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class CrashDetector {

    /** Итог проверки. */
    public enum Status {
        /** Предыдущий сеанс завершён корректно или его не было. */
        CLEAN_START,
        /** Предыдущий сеанс оборвался: предложить восстановление. */
        CRASHED,
        /** Программа уже запущена этим же клиентом. */
        ALREADY_RUNNING
    }

    /**
     * Сведения об одном хранилище для диалога восстановления.
     *
     * @param available  доступно ли хранилище
     * @param snapshotAt момент снимка, если снимок есть и читается
     * @param problem    почему восстановиться из хранилища нельзя (на русском); пустая строка — можно
     */
    public record StoreInfo(boolean available, Optional<Instant> snapshotAt, String problem) {

        /** Проверяет поля. */
        public StoreInfo {
            snapshotAt = Objects.requireNonNullElse(snapshotAt, Optional.empty());
            problem = Objects.requireNonNullElse(problem, "");
        }

        /**
         * Можно ли восстановиться из хранилища.
         *
         * @return {@code true}, если хранилище доступно и снимок читается
         */
        public boolean restorable() {
            return available && snapshotAt.isPresent() && problem.isEmpty();
        }
    }

    /**
     * Результат обнаружения.
     *
     * @param status итог
     * @param stores сведения по хранилищам: ключ — {@link SessionStore#id()}, порядок как в списке хранилищ
     * @param marker маркер, на котором основан вывод; {@code null}, если маркеров нет
     */
    public record Detection(Status status, Map<String, StoreInfo> stores, SessionMarker marker) {

        /** Копирует карту хранилищ. */
        public Detection {
            Objects.requireNonNull(status, "status");
            stores = stores == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(stores));
        }

        /**
         * Маркер как {@link Optional}.
         *
         * @return маркер или пусто
         */
        public Optional<SessionMarker> findMarker() {
            return Optional.ofNullable(marker);
        }

        /**
         * Есть ли хотя бы одно хранилище, из которого можно восстановиться.
         *
         * @return {@code true}, если есть
         */
        public boolean anyRestorable() {
            return stores.values().stream().anyMatch(StoreInfo::restorable);
        }
    }

    /**
     * Сведения о процессах; отдельный интерфейс ради детерминированных тестов.
     */
    public interface ProcessProbe {

        /**
         * Идентификатор текущего процесса.
         *
         * @return pid
         */
        long currentPid();

        /**
         * Жив ли процесс.
         *
         * @param pid идентификатор процесса
         * @return {@code true}, если процесс существует и работает
         */
        boolean isAlive(long pid);

        /**
         * Запущен ли процесс из того же исполняемого файла, что и текущий. Нужен, потому что после
         * перезагрузки pid упавшего сеанса может достаться совсем другой программе.
         *
         * @param pid идентификатор процесса
         * @return {@code true}, только если команды обоих процессов известны и совпадают
         */
        boolean sameExecutable(long pid);

        /**
         * Момент запуска процесса. Нужен против повторного использования pid: Windows быстро раздаёт pid заново,
         * а тот же {@code java.exe} запускает и IDE, и Maven, и другие программы из того же JDK. Процесс,
         * запущенный позже начала сеанса из маркера, заведомо не тот экземпляр.
         *
         * @param pid идентификатор процесса
         * @return момент запуска или пусто, если его нельзя узнать
         */
        Optional<Instant> startInstant(long pid);

        /**
         * Боевая реализация на {@link ProcessHandle}.
         *
         * @return проба процессов операционной системы
         */
        static ProcessProbe system() {
            return new ProcessProbe() {
                @Override
                public long currentPid() {
                    return ProcessHandle.current().pid();
                }

                @Override
                public boolean isAlive(long pid) {
                    return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
                }

                @Override
                public boolean sameExecutable(long pid) {
                    Optional<String> own = ProcessHandle.current().info().command();
                    Optional<String> other = ProcessHandle.of(pid).flatMap(h -> h.info().command());
                    // Пути Windows нечувствительны к регистру. Если команду чужого процесса прочитать
                    // нельзя (например, он запущен от администратора), это заведомо не наш экземпляр.
                    return own.isPresent() && other.isPresent() && own.get().equalsIgnoreCase(other.get());
                }

                @Override
                public Optional<Instant> startInstant(long pid) {
                    return ProcessHandle.of(pid).flatMap(h -> h.info().startInstant());
                }
            };
        }
    }

    /**
     * Допуск при сравнении момента запуска живого процесса с началом сеанса из маркера. Момент маркера снимается
     * часами рекордера уже после запуска процесса, поэтому у настоящего экземпляра запуск всегда раньше; допуск
     * лишь сглаживает разную точность системных часов.
     */
    public static final Duration START_TOLERANCE = Duration.ofSeconds(5);

    private CrashDetector() {
    }

    /**
     * Проверяет хранилища с системной пробой процессов.
     *
     * @param stores хранилища клиента
     * @param client клиент
     * @return результат
     */
    public static Detection detect(List<SessionStore> stores, String client) {
        return detect(stores, client, ProcessProbe.system());
    }

    /**
     * Проверяет хранилища клиента и определяет, как завершился предыдущий сеанс.
     *
     * @param stores хранилища клиента
     * @param client клиент
     * @param probe  сведения о процессах
     * @return результат; сведения о снимках (наличие, повреждение) заполняются полностью только для {@link Status#CRASHED}
     */
    public static Detection detect(List<SessionStore> stores, String client, ProcessProbe probe) {
        Objects.requireNonNull(probe, "probe");
        List<SessionMarker> markers = new ArrayList<>();
        for (SessionStore store : stores) {
            if (!store.isAvailable()) {
                continue;
            }
            try {
                store.readMarker().filter(m -> m.client().equals(client)).ifPresent(markers::add);
            } catch (RuntimeException e) {
                // Хранилище, которое не читается, просто не участвует в решении.
            }
        }
        SessionMarker chosen = chooseMarker(markers);
        Status status;
        if (chosen == null || !chosen.isRunning()) {
            status = Status.CLEAN_START;
        } else if (isSameInstanceAlive(chosen, probe)) {
            status = Status.ALREADY_RUNNING;
        } else {
            status = Status.CRASHED;
        }
        Map<String, StoreInfo> infos = new LinkedHashMap<>();
        for (SessionStore store : stores) {
            infos.put(store.id(), describe(store, status == Status.CRASHED));
        }
        return new Detection(status, infos, chosen);
    }

    /**
     * Жив ли экземпляр, записавший маркер: pid чужой, процесс жив, исполняемый файл тот же и процесс запущен
     * не позже начала сеанса (с допуском {@link #START_TOLERANCE}). Неизвестный момент запуска означает
     * «не тот экземпляр»: ложный CRASHED лишь предложит восстановление, а ложный ALREADY_RUNNING спрятал бы
     * снимок сбоя и отключил запись нового сеанса.
     */
    private static boolean isSameInstanceAlive(SessionMarker marker, ProcessProbe probe) {
        long pid = marker.pid();
        if (pid == probe.currentPid() || !probe.isAlive(pid) || !probe.sameExecutable(pid)) {
            return false;
        }
        Optional<Instant> started = probe.startInstant(pid);
        return started != null && started.isPresent()
                && !started.get().isAfter(marker.startedAt().plus(START_TOLERANCE));
    }

    private static SessionMarker chooseMarker(List<SessionMarker> markers) {
        Comparator<SessionMarker> byStart = Comparator.comparing(SessionMarker::startedAt);
        Optional<SessionMarker> running = markers.stream()
                .filter(SessionMarker::isRunning)
                // Тот же сеанс, отмеченный закрытым в другом хранилище, был закрыт корректно.
                .filter(m -> markers.stream().noneMatch(other -> !other.isRunning()
                        && other.pid() == m.pid() && other.startedAt().equals(m.startedAt())))
                .max(byStart);
        if (running.isPresent()) {
            return running.get();
        }
        // Остались только закрытые маркеры (или running, подтверждённые закрытыми): выбираем закрытый,
        // иначе при равном времени начала max вернул бы running из другого хранилища.
        return markers.stream().filter(m -> !m.isRunning()).max(byStart).orElse(null);
    }

    private static StoreInfo describe(SessionStore store, boolean inspectSnapshot) {
        if (!store.isAvailable()) {
            return new StoreInfo(false, Optional.empty(),
                    Texts.get("session.store.unavailable", store.title(), store.unavailableReason()));
        }
        if (!inspectSnapshot) {
            return new StoreInfo(true, store.lastSavedAt(), "");
        }
        try {
            Optional<SessionSnapshot> snapshot = store.load();
            if (snapshot.isEmpty()) {
                return new StoreInfo(true, Optional.empty(), Texts.get("session.crash.snapshotNotFound"));
            }
            return new StoreInfo(true, Optional.of(snapshot.get().savedAt()), "");
        } catch (SessionStoreException e) {
            return new StoreInfo(true, Optional.empty(), e.getMessage());
        } catch (RuntimeException e) {
            return new StoreInfo(true, Optional.empty(), Texts.get("session.crash.snapshotUnreadable", e.getMessage()));
        }
    }
}
