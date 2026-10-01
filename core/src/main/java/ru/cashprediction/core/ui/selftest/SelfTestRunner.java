package ru.cashprediction.core.ui.selftest;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import ru.cashprediction.core.ui.dump.DumpNormalizer;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.core.ui.text.UiText;

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
        Objects.requireNonNull(script, "script");
        Path root = outDir.toAbsolutePath().normalize();
        Path scenario = child(root, script.name());
        List<StepResult> results = new ArrayList<>();
        StringBuilder log = new StringBuilder();
        try {
            Files.createDirectories(scenario);
            for (SelfTestScript.Line line : script.lines()) {
                try {
                    SelfTestCommand command = line.command();
                    if (command instanceof SelfTestCommand.Wait wait) {
                        if (wait.millis() < 0) throw new IllegalArgumentException("wait");
                        if (driver instanceof ModelUiDriver model) model.advance(Duration.ofMillis(wait.millis()));
                        else Thread.sleep(wait.millis());
                    } else if (command instanceof SelfTestCommand.Signal signal) {
                        Path path = child(root, signal.path());
                        Files.createDirectories(path.getParent());
                        Files.createFile(path);
                        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
                        while (Files.exists(path)) {
                            if (System.nanoTime() >= deadline) throw new IOException("signal timeout");
                            Thread.sleep(20);
                        }
                    } else if (command instanceof SelfTestCommand.Dump dump) {
                        driver.awaitIdle(Duration.ofSeconds(5));
                        var value = driver.dump(dump.step());
                        var environment = driver instanceof ModelUiDriver model ? model.environment()
                                : ru.cashprediction.core.app.AppEnvironment.from(ru.cashprediction.core.app.LaunchOptions.parse(List.of(), System.getProperties()));
                        value = DumpNormalizer.normalize(value, environment.cashMemory(), environment.registryNodePath(driver.client().snapshotClient()));
                        value = ModelDump.label(value, script.name(), dump.step());
                        Files.writeString(child(scenario, dump.step() + ".json"), UiJson.write(value), StandardCharsets.UTF_8);
                    } else if (command instanceof SelfTestCommand.Shot shot) {
                        driver.awaitIdle(Duration.ofSeconds(5));
                        Files.write(child(scenario, shot.step() + ".png"), driver.screenshot(shot.step()));
                    } else if (command instanceof SelfTestCommand.Menus menus) {
                        driver.awaitIdle(Duration.ofSeconds(5));
                        Files.writeString(child(scenario, menus.path()), UiJson.write(driver.dump("menus").menuBar()), StandardCharsets.UTF_8);
                    } else {
                        driver.execute(command);
                    }
                    driver.awaitIdle(Duration.ofSeconds(5));
                    results.add(new StepResult(line.number(), line.text(), true, ""));
                    log.append("SELFTEST ").append(line.number()).append(" OK ").append(line.text()).append('\n');
                } catch (Exception e) {
                    String message = Objects.toString(e.getMessage(), e.getClass().getSimpleName());
                    results.add(new StepResult(line.number(), line.text(), false, message));
                    log.append("SELFTEST ").append(line.number()).append(" FAIL ").append(line.text())
                            .append(": ").append(message.replace('\n', ' ').replace('\r', ' ')).append('\n');
                    if (e instanceof InterruptedException) { Thread.currentThread().interrupt(); break; }
                }
                Files.writeString(root.resolve("selftest.log"), log, StandardCharsets.UTF_8);
            }
            log.append("SELFTEST DONE\n");
            Files.writeString(root.resolve("selftest.log"), log, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(UiText.get("s2.selftest.output", outDir), e);
        }
        return new Report(script.name(), results);
    }

    /** Разрешает только имена внутри папки вывода, исключая перезапись чужих файлов. */
    private static Path child(Path parent, String name) {
        Path relative = Path.of(name);
        Path result = parent.resolve(relative).normalize();
        if (relative.isAbsolute() || relative.getNameCount() != 1 || name.equals(".") || name.equals("..")
                || !result.startsWith(parent) || result.equals(parent)) throw new IllegalArgumentException("output path");
        return result;
    }
}
