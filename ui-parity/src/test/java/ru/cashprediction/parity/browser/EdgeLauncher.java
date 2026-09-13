package ru.cashprediction.parity.browser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import ru.cashprediction.parity.io.Dirs;

/**
 * Запускает установленный Microsoft Edge в режиме {@code --headless=new} (запасной вариант — Google Chrome)
 * с отладочным портом, выбранным самим браузером (архитектура §6.3, решение пользователя о CDP без Node.js).
 *
 * <p>Порт 0 исключает конфликт с другим браузером или параллельным тестом; фактический порт браузер пишет
 * в файл {@value #ACTIVE_PORT_FILE} внутри {@code --user-data-dir}. Профиль браузера всегда отдельный,
 * в папке сборки: настоящий профиль пользователя не трогается, и msedge.exe не передаёт запуск уже
 * открытому окну Edge.</p>
 */
public final class EdgeLauncher {

    /** Системное свойство с явным путём к браузеру на основе Chromium. */
    public static final String BROWSER_PROPERTY = "parity.browser";

    /** Файл, в который браузер пишет порт и путь WebSocket отладки. */
    public static final String ACTIVE_PORT_FILE = "DevToolsActivePort";

    /** Возможные расположения Edge: системная установка x86 и x64, установка для пользователя. */
    static final List<Path> EDGE_CANDIDATES = List.of(
            Path.of("C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"),
            Path.of("C:/Program Files/Microsoft/Edge/Application/msedge.exe"));

    /** Запасной браузер (решение пользователя). */
    static final Path CHROME = Path.of("C:/Program Files/Google/Chrome/Application/chrome.exe");

    /** Измеренная невидимая рамка окна {@code [ширина, высота]} для каждого исполняемого файла браузера. */
    private static final Map<Path, int[]> FRAME_INSETS = new ConcurrentHashMap<>();

    private EdgeLauncher() {
    }

    /**
     * Ищет браузер: свойство {@value #BROWSER_PROPERTY}, затем Edge, затем Chrome.
     *
     * @return путь к исполняемому файлу или пусто, если браузера нет
     */
    public static Optional<Path> findBrowser() {
        String explicit = System.getProperty(BROWSER_PROPERTY);
        if (explicit != null && !explicit.isBlank()) {
            Path path = Path.of(explicit.strip());
            return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
        }
        List<Path> candidates = new ArrayList<>(EDGE_CANDIDATES);
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            candidates.add(Path.of(localAppData, "Microsoft", "Edge", "Application", "msedge.exe"));
        }
        candidates.add(CHROME);
        return candidates.stream().filter(Files::isRegularFile).findFirst();
    }

    /**
     * Описание мест поиска для сообщения о пропуске теста.
     *
     * @return перечень проверенных путей
     */
    public static String searchedLocations() {
        List<String> places = new ArrayList<>();
        EDGE_CANDIDATES.forEach(p -> places.add(p.toString()));
        places.add("%LOCALAPPDATA%\\Microsoft\\Edge\\Application\\msedge.exe");
        places.add(CHROME.toString());
        return String.join(", ", places) + " (or -D" + BROWSER_PROPERTY + "=<path>)";
    }

    /**
     * Командная строка браузера.
     *
     * @param browser     исполняемый файл
     * @param userDataDir отдельный профиль
     * @param width       ширина окна в CSS-пикселях
     * @param height      высота окна в CSS-пикселях
     * @param url         открываемый адрес
     * @return команда
     */
    public static List<String> command(Path browser, Path userDataDir, int width, int height, String url) {
        return List.of(
                browser.toString(),
                "--headless=new",
                "--window-size=" + width + "," + height,
                // Масштаб 1: снимок 1200×800 CSS-пикселей равен 1200×800 пикселей PNG при любом DPI экрана.
                "--force-device-scale-factor=1",
                "--user-data-dir=" + userDataDir,
                "--remote-debugging-port=0",
                // Без мастера первого запуска, расширений, фоновой сети и полос прокрутки: снимки воспроизводимы.
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-extensions",
                "--disable-background-networking",
                "--disable-component-update",
                "--disable-sync",
                "--hide-scrollbars",
                url);
    }

    /**
     * Запускает браузер так, чтобы <b>окно просмотра</b> страницы было ровно {@code width}×{@code height}.
     *
     * <p>Почему не просто {@code --window-size}: в {@code --headless=new} под Windows браузер создаёт скрытое
     * настоящее окно, и размер окна включает невидимую рамку. На этой машине Edge 1200×800 даёт окно просмотра
     * 1166×703, Chrome — 1174×700. Эмуляция размера ({@code Emulation.setDeviceMetricsOverride}) вне набора
     * команд стадии S0, поэтому рамка измеряется через {@code Runtime.evaluate}: браузер запускается,
     * {@code innerWidth/innerHeight} сравниваются с нужными, и при расхождении браузер перезапускается с окном,
     * увеличенным на измеренную рамку. Рамка запоминается для исполняемого файла, так что следующие запуски
     * в той же JVM сразу берут верный размер.</p>
     *
     * @param browser     исполняемый файл
     * @param userDataDir папка профиля
     * @param width       нужная ширина окна просмотра в CSS-пикселях
     * @param height      нужная высота окна просмотра в CSS-пикселях
     * @param url         открываемый адрес
     * @param timeout     наибольшее время ожидания каждого запуска
     * @return сеанс браузера с проверенным размером окна просмотра
     * @throws IllegalStateException если и после поправки размер окна просмотра не совпал
     */
    public static BrowserSession startWithViewport(Path browser, Path userDataDir, int width, int height, String url,
                                                   Duration timeout) {
        int[] inset = FRAME_INSETS.getOrDefault(browser.toAbsolutePath().normalize(), new int[] {0, 0});
        for (int attempt = 0; attempt < 2; attempt++) {
            int windowWidth = width + inset[0];
            int windowHeight = height + inset[1];
            BrowserSession session = start(browser, userDataDir, windowWidth, windowHeight, url, timeout);
            long[] viewport;
            try {
                viewport = measureViewport(session, timeout);
            } catch (RuntimeException e) {
                session.closeQuietly();
                throw e;
            }
            session.sized(windowWidth, windowHeight, (int) viewport[0], (int) viewport[1]);
            if (viewport[0] == width && viewport[1] == height) {
                FRAME_INSETS.put(browser.toAbsolutePath().normalize(), inset);
                return session;
            }
            // Рамка = окно − окно просмотра; следующий запуск добавит её к нужному размеру.
            inset = new int[] {windowWidth - (int) viewport[0], windowHeight - (int) viewport[1]};
            session.close();
            if (attempt == 1) {
                throw new IllegalStateException("Browser viewport is " + viewport[0] + "x" + viewport[1] + " instead of "
                        + width + "x" + height + " with --window-size=" + windowWidth + "," + windowHeight);
            }
        }
        throw new IllegalStateException("unreachable");
    }

    /**
     * Измеряет окно просмотра текущей вкладки.
     *
     * @param session запущенный браузер
     * @param timeout наибольшее время ожидания вкладки
     * @return {@code [innerWidth, innerHeight]}
     */
    private static long[] measureViewport(BrowserSession session, Duration timeout) {
        try (CdpClient cdp = CdpClient.connectToFirstPage(session.port(), timeout)) {
            Object value = cdp.evaluate("[window.innerWidth, window.innerHeight]");
            List<?> size = (List<?>) value;
            return new long[] {((Number) size.get(0)).longValue(), ((Number) size.get(1)).longValue()};
        }
    }

    /**
     * Запускает браузер и ждёт, пока он сообщит отладочный порт.
     *
     * @param browser     исполняемый файл
     * @param userDataDir папка профиля (очищается перед запуском и удаляется при закрытии сеанса)
     * @param width       ширина окна
     * @param height      высота окна
     * @param url         открываемый адрес
     * @param timeout     наибольшее время ожидания порта
     * @return сеанс браузера; закрывать через try-with-resources
     * @throws IllegalStateException если браузер завершился или не сообщил порт вовремя
     * @throws UncheckedIOException  если не удалось подготовить профиль или запустить процесс
     */
    public static BrowserSession start(Path browser, Path userDataDir, int width, int height, String url,
                                       Duration timeout) {
        Path profile = userDataDir.toAbsolutePath().normalize();
        try {
            Dirs.deleteRecursively(profile);
            Files.createDirectories(profile);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot prepare browser profile " + profile + ": " + e.getMessage(), e);
        }
        Path log = profile.resolveSibling(profile.getFileName() + ".browser.log");
        Process process;
        try {
            process = new ProcessBuilder(command(browser, profile, width, height, url))
                    .directory(profile.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot start " + browser + ": " + e.getMessage(), e);
        }
        BrowserSession session = new BrowserSession(browser, profile, process, log);
        try {
            Path activePort = profile.resolve(ACTIVE_PORT_FILE);
            long deadline = System.nanoTime() + timeout.toNanos();
            while (true) {
                Optional<int[]> port = readPort(activePort);
                if (port.isPresent()) {
                    session.connected(port.get()[0], readWebSocketPath(activePort));
                    return session;
                }
                if (!process.isAlive()) {
                    throw new IllegalStateException(browser + " exited with code " + process.exitValue()
                            + " before writing " + ACTIVE_PORT_FILE + "; see " + log);
                }
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException(browser + " did not write " + ACTIVE_PORT_FILE + " within "
                            + timeout + "; see " + log);
                }
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.close();
            throw new IllegalStateException("Interrupted while starting the browser", e);
        } catch (RuntimeException e) {
            session.closeQuietly();
            throw e;
        }
    }

    /**
     * Читает порт из {@value #ACTIVE_PORT_FILE}; файл может быть ещё не дописан.
     *
     * @param file файл порта
     * @return порт или пусто
     */
    private static Optional<int[]> readPort(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.size() < 2 || lines.get(0).isBlank()) {
                return Optional.empty();
            }
            int port = Integer.parseInt(lines.get(0).strip());
            return port > 0 ? Optional.of(new int[] {port}) : Optional.empty();
        } catch (IOException | NumberFormatException e) {
            // Браузер ещё пишет файл: прочитаем на следующем шаге опроса.
            return Optional.empty();
        }
    }

    private static String readWebSocketPath(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).get(1).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
