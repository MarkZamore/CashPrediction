package ru.cashprediction.parity.browser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
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
    private final Map<Long, ProcessTree.ProcessInfo> owned = new LinkedHashMap<>();
    private Set<Long> profilePids = Set.of();

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
            captureOwned();
            var root = ProcessTree.ProcessInfo.of(process.toHandle());
            Map<Long, ProcessTree.ProcessInfo> observed = new LinkedHashMap<>(owned);
            if (process.isAlive()) {
                var report = ProcessTree.kill(process.toHandle(), KILL_TIMEOUT);
                report.processes().forEach(info -> observed.put(info.pid(), info));
            }
            // Edge может завершить лаунчер с кодом 0 и оставить настоящий браузер
            // дочерним процессом. Завершаем только процессы нашего уникального профиля.
            for (var info : List.copyOf(owned.values())) if (info.isAlive()) {
                var handle = ProcessHandle.of(info.pid()).orElseThrow();
                if (!belongsToProfile(handle)) throw new IllegalStateException("Browser process ownership changed");
                var report = ProcessTree.kill(handle, KILL_TIMEOUT);
                report.processes().forEach(member -> observed.put(member.pid(), member));
            }
            captureOwned();
            owned.forEach(observed::put);
            killReport = new ProcessTree.KillReport(root, List.copyOf(observed.values()),
                    ProcessTree.survivors(observed.values()));
        }
        return killReport;
    }

    /** Запоминает реальные процессы только по точному пути exe и уникальному тестовому профилю. */
    synchronized void captureOwned() {
        // Windows JDK не отдаёт arguments/commandLine для чужого процесса.
        // CIM возвращает только pid точного exe с точным уникальным профилем,
        // без вывода команд пользователя, адресов или секретов в журнал.
        if (System.getProperty("os.name").startsWith("Windows")) profilePids = windowsProfilePids();
        try (var processes = ProcessHandle.allProcesses()) {
            processes.filter(ProcessHandle::isAlive).filter(this::belongsToProfile)
                    .forEach(handle -> owned.putIfAbsent(handle.pid(), ProcessTree.ProcessInfo.of(handle)));
        }
    }

    /** Проверяет независимую принадлежность процесса, не полагаясь на уже завершённый pid лаунчера. */
    private boolean belongsToProfile(ProcessHandle handle) {
        var info = handle.info();
        if (!info.command().map(command -> Path.of(command).toAbsolutePath().normalize()
                .equals(executable.toAbsolutePath().normalize())).orElse(false)) return false;
        String expected = "--user-data-dir=" + userDataDir;
        if (info.arguments().isPresent())
            return java.util.Arrays.stream(info.arguments().orElseThrow()).anyMatch(expected::equalsIgnoreCase);
        return info.commandLine().map(command -> hasProfileArgument(command, userDataDir))
                .orElseGet(() -> profilePids.contains(handle.pid()));
    }

    /** Сопоставляет целый аргумент Windows с учётом двух вариантов кавычек, но не префикс пути. */
    static boolean hasProfileArgument(String command, Path profile) {
        String path = Pattern.quote(profile.toString());
        String unquoted = profile.toString().chars().anyMatch(Character::isWhitespace)
                ? "" : "|--user-data-dir=" + path;
        return Pattern.compile("(?:^|\\s)(?:\"--user-data-dir=" + path + "\"|--user-data-dir=\""
                + path + "\"" + unquoted + ")(?=\\s|$)", Pattern.CASE_INSENSITIVE)
                .matcher(command).find();
    }

    /** Читает только идентификаторы процессов нашего профиля штатным Windows CIM. */
    private Set<Long> windowsProfilePids() {
        String profile = userDataDir.toString().replace("'", "''");
        String exe = executable.toAbsolutePath().normalize().toString().replace("'", "''");
        String script = "$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue';$p='" + profile + "';$e='" + exe + "';"
                + "$q=[regex]::Escape($p);$r='(?:^|\\s)(?:\"--user-data-dir='+$q+'\"|--user-data-dir=\"'+$q+'\"';"
                + "if($p -notmatch '\\s'){$r+='|--user-data-dir='+$q};$r+=')(?=\\s|$)';"
                + "$ids=@(Get-CimInstance Win32_Process |Where-Object {$_.ExecutablePath -eq $e -and $_.CommandLine -match $r}"
                + "|ForEach-Object {$_.ProcessId});[Console]::Out.Write([string]::Join(',',[string[]]$ids))";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        Path powershell = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        Process probe = null;
        try {
            probe = new ProcessBuilder(powershell.toString(), "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                    "-EncodedCommand", encoded).redirectErrorStream(true).start();
            if (!probe.waitFor(10, TimeUnit.SECONDS)) throw new IllegalStateException("Owned browser CIM query timed out");
            String output = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (probe.exitValue() != 0 || !output.matches("(?:[0-9]+(?:,[0-9]+)*)?"))
                throw new IllegalStateException("Owned browser CIM query did not return strict pid evidence: exit="
                        + probe.exitValue() + ", prefixCodePoints=" + output.codePoints().limit(32).boxed().toList());
            Set<Long> result = new LinkedHashSet<>();
            if (!output.isEmpty()) for (String pid : output.split(",")) result.add(Long.parseLong(pid));
            return Set.copyOf(result);
        } catch (IOException error) { throw new UncheckedIOException("Cannot inspect owned browser processes", error); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Owned browser CIM query interrupted", error);
        } finally { if (probe != null && probe.isAlive()) ProcessTree.kill(probe, KILL_TIMEOUT).requireClean(); }
    }

    /**
     * Завершает браузер, удаляет профиль и проверяет, что процессов не осталось.
     *
     * @throws IllegalStateException если процесс браузера выжил
     */
    @Override
    public void close() {
        ProcessTree.KillReport report = kill();
        report.requireClean();
        deleteProfileWithRetries();
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
        try { ProfileCleanup.delete(userDataDir); }
        catch (IOException failure) {
            // Оставшийся профиль не выдаётся за успешную очистку стенда.
            throw new UncheckedIOException("Cannot clean browser profile " + userDataDir, failure);
        }
    }

}
