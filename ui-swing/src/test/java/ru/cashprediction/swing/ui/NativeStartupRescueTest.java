package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.prefs.Preferences;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.session.codec.JsonSnapshotCodec;
import ru.cashprediction.core.session.store.*;
import ru.cashprediction.core.ui.alert.AlertCatalog;
import ru.cashprediction.core.ui.text.UiText;

/**
 * Настоящий Swing rescue без режима selftest: контроллер, физические окна и штатный JFileChooser.
 * Декоратор наблюдает неизменённый native-результат и разрешает исходный callback после проверок.
 * Кнопки и filename-поле активируются исключительно Robot; callback-путь не подставляется.
 */
@EnabledIfSystemProperty(named = "parity.e2e.nativeRescue", matches = "true")
@Timeout(value = 150, unit = TimeUnit.SECONDS)
class NativeStartupRescueTest {
    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final String PAYLOAD = "unreadable-native-rescue\nТочный исходный текст <&> \"quoted\"\r\nend\n";

    /** Отмена, настоящая ошибка записи и успешное спасение не теряют исходный снимок до решения. */
    @Test void actualNativeCancelFailureAndSuccessPreserveOriginalPayload() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless(), "Interactive desktop required; enabled test cannot skip");
        assertFalse(SwingUtilities.isEventDispatchThread());
        try (Harness h = new Harness()) {
            h.start();
            h.clickAlert("crashRecovery", AlertCatalog.BUTTON_RESTORE_XML);
            SwingAlerts report = h.alert("restoreReport");
            assertTrue(edt(() -> report.details.getText()).contains(RestoreCoordinator.RECORDER_NOT_STARTED));
            h.clickAlert("restoreReport", AlertCatalog.BUTTON_OK);
            SwingAlerts preserve = h.alert("recorderNotStarted");
            assertEquals(PAYLOAD, edt(() -> preserve.details.getText()));
            h.unchanged();

            Attempt cancel = h.arm();
            h.clickAlert("recorderNotStarted", "saveSnapshotPlan");
            JFileChooser cancelledChooser = h.chooser();
            h.capture("cancel-open", edt(() -> SwingUtilities.getWindowAncestor(cancelledChooser)));
            h.focused(cancelledChooser);
            h.key(KeyEvent.VK_ESCAPE);
            assertTrue(cancel.selected.get(WAIT.toMillis(), TimeUnit.MILLISECONDS).isEmpty());
            h.unchanged();
            cancel.release();
            h.alert("recorderNotStarted");
            h.unchanged();

            Path failed = h.memory.resolve("native-write-failure.md");
            Attempt failure = h.arm();
            h.clickAlert("recorderNotStarted", "saveSnapshotPlan");
            h.nativeSave(h.chooser(), failed, "failure-open");
            assertEquals(Optional.of(failed), failure.selected.get(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            h.unchanged();
            Files.createDirectory(failed);
            Path blocker = failed.resolve("owned-blocker");
            String token = UUID.randomUUID().toString();
            Files.writeString(blocker, token, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            failure.release();
            h.clickAlert("replaceFile", "replace");
            h.alert("err.snapshotPlanSave");
            h.unchanged();
            assertEquals(token, Files.readString(blocker));
            h.clickAlert("err.snapshotPlanSave", AlertCatalog.BUTTON_OK);
            h.alert("recorderNotStarted");
            h.unchanged();
            Files.delete(blocker); // Только созданные тестом объекты, без рекурсивного удаления.
            Files.delete(failed);

            Path saved = h.memory.resolve("native-success.md");
            Attempt success = h.arm();
            h.clickAlert("recorderNotStarted", "saveSnapshotPlan");
            h.nativeSave(h.chooser(), saved, "success-open");
            assertEquals(Optional.of(saved), success.selected.get(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            h.unchanged();
            success.release();
            until(() -> h.started(), "Recorder did not start after actual rescue success");
            assertArrayEquals(PAYLOAD.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(saved));
            assertEquals(3, h.attempts.size());
            for (Attempt attempt : h.attempts) assertEquals(1, attempt.deliveries.get());
            h.capture("success-main", edt(() -> h.port.frame));
        }
    }

    /** Одноразовый наблюдатель, в котором путь приходит только из исходного callback настоящего порта. */
    private static final class Attempt {
        final CompletableFuture<Optional<Path>> selected = new CompletableFuture<>();
        final CompletableFuture<Void> permission = new CompletableFuture<>();
        final java.util.concurrent.atomic.AtomicInteger deliveries = new java.util.concurrent.atomic.AtomicInteger();

        /** Наблюдает native-результат и доставляет тот же объект после разрешения в EDT. */
        void returned(Optional<Path> value, Consumer<Optional<Path>> original) {
            if (!selected.complete(value)) throw new IllegalStateException("Duplicate native return");
            permission.thenRun(() -> SwingUtilities.invokeLater(() -> {
                if (deliveries.incrementAndGet() != 1) throw new IllegalStateException("Duplicate original callback");
                original.accept(value);
            }));
        }

        /** Разрешает исходный callback без возможности задать выбранный путь. */
        void release() {
            assertTrue(selected.isDone());
            assertTrue(permission.complete(null));
        }
    }

    /** Реальный клиент в JVM теста с изолированными файлами, реестром и сохранёнными доказательствами. */
    private static final class Harness implements AutoCloseable {
        final Path root = Files.createTempDirectory("cp-native-swing-rescue-");
        final Path evidence = evidenceDirectory(root, "swing-");
        final Path memory = Files.createDirectories(root.resolve("CashMemory"));
        final String node = "ru/cashprediction/selftest/" + UUID.randomUUID();
        final Map<String, String> realBefore = registryImage("ru/cashprediction/session");
        final AppEnvironment environment;
        final XmlSessionStore xml;
        final RegistrySessionStore registry;
        final Robot robot = new Robot();
        final List<Attempt> attempts = new ArrayList<>();
        final java.util.Set<Window> nativeWindows = new java.util.HashSet<>();
        final AtomicReference<Attempt> pending = new AtomicReference<>();
        final SessionSnapshot original;
        final byte[] originalXml;
        final Map<String, String> originalRegistry;
        SwingUiPort port;
        AppController controller;

        /** Сеет корректно закодированный снимок с неисправным Markdown, не повреждая транспорт снимка. */
        Harness() throws Exception {
            environment = AppEnvironment.from(LaunchOptions.parse("--home", root.toString(), "--registry-node", node,
                    "--today", "2026-09-13"));
            assertFalse(environment.options().isSelftest(), "Native chooser must not take selftest stub path");
            xml = environment.xmlStore("swing"); registry = environment.registryStore("swing");
            assertTrue(registry.isAvailable(), registry.unavailableReason());
            Instant savedAt = Instant.now().minusSeconds(60);
            original = SessionSnapshot.of(savedAt, "swing", MainWindowState.empty(), PlanState.dirty(PAYLOAD), List.of());
            // Маркер собственной JVM считается предыдущим оборванным сеансом, а не другим живым экземпляром.
            SessionMarker marker = SessionMarker.running(ProcessHandle.current().pid(), savedAt.minusSeconds(60), "swing");
            xml.markDirty(marker); registry.markDirty(marker); xml.save(original); registry.save(original);
            assertEquals(original, xml.load().orElseThrow()); assertEquals(original, registry.load().orElseThrow());
            originalXml = Files.readAllBytes(xml.file()); originalRegistry = registryImage(node);
            Files.write(root.resolve("before.xml"), originalXml);
            Files.writeString(root.resolve("before.snapshot.json"), new JsonSnapshotCodec().encode(original));
            assertEquals(original, new JsonSnapshotCodec().decode(Files.readString(root.resolve("before.snapshot.json"))));
            assertEquals(CrashDetector.Status.CRASHED, CrashDetector.detect(List.of(registry, xml), "swing").status());
        }

        /** Создаёт настоящий порт и контроллер; proxy не заменяет окна или результаты выбора. */
        void start() throws Exception {
            edt(() -> {
                assertTrue(java.util.Arrays.stream(Window.getWindows()).noneMatch(Window::isShowing), "Desktop JVM already has visible windows");
                SwingLook.install(); port = new SwingUiPort(environment);
                UiPort observed = (UiPort) Proxy.newProxyInstance(UiPort.class.getClassLoader(), new Class<?>[]{UiPort.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("chooseFile")) {
                                FileChooserSpec spec = (FileChooserSpec) args[0];
                                assertEquals(FileChooserSpec.Purpose.SAVE_SNAPSHOT_PLAN, spec.purpose());
                                Attempt attempt = pending.getAndSet(null);
                                if (attempt == null) throw new IllegalStateException("Unarmed native chooser request");
                                @SuppressWarnings("unchecked") Consumer<Optional<Path>> callback = (Consumer<Optional<Path>>) args[1];
                                port.chooseFile(spec, value -> attempt.returned(value, callback));
                                return null;
                            }
                            if (method.getName().equals("exit")) return null; // Не завершаем JVM Surefire; окна очищаются ниже.
                            try { return method.invoke(port, args); }
                            catch (InvocationTargetException e) { throw e.getCause(); }
                        });
                controller = new AppController(observed, environment); port.bind(controller); controller.start();
                return null;
            });
        }

        /** Подготавливает наблюдение очередного настоящего открытия, не задавая chooser-path. */
        Attempt arm() {
            Attempt attempt = new Attempt();
            assertTrue(pending.compareAndSet(null, attempt)); attempts.add(attempt); return attempt;
        }

        /** Находит физический активный alert по назначению ядра, а не по предположению о числе окон. */
        SwingAlerts alert(String purpose) throws Exception {
            AtomicReference<SwingAlerts> result = new AtomicReference<>();
            try {
                until(() -> read(() -> {
                for (SwingAlerts alert : port.alerts.values()) {
                    // Внешнее окно рабочего стола может забрать foreground между двумя сообщениями.
                    // Поднимаем только ожидаемое собственное окно, не подтверждая его вместо пользователя.
                    if (alert.spec.purpose().equals(purpose) && alert.dialog.isShowing() && !alert.dialog.isActive())
                        alert.dialog.toFront();
                    if (alert.spec.purpose().equals(purpose) && alert.dialog.isShowing() && alert.dialog.isActive()) {
                        result.set(alert); return true;
                    }
                }
                return false;
                }), "Actual alert not active: " + purpose);
            } catch (AssertionError failure) {
                String windows = edt(() -> {
                    StringBuilder state = new StringBuilder();
                    state.append("Expected purpose: ").append(purpose).append('\n');
                    state.append("Recorder started: ").append(controller.recorder() == null ? "not-created" : controller.recorder().isStarted()).append('\n');
                    for (SwingAlerts alert : port.alerts.values()) {
                        state.append(alert.spec.purpose()).append(" showing=").append(alert.dialog.isShowing())
                                .append(" active=").append(alert.dialog.isActive()).append(" bounds=")
                                .append(alert.dialog.getBounds()).append('\n');
                    }
                    for (Window window : Window.getWindows()) {
                        if (window.isShowing()) state.append(window.getClass().getSimpleName())
                                .append(" active=").append(window.isActive()).append(" bounds=")
                                .append(window.getBounds()).append('\n');
                    }
                    return state.toString();
                });
                Files.writeString(evidence.resolve("failed-alert-state.txt"), windows, StandardCharsets.UTF_8);
                for (Window window : edt(() -> java.util.Arrays.stream(Window.getWindows())
                        .filter(Window::isShowing).toList())) {
                    capture("failed-window-" + Integer.toHexString(System.identityHashCode(window)), window);
                }
                throw new AssertionError(failure.getMessage() + "\n" + windows, failure);
            }
            return result.get();
        }

        /** Нажимает физическую кнопку Robot-щелчком, не вызывая контроллер или doClick. */
        void clickAlert(String purpose, String button) throws Exception {
            SwingAlerts alert = alert(purpose);
            assertTrue(edt(() -> alert.spec.buttons().stream().anyMatch(value -> value.id().equals(button) && value.enabled())),
                    "Production AlertSpec lacks enabled id " + button);
            JButton actual = edt(() -> alert.buttons.get(button));
            assertNotNull(actual, "Actual alert lacks button " + button); click(actual);
        }

        /** Находит настоящий JFileChooser в дереве показанного окна своей JVM. */
        JFileChooser chooser() throws Exception {
            AtomicReference<JFileChooser> result = new AtomicReference<>();
            until(() -> read(() -> {
                List<JFileChooser> choices = new ArrayList<>();
                for (Window window : Window.getWindows()) if (window.isShowing() && window.isActive())
                    for (Component component : components(window)) if (component instanceof JFileChooser chooser
                            && chooser.isShowing() && chooser.getDialogType() == JFileChooser.SAVE_DIALOG
                            && UiText.get("s2.startup.saveTitle").equals(chooser.getDialogTitle())) choices.add(chooser);
                assertTrue(choices.size() <= 1, "Ambiguous actual native chooser");
                if (choices.isEmpty()) return false;
                result.set(choices.getFirst());
                nativeWindows.add(SwingUtilities.getWindowAncestor(result.get()));
                return true;
            }), "Real native chooser not shown");
            return result.get();
        }

        /** Проверяет фактический foreground-владелец перед отправкой Escape или ввода. */
        void focused(JFileChooser chooser) throws Exception {
            assertTrue(edt(() -> {
                Window window = SwingUtilities.getWindowAncestor(chooser);
                return chooser.isShowing() && window != null && window.isActive()
                        && KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow() == window;
            }), "Native chooser lost OS focus");
        }

        /** Вводит путь в настоящее filename-поле и нажимает настоящую кнопку сохранения. */
        void nativeSave(JFileChooser chooser, Path target, String evidence) throws Exception {
            focused(chooser); assertTrue(target.normalize().startsWith(memory));
            capture(evidence, edt(() -> SwingUtilities.getWindowAncestor(chooser)));
            JTextField field = edt(() -> {
                String caption = UIManager.getString("FileChooser.fileNameLabelText", chooser.getLocale());
                List<JTextField> fields = new ArrayList<>();
                for (Component component : components(chooser)) if (component instanceof JLabel label
                        && caption.equals(label.getText()) && label.getLabelFor() instanceof JTextField text) fields.add(text);
                assertEquals(1, fields.size(), "Native filename field is not identifiable"); return fields.getFirst();
            });
            click(field); until(() -> read(field::isFocusOwner), "Filename did not receive OS focus");
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            Transferable previous = clipboard.getContents(null);
            StringSelection text = new StringSelection(target.toString());
            try {
                clipboard.setContents(text, null); focused(chooser);
                chord(KeyEvent.VK_CONTROL, KeyEvent.VK_A); chord(KeyEvent.VK_CONTROL, KeyEvent.VK_V);
                until(() -> read(() -> field.getText().equals(target.toString())), "OS paste did not set native filename");
                JButton approve = edt(() -> {
                    String caption = chooser.getApproveButtonText();
                    if (caption == null) caption = UIManager.getString("FileChooser.saveButtonText", chooser.getLocale());
                    List<JButton> buttons = new ArrayList<>();
                    for (Component component : components(chooser)) if (component instanceof JButton button
                            && caption.equals(button.getText()) && button.isShowing() && button.isEnabled()) buttons.add(button);
                    assertEquals(1, buttons.size(), "Native save button is not identifiable"); return buttons.getFirst();
                });
                click(approve); until(() -> read(() -> !chooser.isShowing()), "Actual native save did not return");
            } finally {
                Transferable current = clipboard.getContents(null);
                if (current != null && current.isDataFlavorSupported(DataFlavor.stringFlavor)
                        && target.toString().equals(current.getTransferData(DataFlavor.stringFlavor)))
                    clipboard.setContents(previous == null ? new StringSelection("") : previous, null);
            }
        }

        /** Проверяет точные bytes/значения обоих хранилищ и отсутствие старта записи до решения. */
        void unchanged() throws Exception {
            assertArrayEquals(originalXml, Files.readAllBytes(xml.file()));
            assertEquals(originalRegistry, registryImage(node));
            assertEquals(original, xml.load().orElseThrow()); assertEquals(original, registry.load().orElseThrow());
            assertFalse(edt(() -> controller.recorder().isStarted()), "Recorder started before rescue was resolved");
        }

        /** Наблюдает начало настоящего рекордера после штатной успешной записи. */
        boolean started() { return read(() -> controller.recorder().isStarted()); }

        /** Щёлкает показанный включённый компонент в реальном активном окне. */
        void click(Component component) throws Exception {
            Rectangle rectangle = edt(() -> {
                Window owner = SwingUtilities.getWindowAncestor(component);
                assertTrue(component.isShowing() && component.isEnabled() && owner != null && owner.isActive());
                Point p = component.getLocationOnScreen(); return new Rectangle(p.x, p.y, component.getWidth(), component.getHeight());
            });
            assertTrue(rectangle.width > 0 && rectangle.height > 0);
            // ActiveWindow не доказывает, что OS pointer уже дошёл после первого layout.
            if (component instanceof JButton button) {
                var entered = new java.util.concurrent.atomic.AtomicBoolean();
                var listener = new java.awt.event.MouseAdapter() {
                    /** Наблюдает реальное событие OS независимо от rollover-настройки Metal. */
                    @Override public void mouseEntered(java.awt.event.MouseEvent event) { entered.set(true); }
                    /** Не принимает ранее доставленный enter после ухода указателя. */
                    @Override public void mouseExited(java.awt.event.MouseEvent event) { entered.set(false); }
                };
                edt(() -> { button.addMouseListener(listener); return null; });
                try {
                    robot.mouseMove(rectangle.x - 1, rectangle.y - 1); robot.waitForIdle();
                    robot.mouseMove(rectangle.x + rectangle.width / 2, rectangle.y + rectangle.height / 2);
                    until(() -> entered.get() && read(() -> button.getMousePosition() != null),
                            "OS pointer did not reach actual Swing button");
                    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                    try {
                        until(() -> read(() -> button.getModel().isPressed() && button.getModel().isArmed()),
                                "OS press did not arm actual Swing button");
                    } finally { robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); }
                } finally { edt(() -> { button.removeMouseListener(listener); return null; }); }
            } else {
                robot.mouseMove(rectangle.x + rectangle.width / 2, rectangle.y + rectangle.height / 2);
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                try { robot.waitForIdle(); } finally { robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); }
            }
        }

        /** Отправляет и отпускает реальную клавишу. */
        void key(int key) { robot.keyPress(key); robot.keyRelease(key); }

        /** Отправляет сочетание, гарантируя отпускание модификатора. */
        void chord(int modifier, int key) { robot.keyPress(modifier); try { key(key); } finally { robot.keyRelease(modifier); } }

        /** Сохраняет PNG настоящего OS-окна, а не невидимого каркаса главного окна. */
        void capture(String name, Window window) throws Exception {
            Rectangle rectangle = edt(() -> { assertNotNull(window); assertTrue(window.isShowing()); return window.getBounds(); });
            assertTrue(ImageIO.write(robot.createScreenCapture(rectangle), "png", evidence.resolve(name + ".png").toFile()));
        }

        /** Освобождает только окна своего порта, таймеры и UUID-узел; System.exit не вызывается. */
        @Override public void close() throws Exception {
            try {
                edt(() -> {
                    if (controller != null && controller.recorder() != null) controller.recorder().shutdownClean();
                    if (port != null) {
                        port.scheduler().shutdown(); if (port.keys != null) port.keys.close(); port.popups.hide();
                        new ArrayList<>(port.forms.values()).forEach(SwingFormDialog::close);
                        new ArrayList<>(port.alerts.values()).forEach(SwingAlerts::close);
                        nativeWindows.forEach(Window::dispose);
                        if (port.frame != null) port.frame.dispose();
                    }
                    return null;
                });
            } finally {
                if (Preferences.userRoot().nodeExists(node)) { Preferences.userRoot().node(node).removeNode(); Preferences.userRoot().flush(); }
                assertFalse(Preferences.userRoot().nodeExists(node));
                assertEquals(realBefore, registryImage("ru/cashprediction/session"), "Real session registry changed");
            }
        }
    }

    /** Выделяет уникальный принадлежащий тесту каталог только для PNG под явно заданным корнем. */
    private static Path evidenceDirectory(Path fallback, String prefix) throws Exception {
        String configured = System.getProperty("parity.native.directory");
        if (configured == null) return fallback;
        if (configured.isBlank()) throw new IllegalArgumentException("Empty native evidence root");
        Path allowed = Files.createDirectories(Path.of(configured).toAbsolutePath().normalize()).toRealPath();
        Path owned = Files.createTempDirectory(allowed, prefix).toRealPath();
        if (!owned.getParent().equals(allowed) || Files.isSymbolicLink(owned))
            throw new IllegalStateException("Native evidence directory escaped configured root");
        return owned;
    }

    /** Считывает только существующую ветку реестра, сохраняя все значения без исключений. */
    private static Map<String, String> registryImage(String path) throws Exception {
        Map<String, String> result = new TreeMap<>();
        if (Preferences.userRoot().nodeExists(path)) registryImage(Preferences.userRoot().node(path), result);
        return result;
    }

    /** Обходит только перечисленных реестром существующих потомков. */
    private static void registryImage(Preferences node, Map<String, String> result) throws Exception {
        result.put(node.absolutePath(), "<node>");
        for (String key : node.keys()) result.put(node.absolutePath() + "/" + key, node.get(key, "<missing>"));
        for (String child : node.childrenNames()) registryImage(node.node(child), result);
    }

    /** Собирает фактическое дерево компонентов своей JVM. */
    private static List<Component> components(Container container) {
        List<Component> result = new ArrayList<>();
        for (Component child : container.getComponents()) { result.add(child); if (child instanceof Container c) result.addAll(components(c)); }
        return result;
    }

    /** Ожидает наблюдаемое условие с конечным пределом, без assumption и фиксированной задержки готовности. */
    private static void until(BooleanSupplier ready, String error) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!ready.getAsBoolean()) { if (System.nanoTime() >= deadline) throw new AssertionError(error); Thread.sleep(25); }
    }

    /** Выполняет ограниченное чтение виджетов в EDT. */
    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeLater(task);
        return task.get(5, TimeUnit.SECONDS);
    }

    /** Преобразует ошибку наблюдения в явное падение ожидания, а не пропуск теста. */
    private static boolean read(Callable<Boolean> action) {
        try { return edt(action); } catch (Exception e) { throw new AssertionError("Actual Swing observation failed", e); }
    }
}
