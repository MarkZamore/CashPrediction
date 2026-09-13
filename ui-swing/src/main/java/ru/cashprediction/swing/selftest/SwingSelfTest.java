package ru.cashprediction.swing.selftest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.swing.ButtonModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import ru.cashprediction.core.document.PeriodChoice;
import ru.cashprediction.core.document.ViewMode;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.session.StatefulWindow;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.swing.MainFrame;
import ru.cashprediction.swing.action.OpenRequest;
import ru.cashprediction.swing.dialog.SwingAlert;
import ru.cashprediction.swing.dialog.SwingDialog;
import ru.cashprediction.swing.dialog.SwingHostedWindow;
import ru.cashprediction.swing.menu.ViewModels;
import ru.cashprediction.swing.popup.QuickEditPopup;

/**
 * Самотест Swing-клиента — <b>средство разработчика и автоматической проверки</b>, а не функция для пользователя.
 *
 * <p>Проверяющий сценарий не отправляет нажатия клавиш (они могли бы попасть в чужие окна), а управляет
 * приложением изнутри: команды открывают окна тем же кодом, что пункты меню, заполняют настоящие поля форм (их
 * слушатели срабатывают, рекордер получает {@code touch()}), нажимают кнопки и снимают состояние в JSON.</p>
 *
 * <p><b>Включение.</b> Только системным свойством {@code -Dcashprediction.selftest=<файл сценария UTF-8>}; журнал —
 * {@code -Dcashprediction.selftest.log=<файл>}, в него дописываются строки {@code SELFTEST <n> OK <команда>},
 * {@code SELFTEST <n> FAIL <команда>: <причина>} и в конце {@code SELFTEST DONE}. Свойство
 * {@code -Dcashprediction.selftest.recovery=registry|xml|none|already-ok} отвечает на диалог восстановления или на
 * вопрос о втором экземпляре (окно всё равно показывается, ответ приходит через секунду).</p>
 *
 * <p><b>Выполнение.</b> Запускается после показа главного окна (и после восстановления, если оно было). Команды
 * выполняются по одной в потоке EDT с паузой около 300 мс; ожидание никогда не блокирует EDT — только таймеры
 * {@code javax.swing.Timer}, которые срабатывают и во вложенном цикле модальных окон. Неизвестная команда или ошибка
 * — строка FAIL, сценарий продолжается.</p>
 *
 * <p><b>Команды</b> (по одной в строке; {@code #} — комментарий; пустые строки пропускаются):</p>
 * <pre>
 *   wait &lt;мс&gt;
 *   sample                                   как «Файл → Открыть пример»
 *   open &lt;WindowType&gt; [ключ=значение ...]     окно как по команде пользователя, с этим контекстом
 *   fill &lt;wN|last&gt; &lt;поле&gt;=&lt;значение&gt; ...     настоящие поля формы; значения в канонической форме, "с пробелами";
 *                                            last — окно последней команды open или quickedit (до первой
 *                                            такой команды — последнее зарегистрированное, например восстановленное)
 *   ok &lt;wN&gt; / cancel &lt;wN&gt;                    кнопка по умолчанию / отмена
 *   view TABLE|CHART ; period M3|M6|M12|M24|ALL ; filter &lt;ключ&gt;=true|false
 *   select &lt;rowId&gt;                           выделить строку таблицы (r1@2026-10-05)
 *   quickedit &lt;rowId&gt; &lt;сумма&gt;                открыть быструю правку и ввести сумму, не подтверждая
 *   save                                     Ctrl+S
 *   snapshot                                 recorder.saveNow()
 *   dump &lt;путь&gt;                              JSON состояния ({@link SelfTestDump})
 *   menus &lt;путь&gt;                             дерево меню, панели инструментов и контекстных меню ({@link SelfTestMenus})
 *   signal &lt;путь&gt;                            создать файл и ждать, пока его удалят (до 60 с)
 *   crash                                    Runtime.halt(3) без сохранения
 *   throw                                    необработанное исключение в EDT
 *   exit                                     корректный выход без вопросов
 * </pre>
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
public final class SwingSelfTest {

    /** Пауза между командами, мс. */
    private static final int PAUSE_MS = 300;
    /** Сколько ждать показа окна, мс. */
    private static final int OPEN_TIMEOUT_MS = 10_000;
    /** Сколько ждать удаления файла-сигнала, мс. */
    private static final int SIGNAL_TIMEOUT_MS = 60_000;

    private final MainFrame app;
    private final SelfTestConfig config;
    /** Псевдоним окна в командах {@code fill/ok/cancel}: последнее окно, открытое командой {@code open}. */
    private static final String LAST = "last";

    private final List<String> commands = new ArrayList<>();
    /** Идентификатор окна, открытого последней командой {@code open} или {@code quickedit}. */
    private String lastWindowId;
    private int index;
    private int number;

    /**
     * Создаёт самотест.
     *
     * @param app    главное окно
     * @param config настройки самотеста
     */
    public SwingSelfTest(MainFrame app, SelfTestConfig config) {
        this.app = Objects.requireNonNull(app, "app");
        this.config = Objects.requireNonNull(config, "config");
    }

    /** Читает сценарий и запускает первую команду через паузу. */
    public void start() {
        List<String> lines;
        try {
            lines = Files.readAllLines(config.script(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            config.log("SELFTEST 0 FAIL script " + config.script() + ": " + e.getMessage());
            config.log("SELFTEST DONE");
            return;
        }
        for (String raw : lines) {
            String line = raw.startsWith("﻿") ? raw.substring(1) : raw;
            line = line.strip();
            if (!line.isEmpty() && !line.startsWith("#")) {
                commands.add(line);
            }
        }
        schedule(PAUSE_MS, this::next);
    }

    private void next() {
        if (index >= commands.size()) {
            config.log("SELFTEST DONE");
            return;
        }
        String line = commands.get(index++);
        Step step = new Step(++number, line);
        try {
            execute(line, step);
        } catch (RuntimeException e) {
            step.fail(Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName()));
        } catch (IOException e) {
            step.fail("ошибка ввода-вывода: " + e.getMessage());
        }
    }

    /** Завершение одной команды: ровно одна строка журнала и переход к следующей команде. */
    private final class Step {
        private final int n;
        private final String line;
        private boolean finished;
        private boolean continueAfter = true;

        Step(int n, String line) {
            this.n = n;
            this.line = line;
        }

        void ok() {
            finish("SELFTEST " + n + " OK " + line);
        }

        void fail(String reason) {
            finish("SELFTEST " + n + " FAIL " + line + ": " + reason);
        }

        /** Команда завершает процесс: после строки журнала продолжать нечего. */
        void last() {
            continueAfter = false;
        }

        private void finish(String logLine) {
            if (finished) {
                return;
            }
            finished = true;
            config.log(logLine);
            if (continueAfter) {
                schedule(PAUSE_MS, SwingSelfTest.this::next);
            }
        }

        boolean isFinished() {
            return finished;
        }
    }

    // ------------------------------------------------------------------ команды

    private void execute(String line, Step step) throws IOException {
        List<String> t = SelfTestTokenizer.tokens(line);
        String command = t.getFirst().toLowerCase(Locale.ROOT);
        switch (command) {
            case "wait" -> schedule(Integer.parseInt(arg(t, 1)), step::ok);
            case "sample" -> {
                app.actions().openSample();
                step.ok();
            }
            case "open" -> open(t, step);
            case "fill" -> fill(t, step);
            case "ok" -> pressOk(arg(t, 1), step);
            case "cancel" -> pressCancel(arg(t, 1), step);
            case "view" -> {
                ViewMode mode = ViewMode.valueOf(arg(t, 1).toUpperCase(Locale.ROOT));
                click(app.models().mode(mode), true);
                check(app.document().viewState().mode() == mode, "вид не переключился", step);
            }
            case "period" -> {
                PeriodChoice period = PeriodChoice.valueOf(arg(t, 1).toUpperCase(Locale.ROOT));
                click(app.models().period(period), true);
                check(app.document().viewState().period() == period, "период не переключился", step);
            }
            case "filter" -> filter(t, step);
            case "select" -> check(app.selectRowId(arg(t, 1)), "строки «" + arg(t, 1) + "» нет в таблице", step);
            case "quickedit" -> quickEdit(arg(t, 1), arg(t, 2), step);
            case "save" -> {
                app.actions().save();
                step.ok();
            }
            case "snapshot" -> {
                app.recorder().saveNow();
                step.ok();
            }
            case "dump" -> {
                Path path = scriptPath(arg(t, 1));
                createParent(path);
                Files.writeString(path, SelfTestDump.json(app), StandardCharsets.UTF_8);
                step.ok();
            }
            case "menus" -> {
                Path path = scriptPath(arg(t, 1));
                createParent(path);
                Files.writeString(path, SelfTestMenus.dump(app), StandardCharsets.UTF_8);
                step.ok();
            }
            case "signal" -> signal(scriptPath(arg(t, 1)), step);
            case "crash" -> {
                step.last();
                step.ok();
                // Как настоящий сбой: ни снимка, ни shutdown hook.
                Runtime.getRuntime().halt(3);
            }
            case "throw" -> {
                step.last();
                step.ok();
                // Из отдельного события: исключение выходит из цикла EDT и попадает в обработчик по умолчанию.
                SwingUtilities.invokeLater(() -> {
                    throw new IllegalStateException("Самотест: необработанное исключение в потоке интерфейса");
                });
            }
            case "exit" -> {
                step.last();
                step.ok();
                config.log("SELFTEST DONE");
                app.exitWithoutAsking();
            }
            default -> step.fail("неизвестная команда");
        }
    }

    private void open(List<String> t, Step step) {
        WindowType type = WindowType.fromName(arg(t, 1))
                .orElseThrow(() -> new IllegalArgumentException("неизвестный тип окна «" + t.get(1) + "»"));
        Map<String, String> context = SelfTestTokenizer.pairs(t, 2);
        OpenRequest request = OpenRequest.scripted(app.actions().host(), window -> {
            // Идентификатор последнего открытого окна доступен сценарию как «last».
            lastWindowId = window.windowId();
            step.ok();
        }, step::fail);
        app.windowFactory().openScripted(type, context, request);
        timeout(step, OPEN_TIMEOUT_MS, "окно не открылось за " + OPEN_TIMEOUT_MS / 1000 + " с");
    }

    private void fill(List<String> t, Step step) {
        String id = arg(t, 1);
        Map<String, String> fields = SelfTestTokenizer.pairs(t, 2);
        Object window = findWindow(id);
        List<String> unknown;
        if (window instanceof SwingDialog<?> dialog) {
            unknown = dialog.fillFields(fields);
        } else if (window instanceof QuickEditPopup popup) {
            unknown = popup.fill(fields);
        } else if (window instanceof SwingAlert) {
            step.fail("у окна " + id + " нет полей");
            return;
        } else {
            step.fail("нет открытого окна " + id);
            return;
        }
        check(unknown.isEmpty(), "неизвестные поля: " + String.join(", ", unknown), step);
    }

    private void pressOk(String id, Step step) {
        Object window = findWindow(id);
        if (window instanceof SwingDialog<?> dialog) {
            String problem = dialog.pressDefaultButton();
            check(problem == null, problem, step);
        } else if (window instanceof SwingAlert alert) {
            check(alert.pressDefault(), "кнопку подтверждения нажать нельзя", step);
        } else if (window instanceof QuickEditPopup popup) {
            String problem = popup.pressOk();
            check(problem == null, problem, step);
        } else {
            step.fail("нет открытого окна " + id);
        }
    }

    private void pressCancel(String id, Step step) {
        Object window = findWindow(id);
        if (window instanceof SwingDialog<?> dialog) {
            dialog.cancel();
            step.ok();
        } else if (window instanceof SwingAlert alert) {
            check(alert.pressCancel(), "окно уже закрыто", step);
        } else if (window instanceof QuickEditPopup popup) {
            popup.cancel();
            step.ok();
        } else {
            step.fail("нет открытого окна " + id);
        }
    }

    private void filter(List<String> t, Step step) {
        Map<String, String> pairs = SelfTestTokenizer.pairs(t, 1);
        if (pairs.isEmpty()) {
            throw new IllegalArgumentException("ожидалось ключ=true|false");
        }
        for (Map.Entry<String, String> pair : pairs.entrySet()) {
            if ("filterText".equals(pair.getKey())) {
                // Текст фильтра вводится в настоящее поле панели инструментов.
                app.toolBar().filterField().setText(pair.getValue());
                continue;
            }
            String value = pair.getValue().strip().toLowerCase(Locale.ROOT);
            if (!"true".equals(value) && !"false".equals(value)) {
                throw new IllegalArgumentException("значение флажка должно быть true или false: «" + pair.getValue() + "»");
            }
            click(app.models().filter(pair.getKey()), Boolean.parseBoolean(value));
            if (ViewModels.filterValue(app.document().viewState(), pair.getKey()) != Boolean.parseBoolean(value)) {
                step.fail("флажок " + pair.getKey() + " не переключился");
                return;
            }
        }
        step.ok();
    }

    private void quickEdit(String rowId, String amount, Step step) {
        OccurrenceKey key = OccurrenceKey.parseRowId(rowId);
        OpenRequest request = OpenRequest.scripted(app.actions().host(), window -> {
            if (window instanceof QuickEditPopup popup) {
                lastWindowId = popup.windowId();
                // Сумма вводится в поле, но не подтверждается: панель остаётся открытой (для снимка и сбоя).
                popup.fill(Map.of(QuickEditPopup.FIELD_AMOUNT, amount));
                step.ok();
            } else {
                step.fail("открылось не окно быстрой правки");
            }
        }, step::fail);
        app.actions().quickEdit(key, request);
        timeout(step, OPEN_TIMEOUT_MS, "быстрая правка не открылась");
    }

    private void signal(Path path, Step step) throws IOException {
        createParent(path);
        Files.writeString(path, "SELFTEST signal " + step.n, StandardCharsets.UTF_8);
        long deadline = System.currentTimeMillis() + SIGNAL_TIMEOUT_MS;
        Timer poll = new Timer(200, null);
        poll.addActionListener(e -> {
            if (!Files.exists(path)) {
                poll.stop();
                step.ok();
            } else if (System.currentTimeMillis() > deadline) {
                poll.stop();
                step.fail("файл-сигнал не удалён за " + SIGNAL_TIMEOUT_MS / 1000 + " с");
            }
        });
        poll.start();
    }

    // ------------------------------------------------------------------ помощники

    /** Окно по идентификатору: диалоги и сообщения хоста или открытая быстрая правка. */
    private Object findWindow(String requestedId) {
        String id = requestedId;
        if (LAST.equals(requestedId)) {
            // После восстановления сценарий ещё ничего не открывал: «last» — последнее зарегистрированное окно.
            id = lastWindowId != null ? lastWindowId : lastRegisteredWindowId().orElse(requestedId);
        }
        return findById(id);
    }

    /** Идентификатор последнего окна, зарегистрированного в рекордере (порядок регистрации). */
    private Optional<String> lastRegisteredWindowId() {
        if (app.recorder() == null) {
            return Optional.empty();
        }
        List<StatefulWindow> windows = new ArrayList<>(app.recorder().registeredWindows());
        return windows.isEmpty() ? Optional.empty() : Optional.of(windows.getLast().windowId());
    }

    private Object findById(String id) {
        for (SwingHostedWindow window : app.actions().host().openWindows()) {
            if (id.equals(window.windowId())) {
                return window;
            }
        }
        Optional<QuickEditPopup> popup = app.quickEditPopup().filter(p -> id.equals(p.windowId()));
        if (popup.isPresent()) {
            return popup.get();
        }
        if (app.recorder() != null) {
            for (StatefulWindow window : app.recorder().registeredWindows()) {
                if (id.equals(window.windowId())) {
                    return window;
                }
            }
        }
        return null;
    }

    /** «Щелчок» по общей модели переключателя: срабатывают те же слушатели, что при щелчке пользователя. */
    private static void click(ButtonModel model, boolean selected) {
        model.setSelected(selected);
    }

    private static void check(boolean condition, String failure, Step step) {
        if (condition) {
            step.ok();
        } else {
            step.fail(failure == null ? "проверка не прошла" : failure);
        }
    }

    private static String arg(List<String> tokens, int i) {
        if (tokens.size() <= i) {
            throw new IllegalArgumentException("не хватает аргументов");
        }
        return tokens.get(i);
    }

    /**
     * Путь из аргумента команды: относительный путь отсчитывается от папки сценария, а не от рабочей папки процесса
     * (её задаёт лаунчер, и сценарий не должен от неё зависеть).
     */
    private Path scriptPath(String argument) {
        Path path = Path.of(argument);
        Path base = config.script().toAbsolutePath().getParent();
        return path.isAbsolute() || base == null ? path : base.resolve(path);
    }

    private static void createParent(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static void timeout(Step step, int millis, String reason) {
        schedule(millis, () -> {
            if (!step.isFinished()) {
                step.fail(reason);
            }
        });
    }

    private static void schedule(int millis, Runnable action) {
        Timer timer = new Timer(Math.max(1, millis), e -> action.run());
        timer.setRepeats(false);
        timer.start();
    }
}
