package ru.cashprediction.parity.launch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import ru.cashprediction.parity.process.ProcessTree;

/**
 * Клиент, запущенный {@link ClientLauncher}: процесс, его команда и журналы.
 *
 * <p>{@link #close()} завершает всё дерево процессов и падает, если кто-то выжил: так тест, забывший
 * о дочерних процессах, не оставит их после себя незаметно.</p>
 */
public final class LaunchedClient implements AutoCloseable {

    /** Сколько ждать завершения дерева процессов. */
    private static final Duration KILL_TIMEOUT = Duration.ofSeconds(20);

    /** Шаг опроса при ожидании условий. */
    private static final Duration POLL = Duration.ofMillis(100);

    private final ClientTarget target;
    private final LaunchRequest request;
    private final List<String> command;
    private final Process process;
    private final Path stdout;
    private final Path stderr;
    private ProcessTree.KillReport killReport;

    /**
     * Создаётся только {@link ClientLauncher#launch}.
     *
     * @param target  цель запуска
     * @param request параметры запуска
     * @param command выполненная команда
     * @param process процесс JVM клиента
     * @param stdout  журнал стандартного вывода
     * @param stderr  журнал потока ошибок
     */
    LaunchedClient(ClientTarget target, LaunchRequest request, List<String> command, Process process, Path stdout,
                   Path stderr) {
        this.target = target;
        this.request = request;
        this.command = command;
        this.process = process;
        this.stdout = stdout;
        this.stderr = stderr;
    }

    /** @return цель запуска */
    public ClientTarget target() {
        return target;
    }

    /** @return параметры запуска */
    public LaunchRequest request() {
        return request;
    }

    /** @return выполненная команда */
    public List<String> command() {
        return command;
    }

    /** @return процесс JVM клиента */
    public Process process() {
        return process;
    }

    /** @return идентификатор процесса JVM клиента */
    public long pid() {
        return process.pid();
    }

    /** @return журнал стандартного вывода */
    public Path stdout() {
        return stdout;
    }

    /** @return журнал потока ошибок */
    public Path stderr() {
        return stderr;
    }

    /**
     * Ждёт появления файла, который пишет клиент (например, отметки готовности самотеста).
     *
     * @param file    ожидаемый файл
     * @param timeout наибольшее время ожидания
     * @throws IllegalStateException если процесс завершился раньше или время вышло; в сообщении хвост stderr
     */
    public void waitForFile(Path file, Duration timeout) {
        waitUntil(() -> Files.isRegularFile(file), timeout, "file " + file);
    }

    /**
     * Ждёт выполнения условия, пока процесс жив.
     *
     * @param condition условие
     * @param timeout   наибольшее время ожидания
     * @param what      что ждём (для сообщения об ошибке)
     * @throws IllegalStateException если процесс завершился раньше или время вышло
     */
    public void waitUntil(BooleanSupplier condition, Duration timeout, String what) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (!process.isAlive()) {
                // Условие могло выполниться в последний момент перед выходом процесса.
                if (condition.getAsBoolean()) {
                    return;
                }
                throw new IllegalStateException(target.client() + " exited with code " + process.exitValue()
                        + " while waiting for " + what + "\n" + stderrTail());
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Timed out after " + timeout + " waiting for " + what + "\n" + stderrTail());
            }
            try {
                Thread.sleep(POLL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for " + what, e);
            }
        }
    }

    /**
     * Завершает процесс и всех его потомков (повторный вызов возвращает прежний отчёт).
     *
     * @return отчёт: кто завершён и кто выжил
     */
    public synchronized ProcessTree.KillReport kill() {
        if (killReport == null) {
            killReport = ProcessTree.kill(process.toHandle(), KILL_TIMEOUT);
        }
        return killReport;
    }

    /**
     * Последние строки журнала ошибок для диагностики.
     *
     * @return хвост stderr или пометка, что журнал не прочитан
     */
    public String stderrTail() {
        try {
            if (!Files.exists(stderr)) {
                return "(no stderr log)";
            }
            List<String> lines = Files.readAllLines(stderr, StandardCharsets.UTF_8);
            return "stderr tail:\n" + String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
        } catch (IOException | RuntimeException e) {
            // Журнал в чужой кодировке или ещё занят процессом — диагностика не должна ломать основную ошибку.
            return "(stderr unreadable: " + e.getMessage() + ")";
        }
    }

    /**
     * Завершает дерево процессов.
     *
     * @throws IllegalStateException если после завершения остались живые процессы дерева
     */
    @Override
    public void close() {
        kill().requireClean();
    }
}
