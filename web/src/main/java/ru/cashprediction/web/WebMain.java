package ru.cashprediction.web;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JOptionPane;
import ru.cashprediction.core.io.CashMemoryLayout;

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
 * <p><b>Сбои.</b> Обработчик необработанных исключений ставится первым делом, до запуска сервера и Swing:
 * он сохраняет снимок сессии и немедленно завершает процесс ({@code halt(2)}), не помечая сеанс закрытым — при
 * следующем запуске браузер предложит восстановление. Shutdown hook только сохраняет снимок (закрытие сеанса
 * отмечается исключительно при явной остановке).</p>
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
        // Реестр ядра загружает java.util.prefs, который пишет в журнал предупреждения о HKLM — они не нужны.
        Logger.getLogger("java.util.prefs").setLevel(Level.SEVERE);
        List<String> options = List.of(args);
        boolean noBrowser = options.contains("--no-browser");
        boolean noWindow = options.contains("--no-window") || GraphicsEnvironment.isHeadless();
        ServerLog log = new ServerLog(true);
        AtomicReference<ServerState> stateRef = new AtomicReference<>();

        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            log.error("Необработанное исключение в потоке " + thread.getName(), error);
            error.printStackTrace();
            ServerState state = stateRef.get();
            if (state != null) {
                // saveNow() из чужого потока пишет последний снятый снимок и никогда не бросает.
                state.recorder().saveNow();
            }
            System.err.println("Непредвиденная ошибка. Сессия сохранена, при следующем запуске её можно восстановить.");
            Runtime.getRuntime().halt(2);
        });

        ServerState state;
        WebServer server;
        try {
            // Штатная папка CashMemory рядом с приложением (или -Dcashprediction.home): создаётся при первом запуске,
            // оставшиеся после сбоя временные файлы удаляются.
            CashMemoryLayout layout = CashMemoryLayout.openDefault();
            state = ServerState.open(layout, log);
            stateRef.set(state);
            int configured = PortFinder.configuredPort();
            server = WebServer.start(state, log, configured >= 0 ? configured : PortFinder.DEFAULT_PORT, configured >= 0);
        } catch (Exception e) {
            fail(noWindow, "Не удалось запустить сервер CashPrediction: " + e.getMessage());
            return;
        }

        ServerState finalState = state;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Завершение сеанса Windows или Ctrl+C в консоли: снимок сохраняется, маркер остаётся «running».
            if (!finalState.recorder().isClosed()) {
                finalState.recorder().saveNow();
            }
        }, "cashprediction-shutdown-hook"));

        System.out.println("CashPrediction Web: " + server.browserUri());
        System.out.println("CashMemory: " + state.layout().dir());
        if (!noWindow) {
            ServerStatusWindow.show(server, log);
        }
        if (!noBrowser) {
            openBrowser(server, log);
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
                log.info("Браузер открыть не удалось: откройте адрес вручную — " + server.browserUri());
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
