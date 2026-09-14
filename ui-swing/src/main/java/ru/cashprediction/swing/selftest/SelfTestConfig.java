package ru.cashprediction.swing.selftest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Optional;

/**
 * Настройки режима самотеста из системных свойств JVM.
 *
 * <ul>
 *   <li>{@code cashprediction.selftest=<файл сценария UTF-8>} — включает режим;</li>
 *   <li>{@code cashprediction.selftest.log=<файл журнала>} — куда дописывать строки {@code SELFTEST …}
 *   (без свойства — в стандартный вывод);</li>
 *   <li>{@code cashprediction.selftest.recovery=registry|xml|none|already-ok} — автоответ на диалог
 *   восстановления или на вопрос о втором экземпляре.</li>
 *   <li>{@code cashprediction.selftest.recoveryDelayMs=<мс>} — через сколько отвечать на диалог восстановления
 *   (по умолчанию 1200; больше — чтобы успеть снять снимок экрана диалога; читает {@code StartupFlow});</li>
 *   <li>{@code cashprediction.selftest.errorAlertMs=<мс>} — сколько показывать сообщение о необработанном исключении
 *   перед {@code halt(2)} (по умолчанию 1500; читает {@code StartupFlow}).</li>
 * </ul>
 *
 * <p>Record неизменяем; запись журнала синхронизирована (строки из разных потоков не перемешиваются).</p>
 *
 * @param script   файл сценария
 * @param log      файл журнала или {@code null}
 * @param recovery автоответ восстановления (пустая строка — не отвечать)
 */
public record SelfTestConfig(Path script, Path log, String recovery) {

    /** Свойство с файлом сценария. */
    public static final String PROP_SCRIPT = "cashprediction.selftest";
    /** Свойство с файлом журнала. */
    public static final String PROP_LOG = "cashprediction.selftest.log";
    /** Свойство автоответа на диалог восстановления. */
    public static final String PROP_RECOVERY = "cashprediction.selftest.recovery";

    /** Подставляет пустой автоответ. */
    public SelfTestConfig {
        recovery = recovery == null ? "" : recovery.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Читает настройки из системных свойств.
     *
     * @return настройки или пусто, если самотест не включён
     */
    public static Optional<SelfTestConfig> fromSystemProperties() {
        String script = System.getProperty(PROP_SCRIPT);
        if (script == null || script.isBlank()) {
            return Optional.empty();
        }
        try {
            String log = System.getProperty(PROP_LOG);
            return Optional.of(new SelfTestConfig(Path.of(script.strip()),
                    log == null || log.isBlank() ? null : Path.of(log.strip()), System.getProperty(PROP_RECOVERY)));
        } catch (InvalidPathException e) {
            System.err.println("CashPrediction: некорректный путь самотеста: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Дописывает строку в журнал самотеста.
     *
     * @param line строка без перевода строки
     */
    public void log(String line) {
        synchronized (SelfTestConfig.class) {
            if (log == null) {
                System.out.println(line);
                return;
            }
            try {
                Path parent = log.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(log, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println("CashPrediction: журнал самотеста не записан: " + e.getMessage() + " - " + line);
            }
        }
    }
}
