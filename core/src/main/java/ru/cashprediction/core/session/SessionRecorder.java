package ru.cashprediction.core.session;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import ru.cashprediction.core.text.Texts;

/**
 * Записывает снимок сессии во все хранилища клиента по событиям и по таймеру (раздел 5.2 плана).
 *
 * <p><b>Жизненный цикл.</b></p>
 * <ol>
 *   <li>{@link #start()} — маркер {@code running} во все доступные хранилища (снимок в них
 *       сохраняется) и периодическая запись раз в {@link #PERIOD}. До {@code start()} рекордер ничего
 *       не пишет: иначе новый пустой сеанс затёр бы снимок сбоя, из которого пользователь ещё не
 *       решил восстанавливаться.</li>
 *   <li>{@link #touch()} при каждом изменении (ввод в поле, открытие/закрытие/перемещение окна, смена
 *       вида) — отложенная запись: таймер {@link #DEBOUNCE} перезапускается, серия нажатий клавиш
 *       сливается в одну запись. Снимок снимается в UI-потоке, пишется в фоне.</li>
 *   <li>{@link #saveNow()} — синхронная запись из обработчика сбоя или перед закрытием главного окна.</li>
 *   <li>{@link #shutdownClean()} — только при явном выходе пользователя: таймеры выключаются,
 *       снимок пишется, маркер становится {@code closed}.</li>
 * </ol>
 *
 * <p><b>Защита от гонок.</b> Все записи в хранилища идут под одной блокировкой {@code writeLock};
 * каждый снимок получает возрастающий номер, и запись более старого снимка после более нового
 * отбрасывается. {@code saveNow()} ждёт идущую фоновую запись не дольше {@link #SAVE_NOW_WAIT}, чтобы
 * запоздавшая фоновая запись не перезаписала свежий снимок. Флаг {@code closed} — volatile: после
 * {@code shutdownClean()} поздние фоновые задачи молча отбрасываются. Сами хранилища дополнительно
 * синхронизируют свои операции.</p>
 *
 * <p><b>Потоки.</b> {@link #register}, {@link #unregister}, {@link #touch} вызываются из UI-потока
 * (touch допускается из любого). Слушатели статуса вызываются в потоке записи, вне блокировки;
 * им нельзя синхронно ждать UI-поток — только передать данные через {@code runLater}/{@code invokeLater}.
 * Класс потокобезопасен.</p>
 */
public final class SessionRecorder {

    /** Задержка отложенной записи после последнего {@link #touch()}. */
    public static final Duration DEBOUNCE = Duration.ofMillis(400);

    /** Период фоновой записи. */
    public static final Duration PERIOD = Duration.ofSeconds(5);

    /** Сколько {@link #saveNow()} ждёт уже идущую фоновую запись. */
    public static final Duration SAVE_NOW_WAIT = Duration.ofSeconds(2);

    /** Префикс идентификаторов окон. */
    private static final String WINDOW_ID_PREFIX = "w";

    /**
     * Снятый снимок с порядковым номером.
     *
     * @param snapshot снимок
     * @param sequence номер: чем больше, тем новее
     * @param complete все зарегистрированные окна дали состояние
     * @param epoch поколение clear/disable/re-enable
     */
    private record Captured(SessionSnapshot snapshot, long sequence, boolean complete, long epoch) {
    }

    private final String client;
    private final List<SessionStore> stores;
    private final UiExecutor ui;
    private final SnapshotSource source;
    private final Scheduler scheduler;
    private final Clock clock;
    private final long pid;

    /** Зарегистрированные окна в порядке регистрации. */
    private final CopyOnWriteArrayList<StatefulWindow> windows = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<StoreStatus>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger windowCounter = new AtomicInteger();
    private final AtomicLong captureSequence = new AtomicLong();

    /** Сериализует записи в хранилища. */
    private final ReentrantLock writeLock = new ReentrantLock();
    /** Номер последнего записанного снимка. */
    private final AtomicLong lastWrittenSequence = new AtomicLong();
    /** Последний успешно записанный в каждое хранилище снимок: неизменившийся снимок таймер не пишет повторно. */
    private final Map<String, SessionSnapshot> lastWrittenByStore = new ConcurrentHashMap<>();
    /** Момент последнего успешного снимка по хранилищам. */
    private final Map<String, Instant> lastSuccessByStore = new ConcurrentHashMap<>();

    /** Защищает таймеры и переходы состояния. */
    private final Object stateLock = new Object();
    private Scheduler.Task pendingTouch;
    private long touchGeneration;
    private int snapshotWork;
    private long workSequence;
    private long acknowledgedWork;
    private long failedDispatch;
    private volatile long snapshotEpoch;
    private Scheduler.Task periodic;
    private boolean markerWritten;

    private volatile boolean started;
    private volatile boolean closed;
    private volatile boolean enabled = true;
    private volatile SessionMarker marker;
    private volatile Captured lastCaptured;

    /**
     * Создаёт рекордер для текущего процесса.
     *
     * @param client    клиент: {@code fx}, {@code swing} или {@code web}
     * @param stores    хранилища в порядке показа в строке состояния
     * @param ui        доступ к UI-потоку
     * @param source    источник состояния главного окна и плана
     * @param scheduler планировщик; рекордер владеет им и остановит его в {@link #shutdownClean()}
     * @param clock     часы для моментов снимков и маркера
     */
    public SessionRecorder(String client, List<SessionStore> stores, UiExecutor ui, SnapshotSource source,
                           Scheduler scheduler, Clock clock) {
        this(client, stores, ui, source, scheduler, clock, ProcessHandle.current().pid());
    }

    /**
     * Создаёт рекордер с явным pid (для тестов).
     *
     * @param client    клиент
     * @param stores    хранилища
     * @param ui        доступ к UI-потоку
     * @param source    источник состояния главного окна и плана
     * @param scheduler планировщик
     * @param clock     часы
     * @param pid       идентификатор процесса для маркера
     */
    public SessionRecorder(String client, List<SessionStore> stores, UiExecutor ui, SnapshotSource source,
                           Scheduler scheduler, Clock clock, long pid) {
        this.client = SnapshotSchema.requireClient(client);
        this.stores = List.copyOf(stores);
        this.ui = Objects.requireNonNull(ui, "ui");
        this.source = Objects.requireNonNull(source, "source");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.pid = pid;
    }

    /**
     * Создаёт рекордер с боевым планировщиком (один фоновый поток-демон) и системными часами UTC.
     *
     * @param client клиент
     * @param stores хранилища
     * @param ui     доступ к UI-потоку
     * @param source источник состояния
     * @return рекордер
     */
    public static SessionRecorder create(String client, List<SessionStore> stores, UiExecutor ui, SnapshotSource source) {
        return new SessionRecorder(client, stores, ui, source,
                new ExecutorScheduler("cashprediction-session-" + client), Clock.systemUTC());
    }

    // ------------------------------------------------------------------ жизненный цикл

    /**
     * Начинает запись сеанса: маркер {@code running} во все доступные хранилища и периодическая запись.
     * Если прошлый сеанс завершился корректно (ни в одном хранилище нет маркера {@code running}), но его снимок
     * остался, перед маркером сразу пишется свежий снимок — так снимок в хранилище всегда относится к сеансу,
     * отмеченному маркером. Повторный вызов ничего не делает.
     */
    public void start() {
        synchronized (stateLock) {
            if (started || closed) {
                return;
            }
            started = true;
            marker = SessionMarker.running(pid, clock.instant(), client);
        }
        if (enabled) {
            // Снимок корректно закрытого прошлого сеанса заменяется свежим ДО маркера running: иначе сбой в первые
            // секунды нового сеанса выдал бы старый снимок (например, с правками, отброшенными при выходе
            // кнопкой «Не сохранять») за снимок сбоя. Снимок сбоя (маркер running) не трогается: из него, возможно,
            // только что восстановились, и новый сбой во время восстановления не должен его затереть.
            if (staleSnapshotLeft()) {
                replaceStaleSnapshot();
            }
            writeMarker();
        }
        synchronized (stateLock) {
            if (!closed) {
                periodic = scheduler.scheduleAtFixedRate(this::captureAndWriteInBackground, PERIOD, PERIOD);
            }
        }
    }

    /**
     * Выдаёт идентификатор для нового окна.
     *
     * @return {@code w1}, {@code w2}, ... — уникальные в пределах рекордера
     */
    public String nextWindowId() {
        return WINDOW_ID_PREFIX + windowCounter.incrementAndGet();
    }

    /**
     * Регистрирует показанное окно: с этого момента его состояние входит в снимок. Окно с тем же
     * идентификатором заменяется. Вызывает {@link #touch()}.
     *
     * @param window окно
     */
    public void register(StatefulWindow window) {
        Objects.requireNonNull(window, "window");
        windows.removeIf(w -> w == window || w.windowId().equals(window.windowId()));
        windows.add(window);
        touch();
    }

    /**
     * Снимает окно с регистрации (окно закрыто). Вызывает {@link #touch()}.
     * Сравнивает экземпляры: закрытие прежнего равного окна не снимает заменившую его регистрацию.
     *
     * @param window окно
     */
    public void unregister(StatefulWindow window) {
        if (window != null && windows.removeIf(registered -> registered == window)) {
            touch();
        }
    }

    /**
     * Сообщает об изменении состояния: запись состоится через {@link #DEBOUNCE} после последнего вызова.
     * До {@link #start()}, после {@link #shutdownClean()} и в отключённом рекордере ничего не делает.
     */
    public void touch() {
        if (!started || closed || !enabled) {
            return;
        }
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            if (pendingTouch != null) {
                pendingTouch.cancel();
            }
            long generation = ++touchGeneration;
            pendingTouch = scheduler.schedule(() -> onDebounceElapsed(generation), DEBOUNCE);
        }
    }

    /**
     * Записывает снимок немедленно и синхронно. Никогда не бросает исключений.
     *
     * <p>Из UI-потока: отменяет отложенную запись, снимает снимок, ждёт идущую фоновую запись не
     * дольше {@link #SAVE_NOW_WAIT} и пишет. Из другого потока (shutdown hook, обработчик исключения
     * фонового потока): UI-поток может быть занят или мёртв, поэтому пишется последний снятый снимок.</p>
     */
    public void saveNow() {
        try {
            if (!started || closed || !enabled) {
                return;
            }
            Captured captured;
            if (ui.isUiThread()) {
                cancelPendingTouch();
                captured = capture();
                if (captured == null) {
                    // UI сломан настолько, что снимок не снимается: лучше предыдущий снимок, чем ничего.
                    captured = lastCaptured;
                }
            } else {
                captured = lastCaptured;
            }
            if (captured != null) {
                write(captured, true, true);
            }
        } catch (Throwable ignored) {
            // Контракт «никогда не бросает»: метод вызывается из обработчиков сбоя, где новое
            // исключение сорвало бы показ сообщения и корректное завершение процесса.
        }
    }

    /**
     * Тихо сохраняет последний захваченный снимок при завершении JVM.
     * Не обращается к UI и не вызывает слушателей статусов, которые могут ставить задачи в остановленную очередь.
     * Не меняет маркер на closed; до старта, после чистого выхода и при отключённой записи ничего не делает.
     * Ограничено ожидание блокировки рекордера, но не время дискового IO самих хранилищ.
     */
    public void saveShutdownSnapshot() {
        try {
            if (!started || closed || !enabled) return;
            Captured captured = lastCaptured;
            if (captured != null) write(captured, true, true, false);
        } catch (Throwable ignored) {
            // Вторичный сбой записи не должен мешать завершению JVM.
        }
    }

    /**
     * Корректное завершение по явному выходу пользователя: таймеры выключаются, снимок пишется,
     * маркер становится {@code closed}, дальнейшие записи отбрасываются, планировщик останавливается.
     */
    public void shutdownClean() {
        if (closed) {
            return;
        }
        cancelTimers();
        saveNow();
        boolean writeClosedMarker;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            // closed выставляется ДО markClean: поздняя фоновая запись не должна пройти после закрытия.
            closed = true;
            writeClosedMarker = started && enabled && markerWritten;
        }
        if (writeClosedMarker) {
            applyToStores(SessionStore::markClean);
        }
        scheduler.shutdown();
    }

    /**
     * «Очистить снимки»: удаляет снимки и маркеры во всех хранилищах клиента, не выключая обнаружение сбоя.
     *
     * <p>Клиенты должны вызывать этот метод, а не {@link SessionStore#clear()} посреди сеанса: {@code clear()}
     * удаляет и маркер {@code running}, а рекордер пишет маркер только в {@link #start()}. Без повторной записи
     * маркера сбой до конца сеанса был бы принят за корректный выход, и диалог восстановления не появился бы.
     * Поэтому после очистки маркер текущего сеанса ставится заново, сведения о «уже записанных» снимках
     * забываются (иначе таймер не записал бы неизменившийся снимок в опустевшее хранилище) и назначается
     * отложенная запись текущего состояния.</p>
     *
     * <p>В отключённом рекордере (второй экземпляр программы) ничего не делает: хранилища принадлежат первому
     * экземпляру. До {@link #start()} и после {@link #shutdownClean()} только очищает хранилища. Не бросает.</p>
     */
    public void clearSnapshots() {
        if (!enabled) {
            return;
        }
        List<StoreStatus> statuses;
        // Под блокировкой записи: идущая фоновая запись не должна вклиниться между очисткой и новым маркером.
        writeLock.lock();
        try {
            synchronized (stateLock) { invalidateSnapshotEpoch(); }
            lastWrittenByStore.clear();
            lastSuccessByStore.clear();
            statuses = collectStatuses(SessionStore::clear);
            SessionMarker current;
            synchronized (stateLock) {
                current = started && !closed ? marker : null;
                if (current != null) {
                    markerWritten = true;
                }
            }
            if (current != null) {
                statuses = collectStatuses(store -> store.markDirty(current));
            }
        } finally {
            writeLock.unlock();
        }
        fire(statuses);
        touch();
    }

    /**
     * Включает или отключает запись. Второй экземпляр программы работает с отключённым рекордером,
     * чтобы не затереть маркер и снимок первого.
     *
     * @param value {@code true} — писать снимки
     */
    public void setEnabled(boolean value) {
        boolean changed;
        synchronized (stateLock) {
            changed = enabled != value;
            if (changed) invalidateSnapshotEpoch();
            enabled = value;
        }
        if (!value) {
            cancelPendingTouch();
            return;
        }
        boolean needMarker;
        synchronized (stateLock) {
            needMarker = started && !closed && !markerWritten;
        }
        if (needMarker) {
            writeMarker();
        }
        if (changed) touch();
    }

    /**
     * Подписывает слушателя на результаты операций с хранилищами.
     *
     * @param listener слушатель; вызывается в потоке записи
     */
    public void addStatusListener(Consumer<StoreStatus> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Отписывает слушателя.
     *
     * @param listener слушатель
     */
    public void removeStatusListener(Consumer<StoreStatus> listener) {
        listeners.remove(listener);
    }

    // ------------------------------------------------------------------ сведения

    /**
     * Последний снятый снимок (для меню «Показать последний снимок…» и shutdown hook).
     *
     * @return снимок или пусто, если снимков ещё не было
     */
    public Optional<SessionSnapshot> lastCaptured() {
        Captured captured = lastCaptured;
        return captured == null ? Optional.empty() : Optional.of(captured.snapshot());
    }

    /**
     * Маркер текущего сеанса.
     *
     * @return маркер или пусто до {@link #start()}
     */
    public Optional<SessionMarker> marker() {
        return Optional.ofNullable(marker);
    }

    /**
     * Момент последней успешной записи в хранилище.
     *
     * @param storeId идентификатор хранилища
     * @return момент или пусто
     */
    public Optional<Instant> lastSavedAt(String storeId) {
        return Optional.ofNullable(lastSuccessByStore.get(storeId));
    }

    /** @return клиент рекордера */
    public String client() {
        return client;
    }

    /** @return хранилища рекордера (неизменяемый список) */
    public List<SessionStore> stores() {
        return stores;
    }

    /** @return зарегистрированные окна в порядке регистрации (копия) */
    public List<StatefulWindow> registeredWindows() {
        return List.copyOf(windows);
    }

    /** @return вызывался ли {@link #start()} */
    public boolean isStarted() {
        return started;
    }

    /** @return завершён ли рекордер {@link #shutdownClean()} */
    public boolean isClosed() {
        return closed;
    }

    /** @return включена ли запись */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Проверяет завершение текущей работы без capture/save и без сравнения wall-clock времени.
     * Ждёт pending touch, queued capture и весь write/outcome callback.
     * Это только settlement; settled store/capture error не означает полный capture или store PASS.
     * Отказ dispatch остаётся неготовым до более нового завершённого запроса или отключения клиента.
     * @return нет незавершённой snapshot работы; не гарантия успешности отдельных stores
     */
    public boolean isSnapshotIdle() {
        synchronized (stateLock) {
            return pendingTouch == null && snapshotWork == 0 && acknowledgedWork >= failedDispatch
                    && !writeLock.isLocked() && !writeLock.hasQueuedThreads();
        }
    }
    /**
     * Проверяет полноту последней попытки capture в текущем lifecycle без повторного снятия.
     * Неполное окно, capture failure или fallback старого снимка не подтверждают текущий capture.
     * @return полный актуальный capture и settlement, атомарно под stateLock; не storage success
     */
    public boolean hasCompleteCurrentCapture() {
        synchronized (stateLock) {
            Captured captured = lastCaptured;
            return isSnapshotIdle() && started && !closed && enabled && captured != null && captured.complete()
                    && captured.sequence() == captureSequence.get() && captured.epoch() == snapshotEpoch;
        }
    }
    private void invalidateSnapshotEpoch() {
        snapshotEpoch++;
        acknowledgedWork = 0; failedDispatch = 0;
    }

    // ------------------------------------------------------------------ внутреннее

    private void onDebounceElapsed(long generation) {
        long work, epoch;
        synchronized (stateLock) {
            if (generation != touchGeneration || closed || !enabled) return;
            pendingTouch = null;
            work = ++workSequence; snapshotWork++;
            epoch = snapshotEpoch;
        }
        dispatchSnapshotWork(work, epoch);
    }

    /** Резервирует работу до постановки capture в UI-очередь: промежуток не выглядит idle. */
    private void captureAndWriteInBackground() {
        long work, epoch;
        synchronized (stateLock) {
            if (closed || !enabled) return;
            work = ++workSequence; snapshotWork++;
            epoch = snapshotEpoch;
        }
        dispatchSnapshotWork(work, epoch);
    }

    /** Учитывает всю цепочку capture -> scheduler write -> store outcomes, включая отказы очередей. */
    private void dispatchSnapshotWork(long work, long epoch) {
        var completed = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.function.Consumer<Boolean> finish = acknowledged -> {
            if (!completed.compareAndSet(false, true)) return;
            synchronized (stateLock) {
                snapshotWork--;
                if (epoch == snapshotEpoch) {
                    if (acknowledged) acknowledgedWork = Math.max(acknowledgedWork, work);
                    else failedDispatch = Math.max(failedDispatch, work);
                }
            }
        };
        try {
            ui.execute(() -> {
                try {
                    synchronized (stateLock) {
                        if (closed || !enabled || epoch != snapshotEpoch) { finish.accept(true); return; }
                    }
                    Captured captured = capture();
                    if (captured == null) { finish.accept(true); return; } // capture разослал явные ошибки
                    scheduler.execute(() -> {
                        try { write(captured, false, false); finish.accept(true); }
                        catch (RuntimeException | Error failure) { finish.accept(false); throw failure; }
                    });
                } catch (RuntimeException | Error failure) { finish.accept(false); throw failure; }
            });
        } catch (RuntimeException failure) {
            finish.accept(false); // прежний контракт не бросает при остановленном UI
        } catch (Error failure) { finish.accept(false); throw failure; }
    }

    /**
     * Снимает снимок; вызывается в UI-потоке.
     *
     * <p>Перехватываются любые {@link Throwable}, а не только {@link RuntimeException}: {@code saveNow()} вызывается
     * из обработчика сбоя, где окно может бросить {@code OutOfMemoryError} или {@code AssertionError} (при {@code -ea}).
     * Выпущенная ошибка лишила бы {@code saveNow()} запасного варианта — последнего снятого снимка, а на фоновом пути
     * дошла бы до обработчика исключений UI-потока, который завершает программу.</p>
     *
     * @return снимок или {@code null}, если состояние снять не удалось
     */
    private Captured capture() {
        long sequence = captureSequence.incrementAndGet();
        long epoch;
        synchronized (stateLock) { epoch = snapshotEpoch; }
        try {
            MainWindowState main = source.captureMain();
            PlanState plan = source.capturePlan();
            List<WindowState> states = new ArrayList<>();
            boolean complete = true;
            for (StatefulWindow window : windows) {
                try {
                    WindowState state = window.captureState();
                    if (state != null) {
                        states.add(state);
                        String owner = window.ownerId();
                        if (!state.id().equals(window.windowId()) || state.type() != window.windowType()
                                || state.modal() != window.modal()
                                || !state.ownerId().equals(owner == null || owner.isBlank() ? WindowState.MAIN_OWNER : owner))
                            complete = false;
                    } else complete = false;
                } catch (Throwable e) {
                    // Одно сломанное окно не должно лишить пользователя снимка остальных.
                    complete = false;
                }
            }
            SessionSnapshot snapshot = SessionSnapshot.of(clock.instant(), client, main, plan, states);
            Captured captured = new Captured(snapshot, sequence, complete, epoch);
            lastCaptured = captured;
            return captured;
        } catch (Throwable e) {
            String reason = Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName());
            List<StoreStatus> statuses = new ArrayList<>();
            for (SessionStore store : stores) {
                statuses.add(new StoreStatus(store.id(), false, lastSuccessByStore.get(store.id()),
                        Texts.get("session.recorder.captureFailed", reason)));
            }
            fire(statuses);
            return null;
        }
    }

    /**
     * Остался ли в хранилищах снимок корректно завершённого прошлого сеанса: ни в одном доступном хранилище нет
     * маркера {@code running} этого клиента, но хотя бы в одном есть снимок. Вызывается до записи своего маркера.
     */
    private boolean staleSnapshotLeft() {
        boolean anySnapshot = false;
        for (SessionStore store : stores) {
            if (!store.isAvailable()) {
                continue;
            }
            try {
                if (store.readMarker().filter(m -> m.client().equals(client) && m.isRunning()).isPresent()) {
                    return false;
                }
                anySnapshot |= store.lastSavedAt().isPresent();
            } catch (RuntimeException e) {
                // Хранилище, которое не читается, в решении не участвует.
            }
        }
        return anySnapshot;
    }

    /** Заменяет оставшийся снимок прошлого сеанса текущим состоянием (в UI-потоке — синхронно). */
    private void replaceStaleSnapshot() {
        if (ui.isUiThread()) {
            Captured captured = capture();
            if (captured != null) {
                write(captured, true, false);
            }
        } else {
            captureAndWriteInBackground();
        }
    }

    /**
     * Пишет снимок во все хранилища.
     *
     * @param captured снимок с номером
     * @param force    писать, даже если содержимое не изменилось
     * @param bounded  ждать блокировку не дольше {@link #SAVE_NOW_WAIT} (для {@link #saveNow()})
     */
    private void write(Captured captured, boolean force, boolean bounded) {
        write(captured, force, bounded, true);
    }

    /** Общая запись; shutdown-путь не уведомляет UI-слушателей после сохранения данных. */
    private void write(Captured captured, boolean force, boolean bounded, boolean notifyListeners) {
        List<StoreStatus> statuses = new ArrayList<>();
        boolean locked = false;
        try {
            if (bounded) {
                try {
                    locked = writeLock.tryLock(SAVE_NOW_WAIT.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                // Не дождались: всё равно пишем. Хранилища синхронизированы сами, а потерять
                // снимок в обработчике сбоя хуже, чем записать его чуть позже фоновой записи.
            } else {
                writeLock.lock();
                locked = true;
            }
            if (closed || !enabled || captured.epoch() != snapshotEpoch || captured.sequence() < lastWrittenSequence.get()) {
                return;
            }
            SessionSnapshot snapshot = captured.snapshot();
            for (SessionStore store : stores) {
                if (!store.isAvailable()) {
                    statuses.add(new StoreStatus(store.id(), false, lastSuccessByStore.get(store.id()),
                            Texts.get("session.store.unavailable", store.title(), store.unavailableReason())));
                    continue;
                }
                if (!force && snapshot.sameContent(lastWrittenByStore.get(store.id()))) {
                    continue;
                }
                try {
                    store.save(snapshot);
                    lastWrittenByStore.put(store.id(), snapshot);
                    lastSuccessByStore.put(store.id(), snapshot.savedAt());
                    statuses.add(new StoreStatus(store.id(), true, snapshot.savedAt(), store.recoveryNotice().orElse("")));
                } catch (SessionStoreException | RuntimeException e) {
                    // Ошибка одного хранилища не мешает записи в остальные.
                    statuses.add(new StoreStatus(store.id(), false, lastSuccessByStore.get(store.id()),
                            Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName())));
                }
            }
            lastWrittenSequence.accumulateAndGet(captured.sequence(), Math::max);
        } finally {
            if (locked) {
                writeLock.unlock();
            }
        }
        // Слушатели — вне блокировки, чтобы медленный слушатель не задерживал saveNow().
        if (notifyListeners) fire(statuses);
    }

    private void writeMarker() {
        SessionMarker current = marker;
        if (current == null) {
            return;
        }
        synchronized (stateLock) {
            markerWritten = true;
        }
        applyToStores(store -> store.markDirty(current));
    }

    /** Выполняет «тихую» операцию во всех доступных хранилищах и сообщает статус. */
    private void applyToStores(Consumer<SessionStore> action) {
        fire(collectStatuses(action));
    }

    /**
     * Выполняет «тихую» операцию во всех доступных хранилищах, не рассылая статусы (их можно разослать позже,
     * вне блокировки).
     *
     * @return статусы по хранилищам
     */
    private List<StoreStatus> collectStatuses(Consumer<SessionStore> action) {
        List<StoreStatus> statuses = new ArrayList<>();
        for (SessionStore store : stores) {
            Instant savedAt = lastSuccessByStore.get(store.id());
            if (!store.isAvailable()) {
                statuses.add(new StoreStatus(store.id(), false, savedAt,
                        Texts.get("session.store.unavailable", store.title(), store.unavailableReason())));
                continue;
            }
            try {
                action.accept(store);
                Optional<String> error = store.lastError();
                if (!store.isAvailable()) {
                    statuses.add(new StoreStatus(store.id(), false, savedAt,
                            Texts.get("session.store.unavailable", store.title(), store.unavailableReason())));
                } else if (error.isPresent()) {
                    statuses.add(new StoreStatus(store.id(), false, savedAt, error.get()));
                } else {
                    statuses.add(new StoreStatus(store.id(), true, savedAt, store.recoveryNotice().orElse("")));
                }
            } catch (RuntimeException e) {
                statuses.add(new StoreStatus(store.id(), false, savedAt,
                        Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName())));
            }
        }
        return statuses;
    }

    private void cancelPendingTouch() {
        synchronized (stateLock) {
            if (pendingTouch != null) {
                pendingTouch.cancel();
                pendingTouch = null;
                touchGeneration++;
            }
        }
    }

    private void cancelTimers() {
        synchronized (stateLock) {
            if (pendingTouch != null) {
                pendingTouch.cancel();
                pendingTouch = null;
                touchGeneration++;
            }
            if (periodic != null) {
                periodic.cancel();
                periodic = null;
            }
        }
    }

    private void fire(List<StoreStatus> statuses) {
        for (StoreStatus status : statuses) {
            for (Consumer<StoreStatus> listener : listeners) {
                try {
                    listener.accept(status);
                } catch (RuntimeException e) {
                    // Ошибка в строке состояния не должна ломать запись снимков.
                }
            }
        }
    }
}
