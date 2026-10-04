package ru.cashprediction.fx.ui;

import java.awt.GraphicsEnvironment;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.lang.foreign.*;
import java.lang.invoke.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import javax.swing.SwingUtilities;
import ru.cashprediction.core.ui.text.UiText;

/**
 * Test-only OS-клавиатура для настоящего Windows FileChooser JavaFX внутри JVM клиента.
 * HWND определяется по PID текущей JVM и точному заголовку rescue. Ни selectedFile, ни callback
 * не подменяются. Неопознаваемый filename-контрол, неоднозначные HWND и чужой foreground запрещают ввод.
 * Подключение наблюдателя и проверка записанного payload относятся к отдельному E2E, не этому helper.
 */
final class NativeFxRescueKeyboard {
    private NativeFxRescueKeyboard() { }


    /** Отменяет однозначный активный OS-диалог настоящим Escape и ждёт его исчезновения. */
    static void cancel(Path home, String node, Duration timeout) throws Exception {
        checkWorker(home, node, timeout);
        try (var nativeUi = new Windows(timeout)) {
            MemorySegment dialog = nativeUi.awaitDialog();
            nativeUi.assertForeground(dialog);
            key(new Robot(), KeyEvent.VK_ESCAPE);
            nativeUi.awaitHidden(dialog);
        }
    }

    /** Вставляет путь в OS filename-поле и щёлкает OS Save; результата выбора или записи не имитирует. */
    static void save(Path selected, Path home, String node, Duration timeout) throws Exception {
        checkWorker(home, node, timeout);
        Path memory = home.toRealPath().resolve("CashMemory").toRealPath();
        Path target = selected.toAbsolutePath().normalize();
        if (!target.startsWith(memory) || !target.getParent().toRealPath().startsWith(memory)
                || !target.getFileName().toString().endsWith(".md") || Files.exists(target))
            throw new IllegalArgumentException("Expected a new isolated Markdown rescue target");
        if (target.toString().length() >= 8192) throw new IllegalArgumentException("Filename observation limit exceeded");
        try (var nativeUi = new Windows(timeout)) {
            MemorySegment dialog = nativeUi.awaitDialog();
            MemorySegment field = nativeUi.filename(dialog);
            MemorySegment save = nativeUi.saveButton(dialog);
            Robot robot = new Robot();
            nativeUi.click(robot, dialog, field);
            nativeUi.awaitFocus(dialog, field);
            nativeUi.assertFocus(dialog, field);
            chord(robot, KeyEvent.VK_CONTROL, KeyEvent.VK_A);
            nativeUi.typeUnicode(dialog, field, target.toString());
            nativeUi.until(() -> nativeUi.text(field).equals(target.toString()), "OS filename differs from typed path");
            nativeUi.assertFocus(dialog, field);
            nativeUi.click(robot, dialog, save);
            try { nativeUi.awaitHidden(dialog); }
            catch (IllegalStateException failure) {
                // Снимок только прямоугольника собственного HWND сохраняет фактическую причину отказа.
                try { nativeUi.captureWindow(dialog, home.resolve("native-after-save-timeout.png")); }
                catch (Exception captureFailure) { failure.addSuppressed(captureFailure); }
                throw failure;
            }
        }
    }

    /** Сохраняет реальные пиксели HWND native chooser; скрытая FX-сцена не используется. */
    static void capture(Path home, String node, Path output, Duration timeout) throws Exception {
        checkWorker(home, node, timeout);
        if (!output.toAbsolutePath().normalize().startsWith(home.toRealPath()))
            throw new IllegalArgumentException("Foreign evidence path");
        try (var nativeUi = new Windows(timeout)) {
            MemorySegment dialog = nativeUi.awaitDialog();
            nativeUi.assertForeground(dialog);
            nativeUi.captureWindow(dialog, output);
        }
    }

    /** Требует worker, Windows x64, интерактивный desktop и явно изолированные home/UUID registry. */
    private static void checkWorker(Path home, String node, Duration timeout) throws Exception {
        if (!System.getProperty("os.name").startsWith("Windows") || ValueLayout.ADDRESS.byteSize() != 8)
            throw new IllegalStateException("Windows x64 native chooser required");
        if (GraphicsEnvironment.isHeadless() || SwingUtilities.isEventDispatchThread()
                || Thread.currentThread().getName().equals("JavaFX Application Thread"))
            throw new IllegalStateException("Native chooser Robot requires a desktop worker");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(30)) > 0)
            throw new IllegalArgumentException("Timeout must be within 30 seconds");
        String prefix = "ru/cashprediction/selftest/";
        if (!node.startsWith(prefix) || !UUID.fromString(node.substring(prefix.length())).toString().equals(node.substring(prefix.length())))
            throw new IllegalStateException("Isolated UUID registry required");
        if (!Files.isDirectory(home) || !Files.isDirectory(home.resolve("CashMemory"))
                || !home.resolve("CashMemory").toRealPath().startsWith(home.toRealPath()))
            throw new IllegalStateException("Existing isolated home/CashMemory required");
    }


    /** Нажимает и всегда отпускает реальную клавишу. */
    private static void key(Robot robot, int key) { robot.keyPress(key); try { } finally { robot.keyRelease(key); } }

    /** Отпускает модификатор даже при ошибке OS-ввода. */
    private static void chord(Robot robot, int modifier, int key) {
        robot.keyPress(modifier); try { key(robot, key); } finally { robot.keyRelease(modifier); }
    }

    /** Короткоживущий FFM-доступ к User32; один общий deadline ограничивает все ожидания операции. */
    private static final class Windows implements AutoCloseable {
        private final Arena arena = Arena.ofConfined();
        private final Linker linker = Linker.nativeLinker();
        private final SymbolLookup symbols = SymbolLookup.libraryLookup("user32.dll", arena);
        private final Map<String, MethodHandle> calls = new HashMap<>();
        private final long deadline;

        /** Объявляет только наблюдающие Win32-функции; текст контролов читается bounded SendMessageTimeoutW. */
        Windows(Duration timeout) {
            deadline = System.nanoTime() + timeout.toNanos();
            bind("EnumWindows", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
            bind("EnumChildWindows", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
            bind("GetWindowThreadProcessId", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
            bind("IsWindowVisible", ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
            bind("IsWindowEnabled", ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
            bind("GetForegroundWindow", ValueLayout.ADDRESS);
            bind("GetCursorPos", ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
            bind("SetCursorPos", ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
            bind("GetClassNameW", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
            bind("GetDlgCtrlID", ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
            bind("GetParent", ValueLayout.ADDRESS, ValueLayout.ADDRESS);
            bind("GetWindowRect", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
            bind("WindowFromPoint", ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);
            bind("GetGUIThreadInfo", ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
            bind("SendInput", ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
            bind("SendMessageTimeoutW", ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
        }

        /** Создаёт downcall с ABI Win64 (BOOL/DWORD = 32, HWND/WPARAM/LRESULT = 64). */
        private void bind(String name, MemoryLayout result, MemoryLayout... arguments) {
            calls.put(name, linker.downcallHandle(symbols.find(name).orElseThrow(), FunctionDescriptor.of(result, arguments)));
        }

        /** Ошибки native observation не заменяются предположением о состоянии окна. */
        private Object call(String name, Object... arguments) {
            try { return calls.get(name).invokeWithArguments(arguments); }
            catch (Throwable error) { throw new IllegalStateException("Win32 observation failed: " + name, error); }
        }

        /** Вводит UTF-16 через настоящие OS keydown/keyup, не читая и не меняя clipboard. */
        void typeUnicode(MemorySegment dialog, MemorySegment field, String value) {
            MemorySegment inputs = arena.allocate(80, 8); // Два Win64 INPUT по 40 байт.
            for (char character : value.toCharArray()) {
                if (System.nanoTime() >= deadline) throw new IllegalStateException("Native typing deadline exceeded");
                assertFocus(dialog, field);
                inputs.fill((byte) 0);
                for (int index = 0; index < 2; index++) {
                    long base = index * 40L;
                    inputs.set(ValueLayout.JAVA_INT, base, 1); // INPUT_KEYBOARD.
                    inputs.set(ValueLayout.JAVA_CHAR, base + 10, character);
                    inputs.set(ValueLayout.JAVA_INT, base + 12, index == 0 ? 4 : 6); // UNICODE и KEYUP.
                }
                if (integer("SendInput", 2, inputs, 40) != 2)
                    throw new IllegalStateException("OS Unicode input was not delivered");
            }
        }

        /** Захватывает HWND на первичном экране, переводя физические пиксели в систему координат Robot. */
        void captureWindow(MemorySegment dialog, Path output) throws Exception {
            MemorySegment rect = arena.allocate(16, 4);
            if (integer("GetWindowRect", dialog, rect) == 0) throw new IllegalStateException("Missing native dialog RECT");
            int left = rect.get(ValueLayout.JAVA_INT, 0), top = rect.get(ValueLayout.JAVA_INT, 4);
            int right = rect.get(ValueLayout.JAVA_INT, 8), bottom = rect.get(ValueLayout.JAVA_INT, 12);
            var configuration = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration();
            var bounds = configuration.getBounds();
            var transform = configuration.getDefaultTransform();
            double sx = transform.getScaleX(), sy = transform.getScaleY();
            if (sx <= 0 || sy <= 0 || bounds.x != 0 || bounds.y != 0 || left < 0 || top < 0
                    || right > bounds.width * sx || bottom > bounds.height * sy || right <= left || bottom <= top)
                throw new IllegalStateException("Native screenshot requires an unambiguous primary-screen rectangle");
            int x = (int) Math.floor(left / sx), y = (int) Math.floor(top / sy);
            var capture = new java.awt.Rectangle(x, y, (int) Math.ceil(right / sx) - x, (int) Math.ceil(bottom / sy) - y);
            if (!javax.imageio.ImageIO.write(new Robot().createScreenCapture(capture), "png", output.toFile()))
                throw new IllegalStateException("PNG writer unavailable");
        }

        /** Возвращает Win32 BOOL/DWORD. */
        private int integer(String name, Object... arguments) { return ((Number) call(name, arguments)).intValue(); }

        /** Возвращает Win32 HWND. */
        private MemorySegment hwnd(String name, Object... arguments) { return (MemorySegment) call(name, arguments); }

        /** Находит только один видимый #32770 с PID текущей JVM и точным локализованным заголовком. */
        MemorySegment awaitDialog() throws Exception {
            List<MemorySegment> found = new ArrayList<>();
            until(() -> {
                found.clear();
                for (MemorySegment window : enumerate(MemorySegment.NULL))
                    if (owned(window) && visible(window) && className(window).equals("#32770")
                            && text(window).equals(UiText.get("s2.startup.saveTitle"))) found.add(window);
                if (found.size() > 1) throw new IllegalStateException("Ambiguous owned rescue HWND");
                return found.size() == 1;
            }, "Owned native rescue dialog not visible");
            MemorySegment dialog = found.getFirst();
            until(() -> same(hwnd("GetForegroundWindow"), dialog), "Native chooser not foreground");
            assertForeground(dialog); return dialog;
        }

        /** Перечисляет HWND без пропуска ошибки upcall; исключения не выходят через native callback. */
        private List<MemorySegment> enumerate(MemorySegment parent) {
            var collector = new Collector();
            try (Arena callbackArena = Arena.ofConfined()) {
                MethodHandle callback = MethodHandles.lookup().findVirtual(Collector.class, "visit",
                        MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class)).bindTo(collector);
                MemorySegment stub = linker.upcallStub(callback,
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS), callbackArena);
                if (parent.address() == 0) {
                    if (integer("EnumWindows", stub, MemorySegment.NULL) == 0 && collector.error == null)
                        throw new IllegalStateException("EnumWindows failed");
                }
                else integer("EnumChildWindows", parent, stub, MemorySegment.NULL);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
            if (collector.error != null) throw new IllegalStateException("HWND enumeration failed", collector.error);
            return collector.windows;
        }

        /** Callback не бросает: ошибка переносится в Java после возврата EnumWindows. */
        private static final class Collector {
            final List<MemorySegment> windows = new ArrayList<>();
            Throwable error;
            /** Сохраняет значение HWND, не читая память native адреса. */
            public int visit(MemorySegment window, MemorySegment ignored) {
                try { windows.add(MemorySegment.ofAddress(window.address())); return 1; }
                catch (Throwable failure) { error = failure; return 0; }
            }
        }

        /** PID каждого наблюдаемого окна обязан совпадать с текущей JVM. */
        private boolean owned(MemorySegment window) {
            MemorySegment pid = arena.allocate(ValueLayout.JAVA_INT);
            integer("GetWindowThreadProcessId", window, pid);
            return Integer.toUnsignedLong(pid.get(ValueLayout.JAVA_INT, 0)) == ProcessHandle.current().pid();
        }

        /** Проверяет фактическую видимость HWND. */
        private boolean visible(MemorySegment window) { return integer("IsWindowVisible", window) != 0; }

        /** Читает имя класса HWND через User32. */
        private String className(MemorySegment window) {
            MemorySegment buffer = arena.allocate(512 * 2, 2);
            int length = integer("GetClassNameW", window, buffer, 512);
            if (length <= 0 || length >= 511) throw new IllegalStateException("Cannot identify native HWND class");
            return utf16(buffer, length);
        }

        /** WM_GETTEXT только читает реальный контрол; зависший UI не блокирует helper без срока. */
        String text(MemorySegment window) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IllegalStateException("Native operation deadline exceeded");
            MemorySegment buffer = arena.allocate(8192 * 2, 2), result = arena.allocate(ValueLayout.JAVA_LONG);
            long success = ((Number) call("SendMessageTimeoutW", window, 0x000D, 8192L, buffer, 2,
                    (int) Math.max(1, Math.min(1000, Duration.ofNanos(remaining).toMillis())), result)).longValue();
            if (success == 0) throw new IllegalStateException("Native text read timed out or failed");
            long length = result.get(ValueLayout.JAVA_LONG, 0);
            if (length < 0 || length >= 8191) throw new IllegalStateException("Native text truncated");
            return utf16(buffer, (int) length);
        }

        /** Декодирует UTF-16 Windows, не используя системную ANSI-кодировку. */
        private static String utf16(MemorySegment buffer, int length) {
            char[] chars = new char[length];
            for (int i = 0; i < length; i++) chars[i] = buffer.get(ValueLayout.JAVA_CHAR, i * 2L);
            return new String(chars);
        }

        /** Опознаёт классическое поле или измеренную строгую цепочку современного Windows chooser. */
        MemorySegment filename(MemorySegment dialog) {
            List<MemorySegment> fields = new ArrayList<>();
            List<String> observed = new ArrayList<>();
            for (MemorySegment child : enumerate(dialog)) {
                if (!owned(child) || !visible(child) || integer("IsWindowEnabled", child) == 0
                        || !className(child).equals("Edit")) continue;
                boolean identified = integer("GetDlgCtrlID", child) == 0x0480;
                MemorySegment ancestor = hwnd("GetParent", child);
                StringBuilder identity = new StringBuilder("Edit#").append(integer("GetDlgCtrlID", child));
                for (int depth = 0; !same(ancestor, dialog) && ancestor.address() != 0 && depth < 32; depth++) {
                    identity.append("/").append(className(ancestor)).append("#").append(integer("GetDlgCtrlID", ancestor));
                    if (integer("GetDlgCtrlID", ancestor) == 0x047c
                            && Set.of("ComboBox", "ComboBoxEx32").contains(className(ancestor))) identified = true;
                    ancestor = hwnd("GetParent", ancestor);
                }
                // Измерено в настоящем FileChooser: современный filename Edit имеет ID 1001
                // и эту цепочку родителей. Произвольный видимый Edit не принимается.
                if (identity.toString().equals("Edit#1001/ComboBox#0/FloatNotifySink#0/DirectUIHWND#0/DUIViewWndClassName#0"))
                    identified = true;
                observed.add(identity.toString());
                if (identified) fields.add(child);
            }
            if (fields.size() != 1) throw new IllegalStateException("Cannot identify one native filename Edit: " + fields.size()
                    + "; actual control identities=" + observed);
            return fields.getFirst();
        }

        /** Кнопка Save определяется по IDOK, не по предположению о языке Windows. */
        MemorySegment saveButton(MemorySegment dialog) {
            var matches = enumerate(dialog).stream().filter(w -> owned(w) && visible(w)
                    && integer("IsWindowEnabled", w) != 0 && integer("GetDlgCtrlID", w) == 1
                    && className(w).equals("Button")).toList();
            if (matches.size() != 1) throw new IllegalStateException("Ambiguous native Save/IDOK button");
            return matches.getFirst();
        }

        /** Повторно проверяет PID, заголовок и точный foreground непосредственно перед Robot-действием. */
        void assertForeground(MemorySegment dialog) {
            if (!owned(dialog) || !visible(dialog) || !same(hwnd("GetForegroundWindow"), dialog)
                    || !text(dialog).equals(UiText.get("s2.startup.saveTitle")))
                throw new IllegalStateException("Native chooser lost ownership/foreground");
        }

        /** Проверяет OS focus через GUITHREADINFO, а не JavaFX focus model. */
        void assertFocus(MemorySegment dialog, MemorySegment field) {
            assertForeground(dialog);
            MemorySegment pid = arena.allocate(ValueLayout.JAVA_INT);
            int thread = integer("GetWindowThreadProcessId", dialog, pid);
            MemorySegment info = arena.allocate(72, 8); info.set(ValueLayout.JAVA_INT, 0, 72);
            if (integer("GetGUIThreadInfo", thread, info) == 0 || !same(info.get(ValueLayout.ADDRESS, 16), field))
                throw new IllegalStateException("Native filename lost OS focus");
        }

        /** Ждёт наблюдаемый OS focus, не меняя его Win32 SetFocus или тестовым callback. */
        void awaitFocus(MemorySegment dialog, MemorySegment field) throws Exception {
            until(() -> {
                assertForeground(dialog);
                MemorySegment pid = arena.allocate(ValueLayout.JAVA_INT), info = arena.allocate(72, 8);
                info.set(ValueLayout.JAVA_INT, 0, 72);
                int thread = integer("GetWindowThreadProcessId", dialog, pid);
                if (integer("GetGUIThreadInfo", thread, info) == 0) throw new IllegalStateException("Cannot observe GUI focus");
                return same(info.get(ValueLayout.ADDRESS, 16), field);
            }, "Filename did not receive OS focus");
        }

        /** Щёлкает HWND по фактическому экранному RECT; сообщения BM_CLICK/WM_SETTEXT не используются. */
        void click(Robot robot, MemorySegment dialog, MemorySegment control) {
            assertForeground(dialog);
            if (!owned(control) || !visible(control) || integer("IsWindowEnabled", control) == 0)
                throw new IllegalStateException("Foreign or unavailable native control");
            MemorySegment rect = arena.allocate(16, 4);
            if (integer("GetWindowRect", control, rect) == 0) throw new IllegalStateException("Missing native control RECT");
            int x = rect.get(ValueLayout.JAVA_INT, 0), y = rect.get(ValueLayout.JAVA_INT, 4);
            int width = rect.get(ValueLayout.JAVA_INT, 8) - x, height = rect.get(ValueLayout.JAVA_INT, 12) - y;
            if (width <= 0 || height <= 0) throw new IllegalStateException("Empty native control RECT");
            int clickX = x + width / 2, clickY = y + height / 2;
            // POINT передаётся Win64 как восьмибайтовое значение; hit-test не разрешает чужую перекрывающую панель.
            long point = Integer.toUnsignedLong(clickX) | ((long) clickY << 32);
            // HWND RECT и GetCursorPos используют физические пиксели, Robot.mouseMove - логические.
            // Настоящий OS-cursor перемещается в точку RECT; кнопки по-прежнему нажимает Robot.
            if (integer("SetCursorPos", clickX, clickY) == 0) throw new IllegalStateException("Cannot position OS cursor");
            assertForeground(dialog);
            MemorySegment cursor = arena.allocate(8, 4);
            if (integer("GetCursorPos", cursor) == 0) throw new IllegalStateException("Cannot observe OS cursor");
            if (cursor.get(ValueLayout.JAVA_INT, 0) != clickX || cursor.get(ValueLayout.JAVA_INT, 4) != clickY)
                throw new IllegalStateException("Robot/native coordinate mismatch: expected " + clickX + "," + clickY
                        + " actual " + cursor.get(ValueLayout.JAVA_INT, 0) + "," + cursor.get(ValueLayout.JAVA_INT, 4));
            if (!same(hwnd("WindowFromPoint", point), control))
                throw new IllegalStateException("Native control is occluded; refusing Robot click");
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            try { } finally { robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); }
        }

        /** Ждёт закрытия именно наблюдаемого HWND; новый диалог подтверждения не принимается за успех. */
        void awaitHidden(MemorySegment dialog) throws Exception { until(() -> !visible(dialog), "Native chooser did not close"); }

        /** Один deadline применяется ко всем этапам, а не начинается заново после каждого действия. */
        void until(java.util.function.BooleanSupplier predicate, String error) throws InterruptedException {
            while (true) {
                if (System.nanoTime() >= deadline) throw new IllegalStateException(error);
                if (predicate.getAsBoolean()) return;
                Thread.sleep(Math.min(25, Math.max(1, Duration.ofNanos(deadline - System.nanoTime()).toMillis())));
            }
        }

        /** Сопоставляет значения HWND без разыменования чужой памяти. */
        private static boolean same(MemorySegment a, MemorySegment b) { return a.address() == b.address(); }

        /** Закрывает только собственный native scope, не окно пользователя. */
        @Override public void close() { arena.close(); }
    }
}
