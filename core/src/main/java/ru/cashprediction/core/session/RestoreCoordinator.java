package ru.cashprediction.core.session;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import ru.cashprediction.core.text.Texts;

/**
 * Восстанавливает сессию из снимка в строго определённом порядке (раздел 5.6 плана).
 *
 * <ol>
 *   <li>План: {@link RestoreTarget#loadPlan} (из снимка, если были несохранённые изменения).</li>
 *   <li>Главное окно: {@link RestoreTarget#applyMain} — геометрия, вид, период, фильтры.</li>
 *   <li>Показ главного окна: {@link RestoreTarget#showMainWindow}.</li>
 *   <li><b>{@link SessionRecorder#start()} и {@link SessionRecorder#touch()}</b> — до показа
 *       восстановленных окон. Модальный диалог Swing блокирует поток до закрытия; если бы рекордер
 *       запускался после окон, новый сбой при открытом восстановленном диалоге не был бы записан.
 *       Исключение: план с несохранёнными изменениями ({@code plan.dirty()}) не загрузился. Тогда рекордер
 *       не запускается (предупреждение {@link #RECORDER_NOT_STARTED}), иначе он затёр бы единственную копию
 *       текста плана; маркер сбоя остаётся, и снимок будет предложен снова. Клиент может проверить
 *       {@link SessionRecorder#isStarted()} и запустить запись сам, когда пользователь сохранит нужные данные.</li>
 *   <li>Окна: сначала немодальные, затем модальные, внутри групп — в порядке снимка. Каждое
 *       следующее окно открывается только после колбэка {@code onShown} предыдущего: асинхронная
 *       последовательная цепочка — единственный способ открыть несколько вложенных модальных
 *       диалогов, не заблокировав UI-поток. Окно регистрируется в рекордере в момент показа.</li>
 *   <li>Выделение строки прогноза: {@link RestoreTarget#selectRow}, затем отчёт {@code done}.</li>
 * </ol>
 *
 * <p><b>Правила восстановления окон.</b></p>
 * <ul>
 *   <li>Каждое окно получает новый идентификатор текущего сеанса; ссылки владельцев переводятся
 *       на новые идентификаторы. Владелец, который не восстановлен (неизвестен, пропущен, не
 *       открылся), заменяется главным окном с предупреждением.</li>
 *   <li>Окно неизвестного типа ({@code type == null}) пропускается с предупреждением.</li>
 *   <li>Если окно ссылается на объект плана ({@code ruleId}, {@code txId}, {@code targetId}), которого
 *       нет в восстановленном плане, оно открывается в режиме {@code mode=create} с теми же полями
 *       и предупреждением: введённые данные важнее исчезнувшего объекта.</li>
 * </ul>
 *
 * <p>Тексты предупреждений отчёта берутся из каталога текстов (ключи {@code session.restore.*}).</p>
 *
 * <p>Все методы вызываются в UI-потоке клиента; колбэки фабрики тоже приходят в UI-поток.
 * Экземпляр не хранит состояния между вызовами, потокобезопасен в этом смысле.</p>
 */
public final class RestoreCoordinator {

    /**
     * Предупреждение отчёта, когда рекордер не запущен, чтобы не затереть несохранённый план в снимке.
     *
     * <p>Текст берётся из каталога ({@code session.restore.recorderNotStarted}) при загрузке класса и больше не
     * является константой времени компиляции; прежние клиенты сравнивают строку отчёта с этим полем.</p>
     */
    public static final String RECORDER_NOT_STARTED = Texts.get("session.restore.recorderNotStarted");

    /** Создаёт координатор (состояния нет). */
    public RestoreCoordinator() {
    }

    /**
     * Восстанавливает сессию; результат приходит в {@code done} после показа последнего окна.
     *
     * @param snapshot снимок, выбранный пользователем
     * @param target   главное окно клиента
     * @param factory  фабрика окон клиента
     * @param recorder рекордер нового сеанса (ещё не запущенный)
     * @param done     получатель отчёта; вызывается ровно один раз
     */
    public void restore(SessionSnapshot snapshot, RestoreTarget target, WindowFactory factory, SessionRecorder recorder,
                        Consumer<RestoreReport> done) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(recorder, "recorder");
        Objects.requireNonNull(done, "done");
        List<String> warnings = new ArrayList<>();
        MainWindowState main = snapshot.main();

        boolean planLoaded = step(warnings, Texts.get("session.restore.planNotLoaded"),
                () -> target.loadPlan(snapshot.plan(), main.planPath(), warnings::add));
        step(warnings, Texts.get("session.restore.mainStateNotApplied"), () -> target.applyMain(main));
        step(warnings, Texts.get("session.restore.mainNotShown"), target::showMainWindow);

        // Несохранённый текст плана существует только в снимке. Если он не открылся, первая же запись нового
        // сеанса (через 400 мс) заменила бы его во всех хранилищах текущим, пустым планом — и повторить
        // восстановление было бы не из чего. Файл плана на диске (plan.dirty() == false) такой опасности не несёт.
        if (planLoaded || !snapshot.plan().dirty()) {
            recorder.start();
            recorder.touch();
        } else {
            warnings.add(RECORDER_NOT_STARTED);
        }

        Set<String> existing;
        try {
            existing = Set.copyOf(target.existingTargetIds());
        } catch (RuntimeException e) {
            warnings.add(Texts.get("session.restore.targetIdsFailed", e.getMessage()));
            existing = Set.of();
        }

        List<WindowState> ordered = new ArrayList<>();
        snapshot.windows().stream().filter(w -> !w.modal()).forEach(ordered::add);
        snapshot.windows().stream().filter(WindowState::modal).forEach(ordered::add);

        new Chain(ordered, existing, target, factory, recorder, warnings, main.selectedRowId(), done).next();
    }

    /**
     * «Не восстанавливать»: удаляет маркеры и снимки во всех хранилищах клиента.
     *
     * @param stores хранилища клиента
     */
    public static void startFresh(List<SessionStore> stores) {
        for (SessionStore store : stores) {
            try {
                store.clear();
            } catch (RuntimeException e) {
                // Контракт clear() — не бросать; но и чужая ошибка не должна помешать запуску программы.
            }
        }
    }

    /**
     * Выполняет шаг восстановления; исключение превращается в предупреждение отчёта.
     *
     * @param warnings предупреждения отчёта
     * @param what     название шага на языке интерфейса
     * @param action   шаг
     * @return {@code true}, если шаг прошёл без исключения
     */
    private static boolean step(List<String> warnings, String what, Runnable action) {
        try {
            action.run();
            return true;
        } catch (RuntimeException e) {
            warnings.add(Texts.get("session.restore.stepFailed", what, reason(e)));
            return false;
        }
    }

    /** @return сообщение исключения или, если его нет, простое имя класса */
    private static String reason(RuntimeException e) {
        return Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName());
    }

    /** Последовательная цепочка открытия окон одного восстановления. */
    private static final class Chain {
        private final List<WindowState> ordered;
        private final Set<String> existingTargets;
        private final RestoreTarget target;
        private final WindowFactory factory;
        private final SessionRecorder recorder;
        private final List<String> warnings;
        private final String selectedRowId;
        private final Consumer<RestoreReport> done;
        /** Старый идентификатор окна из снимка → идентификатор показанного окна. */
        private final Map<String, String> idMap = new HashMap<>();
        private int index;
        private int restored;
        private boolean finished;

        Chain(List<WindowState> ordered, Set<String> existingTargets, RestoreTarget target, WindowFactory factory,
              SessionRecorder recorder, List<String> warnings, String selectedRowId, Consumer<RestoreReport> done) {
            this.ordered = ordered;
            this.existingTargets = existingTargets;
            this.target = target;
            this.factory = factory;
            this.recorder = recorder;
            this.warnings = warnings;
            this.selectedRowId = selectedRowId;
            this.done = done;
        }

        /** Открывает следующее окно или завершает восстановление. */
        void next() {
            while (index < ordered.size()) {
                WindowState original = ordered.get(index++);
                if (original.type() == null) {
                    warnings.add(Texts.get("session.restore.unknownWindowType", original.id()));
                    continue;
                }
                String title = original.type().title();
                String owner = remapOwner(original, title);
                Map<String, String> context = checkTarget(original, title);
                WindowState prepared = new WindowState(recorder.nextWindowId(), original.type(), original.modal(),
                        owner, original.bounds(), context, original.fields());
                AtomicBoolean answered = new AtomicBoolean();
                try {
                    factory.open(prepared, owner,
                            window -> {
                                if (!answered.compareAndSet(false, true)) {
                                    return;
                                }
                                recorder.register(window);
                                idMap.put(original.id(), window.windowId());
                                restored++;
                                next();
                            },
                            reason -> {
                                if (!answered.compareAndSet(false, true)) {
                                    return;
                                }
                                warnings.add(Texts.get("session.restore.windowNotRestored", title, reason));
                                next();
                            });
                } catch (RuntimeException e) {
                    if (answered.compareAndSet(false, true)) {
                        warnings.add(Texts.get("session.restore.windowNotRestored", title, reason(e)));
                        continue;
                    }
                }
                // Дальше цепочку продолжит колбэк фабрики (возможно, он уже это сделал синхронно).
                return;
            }
            finish();
        }

        private String remapOwner(WindowState original, String title) {
            String oldOwner = original.ownerId();
            if (WindowState.MAIN_OWNER.equals(oldOwner)) {
                return WindowState.MAIN_OWNER;
            }
            String mapped = idMap.get(oldOwner);
            if (mapped != null) {
                return mapped;
            }
            warnings.add(Texts.get("session.restore.ownerNotRestored", title, oldOwner));
            return WindowState.MAIN_OWNER;
        }

        private Map<String, String> checkTarget(WindowState original, String title) {
            Map<String, String> context = original.context();
            if (WindowType.MODE_CREATE.equals(context.get(WindowType.CONTEXT_MODE))) {
                return context;
            }
            for (String key : WindowType.TARGET_CONTEXT_KEYS) {
                String value = context.get(key);
                if (value != null && !value.isBlank() && !existingTargets.contains(value)) {
                    Map<String, String> changed = new LinkedHashMap<>(context);
                    changed.put(WindowType.CONTEXT_MODE, WindowType.MODE_CREATE);
                    warnings.add(Texts.get("session.restore.targetNotFound", value, title));
                    return changed;
                }
            }
            return context;
        }

        private void finish() {
            if (finished) {
                return;
            }
            finished = true;
            if (!selectedRowId.isBlank()) {
                step(warnings, Texts.get("session.restore.rowNotSelected"), () -> target.selectRow(selectedRowId));
            }
            done.accept(new RestoreReport(warnings, restored));
        }
    }
}
