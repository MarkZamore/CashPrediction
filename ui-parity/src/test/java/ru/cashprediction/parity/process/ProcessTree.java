package ru.cashprediction.parity.process;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Завершение процесса вместе со всеми потомками через {@link ProcessHandle}.
 *
 * <p>Почему не просто {@code destroyForcibly()} корня: лаунчер jpackage перезапускает себя дочерним процессом,
 * Edge порождает десяток процессов рендеринга и GPU. В Windows потомки не умирают вместе с родителем,
 * а после смерти родителя их уже не найти через {@link ProcessHandle#descendants()}.</p>
 *
 * <p>Алгоритм:</p>
 * <ol>
 *   <li>снимок дерева (корень и потомки) делается <b>до</b> завершения;</li>
 *   <li>процессы завершаются сверху вниз: корень первым, чтобы он не успел перезапустить убитого потомка
 *       (так делает браузер с процессом GPU);</li>
 *   <li>раунд повторяется для ещё живых процессов снимка — вдруг кто-то успел породить нового потомка;</li>
 *   <li>ожидание выхода и проверка выживших с учётом времени старта (pid в Windows переиспользуются).</li>
 * </ol>
 */
public final class ProcessTree {

    /** Сколько раундов «снимок — завершение» делать при появлении новых потомков. */
    private static final int MAX_ROUNDS = 5;

    /** Наибольшая глубина при вычислении уровня процесса в дереве (защита от циклов pid). */
    private static final int MAX_DEPTH = 64;

    private ProcessTree() {
    }

    /**
     * Сведения о процессе, по которым его можно узнать и после завершения.
     *
     * @param pid          идентификатор процесса
     * @param startInstant время старта, если ОС его сообщила
     * @param command      исполняемый файл или пустая строка
     */
    public record ProcessInfo(long pid, Optional<Instant> startInstant, String command) {

        /**
         * Снимает сведения с дескриптора.
         *
         * @param handle дескриптор процесса
         * @return сведения
         */
        public static ProcessInfo of(ProcessHandle handle) {
            ProcessHandle.Info info = handle.info();
            return new ProcessInfo(handle.pid(), info.startInstant(), info.command().orElse(""));
        }

        /**
         * Жив ли именно этот процесс, а не новый процесс с тем же переиспользованным pid.
         *
         * @return {@code true}, если процесс с этим pid и тем же временем старта ещё работает
         */
        public boolean isAlive() {
            return ProcessHandle.of(pid)
                    .filter(ProcessHandle::isAlive)
                    .filter(h -> startInstant.isEmpty() || h.info().startInstant().isEmpty()
                            || h.info().startInstant().equals(startInstant))
                    .isPresent();
        }
    }

    /**
     * Итог завершения дерева.
     *
     * @param root      корень дерева
     * @param processes все процессы, которые были найдены в дереве (корень первым)
     * @param survivors процессы, оставшиеся живыми после ожидания
     */
    public record KillReport(ProcessInfo root, List<ProcessInfo> processes, List<ProcessInfo> survivors) {

        /** Копирует списки. */
        public KillReport {
            processes = List.copyOf(processes);
            survivors = List.copyOf(survivors);
        }

        /** @return {@code true}, если не выжил ни один процесс дерева */
        public boolean isClean() {
            return survivors.isEmpty();
        }

        /**
         * Проверяет, что никто не выжил.
         *
         * @return этот же отчёт
         * @throws IllegalStateException со списком выживших pid и команд
         */
        public KillReport requireClean() {
            if (!isClean()) {
                throw new IllegalStateException("Processes survived killing the tree of pid " + root.pid() + ": "
                        + survivors);
            }
            return this;
        }

        /**
         * Был ли процесс с этим pid найден в дереве.
         *
         * @param pid идентификатор процесса
         * @return {@code true}, если процесс входил в дерево
         */
        public boolean contains(long pid) {
            return processes.stream().anyMatch(p -> p.pid() == pid);
        }
    }

    /**
     * Снимок живого дерева: корень и все потомки, упорядоченные сверху вниз.
     *
     * @param root корень
     * @return корень (если жив) и потомки по возрастанию глубины
     */
    public static List<ProcessHandle> tree(ProcessHandle root) {
        List<ProcessHandle> result = new ArrayList<>();
        if (root.isAlive()) {
            result.add(root);
        }
        root.descendants().filter(ProcessHandle::isAlive)
                .sorted(Comparator.comparingInt(h -> depth(h, root)))
                .forEach(result::add);
        return result;
    }

    /**
     * Завершает процесс и всех его потомков и ждёт их выхода.
     *
     * @param process процесс, запущенный через {@link ProcessBuilder}
     * @param timeout наибольшее время ожидания выхода всех процессов
     * @return отчёт с выжившими (пустой список — успех)
     */
    public static KillReport kill(Process process, Duration timeout) {
        return kill(process.toHandle(), timeout);
    }

    /**
     * Завершает процесс и всех его потомков и ждёт их выхода.
     *
     * @param root    корень дерева
     * @param timeout наибольшее время ожидания выхода всех процессов
     * @return отчёт с выжившими (пустой список — успех)
     */
    public static KillReport kill(ProcessHandle root, Duration timeout) {
        ProcessInfo rootInfo = ProcessInfo.of(root);
        Map<Long, ProcessHandle> known = new LinkedHashMap<>();
        Map<Long, ProcessInfo> infos = new LinkedHashMap<>();
        known.put(root.pid(), root);
        infos.put(root.pid(), rootInfo);

        for (int round = 0; round < MAX_ROUNDS; round++) {
            // Снимок до завершения: у мёртвого родителя потомков уже не найти.
            List<ProcessHandle> snapshot = new ArrayList<>();
            for (ProcessHandle handle : List.copyOf(known.values())) {
                for (ProcessHandle member : tree(handle)) {
                    if (known.putIfAbsent(member.pid(), member) == null) {
                        infos.put(member.pid(), ProcessInfo.of(member));
                    }
                    snapshot.add(member);
                }
            }
            List<ProcessHandle> alive = snapshot.stream().distinct().filter(ProcessHandle::isAlive).toList();
            if (alive.isEmpty()) {
                break;
            }
            // Сверху вниз: snapshot уже упорядочен так внутри каждого поддерева, корень известных — первым.
            alive.forEach(ProcessHandle::destroyForcibly);
            waitForExit(alive, Duration.ofMillis(500));
        }

        waitForExit(known.values(), timeout);
        List<ProcessInfo> survivors = infos.values().stream().filter(ProcessInfo::isAlive).toList();
        return new KillReport(rootInfo, List.copyOf(infos.values()), survivors);
    }

    /**
     * Живые процессы из списка сведений.
     *
     * @param processes сведения о процессах
     * @return те, что ещё работают
     */
    public static List<ProcessInfo> survivors(Collection<ProcessInfo> processes) {
        return processes.stream().filter(ProcessInfo::isAlive).toList();
    }

    private static void waitForExit(Collection<ProcessHandle> handles, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        for (ProcessHandle handle : handles) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return;
            }
            try {
                handle.onExit().get(left, TimeUnit.NANOSECONDS);
            } catch (TimeoutException | ExecutionException e) {
                // Выживших посчитает вызывающий код по времени старта; здесь только ожидание.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static int depth(ProcessHandle handle, ProcessHandle root) {
        int depth = 0;
        Optional<ProcessHandle> parent = handle.parent();
        while (parent.isPresent() && parent.get().pid() != root.pid() && depth < MAX_DEPTH) {
            depth++;
            parent = parent.get().parent();
        }
        return depth;
    }
}
