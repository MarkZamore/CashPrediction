package ru.cashprediction.web;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JOptionPane;
import ru.cashprediction.core.app.AppEnvironment;
import ru.cashprediction.core.app.LaunchOptions;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.text.Texts;

/**
 * Точка входа web-клиента CashPrediction: открывает серверный сеанс над {@code CashMemory}, запускает HTTP-сервер,
 * показывает окно статуса и открывает браузер.
 *
 * <p>Аргументы командной строки:</p>
 * <ul>
 *   <li>{@code --no-browser} — не открывать браузер (адрес печатается в консоль и виден в окне статуса);</li>
 *   <li>{@code --no-window} — не показывать окно статуса (запуск из консоли, тесты, headless).</li>
 * </ul>
 * <p>Системные свойства: {@code cashprediction.home} — папка, рядом с которой создаётся CashMemory;
 * {@code cashprediction.web.port} — строго заданный порт (иначе 8765, а если занят — любой свободный).</p>
 *
 * <p><b>Сбои.</b> После запуска серверного ядра обработчик необработанных исключений передаёт ошибку
 * контроллеру на его единственном потоке. Аварийный выход не помечает сеанс чистым; shutdown hook
 * сохраняет последний снимок. Явный выход проходит через общий сценарий закрытия приложения.</p>
 */
public final class WebMain {

    private WebMain() {
    }

    /**
     * Запускает web-сервер.
     *
     * @param args аргументы командной строки
     */
    public static void main(String[] args) {
        // Предупреждения HKLM не относятся к пользовательскому хранилищу HKCU.
        Logger.getLogger("java.util.prefs").setLevel(Level.SEVERE);
        List<String> raw = List.of(args);
        LaunchOptions launch;
        try {
            launch = LaunchOptions.parse(raw, System.getProperties());
        } catch (IllegalArgumentException error) {
            // При ошибке разбора ещё нет LaunchOptions: уважаем явный запрет окна и headless.
            boolean noWindow = GraphicsEnvironment.isHeadless()
                    || raw.stream().anyMatch(argument -> argument.equals("--no-window") || argument.startsWith("--no-window="));
            fail(noWindow, UiText.get("s2.startup.errorHeader") + ": " + error.getMessage());
            return;
        }
        startCore(launch, args);
    }

    /** Запускает единственный контроллер ядра и HTTP-отрисовщик. */
    private static void startCore(LaunchOptions options, String[] args) {
        ServerLog log = new ServerLog(true);
        boolean noWindow = options.noWindow() || GraphicsEnvironment.isHeadless();
        WebServer started = null;
        try {
            int configured = PortFinder.configuredPort();
            WebServer server = WebServer.startCore(AppEnvironment.from(options), log,
                    configured >= 0 ? configured : PortFinder.DEFAULT_PORT, configured >= 0, args);
            started = server;
            server.addStopListener(() -> {
                var port = server.coreRuntime().port();
                if (port.exitKind() == ru.cashprediction.core.app.ExitKind.HALT || port.exitKind() == ru.cashprediction.core.app.ExitKind.WEB_CRASHED)
                    Runtime.getRuntime().halt(port.exitCode());
                System.exit(port.exitCode());
            });
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> server.coreRuntime().thread().execute(
                    () -> server.coreRuntime().controller().uncaught(thread, error)));
            Runtime.getRuntime().addShutdownHook(new Thread(server.coreRuntime()::saveSnapshot, "cashprediction-core-shutdown"));
            System.out.println(Texts.get("app.web.started", server.browserUri()));
            if (options.testApi()) System.out.println("PARITY_URL " + server.browserUri());
            if (!noWindow) ServerStatusWindow.show(server, log);
            if (!options.noBrowser()) openBrowser(server, log);
        } catch (WebUpdateSession.DeferredLaunch deferred) {
            // Предстартовый барьер запретил UI; этот процесс не показывает сообщение обновлятора.
        } catch (Exception | LinkageError error) {
            // Не вызываем stop-listeners: их System.exit мог бы подменить код раннего сбоя.
            if (started != null) started.closeUpdates();
            fail(noWindow, UiText.get("s2.startup.errorHeader") + ": " + error.getMessage());
        }
    }

    /**
     * Открывает страницу в браузере по умолчанию (в отдельном потоке: {@code Desktop.browse} может ждать секунды).
     *
     * @param server сервер
     * @param log    журнал
     */
    static void openBrowser(WebServer server, ServerLog log) {
        Thread thread = new Thread(() -> {
            if (!browse(server.browserUri())) {
                log.info(Texts.get("app.web.browserUnavailable", server.browserUri()));
            }
        }, "cashprediction-browser");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Открывает адрес в браузере по умолчанию: сначала {@code Desktop.browse}, а если он недоступен или упал —
     * штатный обработчик ссылок Windows {@code rundll32 url.dll,FileProtocolHandler}.
     *
     * <p>Запасной путь нужен потому, что {@code Desktop} бывает не поддержан (урезанный jlink-образ, службы без
     * рабочего стола, редкие сбои ассоциаций). {@code rundll32} ничего не пишет на диск и не требует консоли.</p>
     *
     * @param uri адрес страницы с токеном
     * @return {@code true}, если команда открытия отдана
     */
    static boolean browse(URI uri) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri);
                return true;
            }
        } catch (Exception e) {
            // Переходим к запасному способу ниже.
        }
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            return false;
        }
        try {
            // Вывод процесса не нужен: перенаправляем в «никуда», чтобы буфер канала не заполнился.
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", uri.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Сообщает об ошибке запуска и завершает процесс.
     *
     * @param noWindow без окон (только консоль)
     * @param message  сообщение
     */
    private static void fail(boolean noWindow, String message) {
        System.err.println(message);
        if (!noWindow) {
            // JavaFX: Alert(ERROR) → Swing: JOptionPane.showMessageDialog(ERROR_MESSAGE) → Web: <dialog class="alert">
            JOptionPane.showMessageDialog(null, message, "CashPrediction Web", JOptionPane.ERROR_MESSAGE);
        }
        System.exit(1);
    }
}
