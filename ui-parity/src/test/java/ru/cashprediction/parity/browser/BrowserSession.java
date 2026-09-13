package ru.cashprediction.parity.browser;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import ru.cashprediction.parity.io.Dirs;
import ru.cashprediction.parity.process.ProcessTree;

/**
 * Запущенный {@link EdgeLauncher} браузер: процесс, отладочный порт и временный профиль.
 *
 * <p>{@link #close()} завершает всё дерево процессов браузера (рендеры, GPU, утилиты), удаляет профиль
 * и падает, если какой-то процесс выжил.</p>
 */
public final class BrowserSession implements AutoCloseable {

    /** Сколько ждать завершения процессов браузера. */
    private static final Duration KILL_TIMEOUT = Duration.ofSeconds(20);

    private final Path executable;
    private final Path userDataDir;
    private final Process process;
    private final Path log;
    private int port;
    private String browserWebSocketPath;
    private ProcessTree.KillReport killReport;
    private String windowSize = "";
    private String viewportSize = "";

    /**
     * Создаётся только {@link EdgeLauncher#start}.
     *
     * @param executable  исполняемый файл браузера
     * @param userDataDir временный профиль
     * @param process     процесс браузера
     * @param log         журнал вывода браузера
     */
    BrowserSession(Path executable, Path userDataDir, Process process, Path log) {
        this.executable = executable;
        this.userDataDir = userDataDir;
        this.process = process;
        this.log = log;
    }

    /**
     * Запоминает данные из {@code DevToolsActivePort}.
     *
     * @param port          отладочный порт
     * @param webSocketPath путь WebSocket браузера, например {@code /devtools/browser/<id>}
     */
    void connected(int port, String webSocketPath) {
        this.port = port;
        this.browserWebSocketPath = webSocketPath;
    }

    /**
     * Запоминает размер окна и измеренный размер окна просмотра ({@link EdgeLauncher#startWithViewport}).
     *
     * @param windowWidth    переданная ширина {@code --window-size}
     * @param windowHeight   переданная высота {@code --window-size}
     * @param viewportWidth  измеренный {@code innerWidth}
     * @param viewportHeight измеренный {@code innerHeight}
     */
    void sized(int windowWidth, int windowHeight, int viewportWidth, int viewportHeight) {
        this.windowSize = windowWidth + "x" + windowHeight;
        this.viewportSize = viewportWidth + "x" + viewportHeight;
    }

    /** @return переданный {@code --window-size} в виде {@code ШxВ} или пустая строка, если размер не проверялся */
    public String windowSize() {
        return windowSize;
    }

    /** @return измеренное окно просмотра в виде {@code ШxВ} или пустая строка, если размер не проверялся */
    public String viewportSize() {
        return viewportSize;
    }

    /** @return исполняемый файл браузера */
    public Path executable() {
        return executable;
    }

    /** @return временный профиль браузера */
    public Path userDataDir() {
        return userDataDir;
    }

    /** @return процесс браузера */
    public Process process() {
        return process;
    }

    /** @return журнал вывода браузера */
    public Path log() {
        return log;
    }

    /** @return отладочный порт на 127.0.0.1 */
    public int port() {
        return port;
    }

    /** @return путь WebSocket уровня браузера */
    public String browserWebSocketPath() {
        return browserWebSocketPath;
    }

    /**
     * Завершает дерево процессов браузера (повторный вызов возвращает прежний отчёт).
     *
     * @return отчёт о завершении
     */
    public synchronized ProcessTree.KillReport kill() {
        if (killReport == null) {
            killReport = ProcessTree.kill(process.toHandle(), KILL_TIMEOUT);
        }
        return killReport;
    }

    /**
     * Завершает браузер, удаляет профиль и проверяет, что процессов не осталось.
     *
     * @throws IllegalStateException если процесс браузера выжил
     */
    @Override
    public void close() {
        ProcessTree.KillReport report = kill();
        deleteProfileWithRetries();
        report.requireClean();
    }

    /** Закрывает сеанс, не выбрасывая исключений (путь ошибки при запуске). */
    void closeQuietly() {
        try {
            close();
        } catch (RuntimeException ignored) {
            // Исходная ошибка запуска важнее; выживших процессов покажет следующий прогон.
        }
    }

    private void deleteProfileWithRetries() {
        // Файлы профиля освобождаются с небольшой задержкой после завершения процессов.
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                Dirs.deleteRecursively(userDataDir);
                return;
            } catch (IOException e) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

}
