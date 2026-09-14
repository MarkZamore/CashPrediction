package ru.cashprediction.core.ui.selftest;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Выполнение сценария самотеста драйвером клиента (архитектура §6.2).
 *
 * <p><b>Поведение (этап S2):</b> команды по одной; после каждой — {@code driver.awaitIdle}; результат строки пишется
 * в журнал {@code <out>/selftest.log} строками {@code SELFTEST <n> OK <команда>} или {@code SELFTEST <n> FAIL
 * <команда>: <причина>}, в конце {@code SELFTEST DONE}; ошибка команды не останавливает сценарий. {@code dump <шаг>}
 * пишет {@code <out>/<сценарий>/<шаг>.json} (нормализованный {@code DumpNormalizer}), {@code shot <шаг>} —
 * {@code <out>/<сценарий>/<шаг>.png}. Ожидания не блокируют поток интерфейса клиента: драйвер выполняет команды в
 * этом потоке, раннер ждёт в своём.</p>
 *
 * <p>Не потокобезопасен: один прогон на экземпляр.</p>
 */
public final class SelfTestRunner {

    /**
     * Результат строки сценария.
     *
     * @param number  номер строки
     * @param text    текст команды
     * @param ok      успешно ли
     * @param message причина неудачи или пустая строка
     */
    public record StepResult(int number, String text, boolean ok, String message) {
        /** Заменяет {@code null}. */
        public StepResult {
            text = Objects.requireNonNullElse(text, "");
            message = Objects.requireNonNullElse(message, "");
        }
    }

    /**
     * Отчёт прогона.
     *
     * @param scenario имя сценария
     * @param steps    результаты строк по порядку
     */
    public record Report(String scenario, List<StepResult> steps) {
        /** Копирует список. */
        public Report {
            steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        }

        /** @return все ли строки успешны */
        public boolean ok() {
            return steps.stream().allMatch(StepResult::ok);
        }
    }

    private final UiDriver driver;
    private final Path outDir;

    /**
     * Создаёт раннер.
     *
     * @param driver драйвер клиента
     * @param outDir папка результатов ({@code --selftest-out})
     */
    public SelfTestRunner(UiDriver driver, Path outDir) {
        this.driver = Objects.requireNonNull(driver, "driver");
        this.outDir = Objects.requireNonNull(outDir, "outDir");
    }

    /** @return драйвер клиента */
    public UiDriver driver() {
        return driver;
    }

    /** @return папка результатов */
    public Path outDir() {
        return outDir;
    }

    /**
     * Выполняет сценарий.
     *
     * @param script сценарий
     * @return отчёт
     */
    public Report run(SelfTestScript script) {
        throw new UnsupportedOperationException("S2: core-protocol-dump - SelfTestRunner.run");
    }
}
