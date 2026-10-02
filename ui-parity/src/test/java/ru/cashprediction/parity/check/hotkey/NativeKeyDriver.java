package ru.cashprediction.parity.check.hotkey;

import java.awt.Component;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.ui.command.KeyChord;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.core.ui.selftest.*;

/** Дополняет штатный драйвер только символом раскладки и раздельным наблюдением жеста Alt. */
public final class NativeKeyDriver implements UiDriver {
    private final UiDriver delegate;
    private final Object port;
    private final boolean fx, russian;
    private final Path output;
    private boolean measured;

    private NativeKeyDriver(UiDriver delegate, Object port, boolean fx, boolean russian, Path output) {
        this.delegate = delegate; this.port = port; this.fx = fx; this.russian = russian; this.output = output;
    }

    /** Создаёт настоящие порт и драйвер; сценарий, ожидания и дампы исполняет существующий SelfTestRunner. */
    public static void main(String[] args) {
        try {
            boolean fx = System.getProperty("parity.hotkey.client").equals("fx");
            var options = LaunchOptions.parse(args);
            var script = SelfTestScript.load(options.selftest());
            // FX-драйвер берёт имя сценария из окружения; передаём разобранное имя до создания клиента.
            // Содержимое и происхождение возвращаемых дампов после этого не переписываются.
            var canonicalArgs = new java.util.ArrayList<>(java.util.Arrays.asList(args));
            canonicalArgs.set(canonicalArgs.indexOf("--selftest") + 1, script.name());
            var environment = AppEnvironment.from(LaunchOptions.parse(canonicalArgs.toArray(String[]::new)));
            if (fx) {
                var ready = new java.util.concurrent.CountDownLatch(1);
                Class.forName("javafx.application.Platform").getMethod("startup", Runnable.class).invoke(null, (Runnable) ready::countDown);
                if (!ready.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("FX startup timeout");
            }
            Object[] real = ui(fx, () -> {
                String prefix = fx ? "ru.cashprediction.fx.ui.Fx" : "ru.cashprediction.swing.ui.Swing";
                Class<?> type = Class.forName(prefix + "UiPort");
                Object port;
                if (fx) {
                    // JavaFX: Stage → Swing: JFrame → Web: document
                    Class<?> stage = Class.forName("javafx.stage.Stage");
                    port = type.getConstructor(stage, Supplier.class).newInstance(stage.getConstructor().newInstance(),
                            (Supplier<java.time.LocalDate>) environment.clock()::today);
                    var flag = type.getDeclaredField("selftest"); flag.setAccessible(true); flag.set(port, true);
                } else {
                    Class.forName(prefix + "Look").getMethod("install").invoke(null);
                    port = type.getConstructor(AppEnvironment.class).newInstance(environment);
                }
                var controller = new AppController((UiPort) port, environment);
                type.getMethod("bind", UiIntents.class).invoke(port, controller); controller.start();
                Class<?> driver = Class.forName(prefix + "UiDriver");
                UiDriver delegate = fx ? (UiDriver) driver.getConstructor(type, AppController.class, AppEnvironment.class).newInstance(port, controller, environment)
                        : (UiDriver) driver.getConstructor(type, AppController.class, String.class).newInstance(port, controller, "hotkey");
                return new Object[]{port, delegate};
            });
            new SelfTestRunner(new NativeKeyDriver((UiDriver) real[1], real[0], fx,
                    Boolean.getBoolean("parity.hotkey.russian"), options.selftestOut()), options.selftestOut())
                    .run(script);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(2); }
    }

    /** Возвращает идентичность исходного настоящего клиента. */
    @Override public ClientKind client() { return delegate.client(); }
    /** Меняет только измеряемую клавишу; подготовку выполняет существующий драйвер. */
    @Override public void execute(SelfTestCommand command) throws Exception {
        if (!measured || !(command instanceof SelfTestCommand.Key key)) { delegate.execute(command); return; }
        emit(key.chord(), true);
        if (key.chord().key().equals("ALT")) save("alt-pressed");
        emit(key.chord(), false);
        if (key.chord().key().equals("ALT")) {
            save("alt-released"); emit(key.chord(), false); save("alt-repeat-release");
        }
    }
    /** Сохраняет штатные ожидания очереди клиента. */
    @Override public void awaitIdle(Duration timeout) throws Exception { delegate.awaitIdle(timeout); }
    /** Читает только реальный дамп; метаданные и счётчики не меняются. */
    @Override public UiDump dump(String step) { UiDump dump = delegate.dump(step); if (step.equals("before")) measured = true; return dump; }
    /** Делегирует настоящий снимок исходному драйверу. */
    @Override public byte[] screenshot(String step) throws java.io.IOException { return delegate.screenshot(step); }

    /** Записывает промежуточное наблюдение реальных виджетов и счётчиков после обработки очереди. */
    private void save(String step) throws Exception {
        delegate.awaitIdle(Duration.ofSeconds(5));
        Files.writeString(output.resolve("hotkey").resolve(step + ".json"), UiJson.write(delegate.dump(step)));
    }
    /** Отправляет событие в настоящий фильтр сцены или существующий KeyEventDispatcher. */
    private void emit(KeyChord chord, boolean pressed) throws Exception {
        ui(fx, () -> {
            String character = RussianKeyInput.character(chord, russian);
            if (fx) {
                Object stage = field(port, "stage");
                Object scene = stage.getClass().getMethod("getScene").invoke(stage);
                Object target = scene.getClass().getMethod("getFocusOwner").invoke(scene);
                if (target == null) target = scene.getClass().getMethod("getRoot").invoke(scene);
                Class<?> event = Class.forName("javafx.scene.input.KeyEvent"), code = Class.forName("javafx.scene.input.KeyCode");
                Object value = event.getConstructor(Class.forName("javafx.event.EventType"), String.class, String.class, code,
                        boolean.class, boolean.class, boolean.class, boolean.class).newInstance(
                        event.getField(pressed ? "KEY_PRESSED" : "KEY_RELEASED").get(null), character, character,
                        code.getMethod("valueOf", String.class).invoke(null, chord.key()), chord.shift(), chord.ctrl(),
                        chord.key().equals("ALT") ? pressed : chord.alt(), false);
                Class.forName("javafx.scene.Node").getMethod("fireEvent", Class.forName("javafx.event.Event")).invoke(target, value);
            } else {
                Component target = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                // Штатный SwingUiDriver также использует живую таблицу, пока ОС ещё не передала фокус окну.
                if (target == null) target = (Component) field(field(field(port, "frame"), "table"), "table");
                int code = (int) Class.forName("ru.cashprediction.swing.ui.SwingKeyBridge").getMethod("keyCode", String.class).invoke(null, chord.key());
                int modifiers = (chord.ctrl() ? KeyEvent.CTRL_DOWN_MASK : 0) | (chord.shift() ? KeyEvent.SHIFT_DOWN_MASK : 0)
                        | ((chord.key().equals("ALT") ? pressed : chord.alt()) ? KeyEvent.ALT_DOWN_MASK : 0);
                var event = new KeyEvent(target, pressed ? KeyEvent.KEY_PRESSED : KeyEvent.KEY_RELEASED, System.currentTimeMillis(),
                        modifiers, code, character.isEmpty() ? KeyEvent.CHAR_UNDEFINED : character.charAt(0));
                if (!((KeyEventDispatcher) field(port, "keys")).dispatchKeyEvent(event)) SwingUtilities.processKeyBindings(event);
            }
            return null;
        });
    }
    /** Читает техническую ссылку клиента, не меняя состояние модели. */
    private static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    /** Выполняет действие в штатном UI-потоке с ограничением времени ожидания. */
    private static <T> T ui(boolean fx, java.util.concurrent.Callable<T> action) throws Exception {
        var task = new FutureTask<T>(action);
        if (fx) Class.forName("javafx.application.Platform").getMethod("runLater", Runnable.class).invoke(null, task);
        else SwingUtilities.invokeLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }
}
