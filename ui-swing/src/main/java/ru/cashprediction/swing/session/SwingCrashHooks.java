package ru.cashprediction.swing.session;

import java.awt.GraphicsEnvironment;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import ru.cashprediction.core.session.SessionRecorder;
import ru.cashprediction.swing.dialog.SwingAlert;
import ru.cashprediction.swing.dialog.SwingText;

/**
 * Обработка аварийных ситуаций Swing-клиента: необработанные исключения и завершение JVM.
 *
 * <p><b>Необработанное исключение</b> (в любом потоке, в том числе в EDT — начиная с Java 7 EDT передаёт
 * исключение обработчику по умолчанию и продолжает работу): сначала {@code recorder.saveNow()} — снимок
 * сессии с последними введёнными значениями, затем сообщение «Непредвиденная ошибка. Сессия сохранена…» со
 * стеком в «Подробнее», затем {@code Runtime.halt(2)}. Именно {@code halt}, а не {@code System.exit}: маркер
 * сеанса должен остаться {@code running}, чтобы при следующем запуске предложить восстановление, а shutdown
 * hook не нужен — снимок уже записан.</p>
 *
 * <p><b>Shutdown hook</b> (завершение сеанса Windows, Ctrl+C в консоли) пишет последний снятый снимок без
 * отметки о корректном выходе; после {@code shutdownClean()} рекордер закрыт и hook ничего не делает.</p>
 *
 * <p>Обработчик устанавливается в {@code main()} до запуска Swing. Поля статические и {@code volatile}:
 * обработчик вызывается из произвольных потоков.</p>
 */
public final class SwingCrashHooks {

    /** Код завершения процесса после необработанного исключения. */
    public static final int HALT_UNCAUGHT = 2;

    /** Текст сообщения об ошибке — общий для трёх клиентов. */
    public static final String MESSAGE = "Непредвиденная ошибка. Сессия сохранена, при следующем запуске её можно восстановить.";

    private static volatile SessionRecorder recorder;
    private static final AtomicBoolean HANDLING = new AtomicBoolean();
    /** Через сколько миллисекунд закрыть сообщение об ошибке само (0 — ждать пользователя; задаёт самотест). */
    private static volatile int autoCloseMillis;

    private SwingCrashHooks() {
    }

    /**
     * Устанавливает обработчик необработанных исключений по умолчанию и shutdown hook.
     * Вызывается один раз из {@code main()} до создания окон.
     */
    public static void install() {
        Thread.setDefaultUncaughtExceptionHandler(SwingCrashHooks::uncaught);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            SessionRecorder current = recorder;
            if (current != null) {
                // Из не-UI потока saveNow пишет последний снятый снимок; после shutdownClean ничего не делает.
                current.saveNow();
            }
        }, "cashprediction-swing-shutdown"));
    }

    /**
     * Сообщает обработчику рекордер текущего сеанса.
     *
     * @param sessionRecorder рекордер
     */
    public static void setRecorder(SessionRecorder sessionRecorder) {
        recorder = Objects.requireNonNull(sessionRecorder, "sessionRecorder");
    }

    /**
     * Включает автоматическое закрытие сообщения об ошибке (режим самотеста): без этого процесс ждал бы щелчка
     * пользователя, и проверяющий сценарий не дождался бы завершения с кодом 2.
     *
     * @param millis задержка в миллисекундах; 0 — не закрывать само
     */
    public static void setAutoCloseMillis(int millis) {
        autoCloseMillis = Math.max(0, millis);
    }

    /**
     * Обработчик необработанного исключения: снимок, сообщение, аварийное завершение.
     *
     * @param thread поток, в котором возникло исключение
     * @param error  исключение
     */
    public static void uncaught(Thread thread, Throwable error) {
        if (!HANDLING.compareAndSet(false, true)) {
            // Второе исключение во время показа сообщения: снимок уже записан, просто завершаемся.
            Runtime.getRuntime().halt(HALT_UNCAUGHT);
            return;
        }
        try {
            System.err.println("CashPrediction: необработанное исключение в потоке " + thread.getName());
            error.printStackTrace();
        } catch (Throwable ignored) {
            // Консоли может не быть (лаунчер без консоли) — это не повод не сохранить сессию.
        }
        SessionRecorder current = recorder;
        if (current != null) {
            // В EDT снимок снимается заново (с последними значениями полей), в другом потоке — последний снятый.
            current.saveNow();
        }
        if (GraphicsEnvironment.isHeadless()) {
            Runtime.getRuntime().halt(HALT_UNCAUGHT);
            return;
        }
        if (SwingUtilities.isEventDispatchThread()) {
            showAndHalt(error);
        } else {
            try {
                // invokeAndWait работает и тогда, когда EDT крутит вложенный цикл модального окна.
                SwingUtilities.invokeAndWait(() -> showAndHalt(error));
            } catch (Exception | Error ignored) {
                // EDT недоступен: показать сообщение нельзя, снимок уже записан.
            }
            Runtime.getRuntime().halt(HALT_UNCAUGHT);
        }
    }

    /** Показывает сообщение об ошибке (блокирующе — это последний шаг жизни процесса) и завершает процесс. */
    private static void showAndHalt(Throwable error) {
        try {
            // JavaFX: Alert(ERROR) с expandableContent → Swing: SwingAlert (JOptionPane.createDialog) → Web: <dialog class="alert">
            SwingAlert alert = new SwingAlert(null, SwingAlert.AlertType.ERROR, "CashPrediction - ошибка", MESSAGE,
                    error.getClass().getSimpleName() + ": " + Objects.requireNonNullElse(error.getMessage(), "без описания"));
            alert.setDetailsText(SwingText.stackTrace(error));
            alert.prepareForShow();
            int delay = autoCloseMillis;
            if (delay > 0) {
                // Таймер Swing срабатывает и во вложенном цикле модального окна: сообщение закроется само.
                javax.swing.Timer close = new javax.swing.Timer(delay, e -> alert.pressDefault());
                close.setRepeats(false);
                close.start();
            }
            // Здесь, в отличие от остальных окон, допустим блокирующий показ: после закрытия процесс завершается.
            alert.window().setVisible(true);
        } catch (Throwable ignored) {
            // Сообщение не показалось — не страшно: снимок уже записан.
        } finally {
            Runtime.getRuntime().halt(HALT_UNCAUGHT);
        }
    }
}
