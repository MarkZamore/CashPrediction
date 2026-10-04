package ru.cashprediction.fx.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.event.InputEvent;
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
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.scene.control.Button;
import javafx.geometry.Bounds;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.session.codec.JsonSnapshotCodec;
import ru.cashprediction.core.session.store.*;
import ru.cashprediction.core.ui.alert.AlertCatalog;

/**
 * Настоящий JavaFX rescue без режима selftest: контроллер, физические окна и штатный Windows FileChooser.
 * Декоратор наблюдает неизменённый native-результат и разрешает исходный callback после проверок.
 * Кнопки и filename-поле активируются исключительно Robot; callback-путь не подставляется.
 */
@EnabledIfSystemProperty(named = "parity.e2e.nativeRescue", matches = "true")
@Timeout(value = 150, unit = TimeUnit.SECONDS)
class NativeFxStartupRescueTest {
    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final String PAYLOAD = "unreadable-native-rescue\nТочный исходный текст <&> \"quoted\"\r\nend\n";

    /** Запускает toolkit единожды; повторное использование живого toolkit проверяется bounded callback. */
    @BeforeAll static void startupPlatform() throws Exception {
        assertTrue(System.getProperty("os.name").startsWith("Windows"), "Actual Windows chooser required");
        assertFalse(GraphicsEnvironment.isHeadless(), "Interactive desktop required");
        CountDownLatch ready = new CountDownLatch(1);
        Runnable initialize = () -> { Platform.setImplicitExit(false); ready.countDown(); };
        try { Platform.startup(initialize); }
        catch (IllegalStateException alreadyStarted) { Platform.runLater(initialize); }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "FX toolkit did not deliver startup callback");
    }

    /** Отмена, настоящая ошибка записи и успешное спасение не теряют исходный снимок до решения. */
    @Test void actualNativeCancelFailureAndSuccessPreserveOriginalPayload() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless(), "Interactive desktop required; enabled test cannot skip");
        assertFalse(Platform.isFxApplicationThread());
        try (Harness h = new Harness()) {
            h.start();
            h.clickAlert("crashRecovery", AlertCatalog.BUTTON_RESTORE_XML);
            FxAlerts report = h.alert("restoreReport");
            assertTrue(fx(() -> report.details.getText()).contains(RestoreCoordinator.RECORDER_NOT_STARTED));
            h.clickAlert("restoreReport", AlertCatalog.BUTTON_OK);
            FxAlerts preserve = h.alert("recorderNotStarted");
            assertEquals(PAYLOAD, fx(() -> preserve.spec.details()));
            // TextArea приводит CRLF к LF только при отображении; модель и спасённые байты остаются исходными.
            assertEquals(PAYLOAD.replace("\r\n", "\n"), fx(() -> preserve.details.getText()));
            h.capture("unresolved-rescue", fx(() -> preserve.alert.getDialogPane().getScene().getWindow()));
            h.unchanged();

            Attempt cancel = h.arm();
            h.clickAlert("recorderNotStarted", "saveSnapshotPlan");
            NativeFxRescueKeyboard.capture(h.root, h.node, h.root.resolve("cancel-open.png"), WAIT);
            h.publishNativePng("cancel-open");
            NativeFxRescueKeyboard.cancel(h.root, h.node, WAIT);
            assertTrue(cancel.selected.get(WAIT.toMillis(), TimeUnit.MILLISECONDS).isEmpty());
            h.unchanged();
            cancel.release();
            h.alert("recorderNotStarted");
            h.unchanged();

            Path failed = h.memory.resolve("native-write-failure.md");
            Attempt failure = h.arm();
            h.clickAlert("recorderNotStarted", "saveSnapshotPlan");
            h.nativeSave(failed, "failure-open");
            assertEquals(Optional.of(failed), failure.selected.get(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            h.unchanged();
            Files.createDirectory(failed);
            Path blocker = failed.resolve("owned-blocker");
            String token = UUID.randomUUID().toString();
            Files.writeString(blocker, token, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            failure.release();
            // FX nativeReplacePrompt=true: исходный callback сразу выполняет настоящую запись.
            FxAlerts error = h.alert("err.snapshotPlanSave");
            h.capture("actual-write-error", fx(() -> error.alert.getDialogPane().getScene().getWindow()));
            h.unchanged();
            assertEquals(token, Files.readString(blocker));
            h.clickAlert("err.snapshotPlanSave", AlertCatalog.BUTTON_OK);
            h.alert("recorderNotStarted");
            h.unchanged();
            Files.delete(blocker); // Только созданные тестом объекты, без рекурсивного удаления.
            Files.delete(failed);

            Path saved = h.memory.resolve("native-success-\u043F\u043B\u0430\u043D.md");
            Attempt success = h.arm();
            h.clickAlert("recorderNotStarted", "saveSnapshotPlan");
            h.nativeSave(saved, "success-open");
            assertEquals(Optional.of(saved), success.selected.get(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            h.unchanged();
            success.release();
            until(() -> h.started(), "Recorder did not start after actual rescue success");
            assertArrayEquals(PAYLOAD.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(saved));
            assertEquals(3, h.attempts.size());
            for (Attempt attempt : h.attempts) assertEquals(1, attempt.deliveries.get());
            h.capture("success-main", fx(() -> h.port.stage));
        }
    }

    /** Одноразовый наблюдатель, в котором путь приходит только из исходного callback настоящего порта. */
    private static final class Attempt {
        final CompletableFuture<Optional<Path>> selected = new CompletableFuture<>();
        final CompletableFuture<Void> permission = new CompletableFuture<>();
        final java.util.concurrent.atomic.AtomicInteger deliveries = new java.util.concurrent.atomic.AtomicInteger();

        /** Наблюдает native-результат и доставляет тот же объект после разрешения в FX thread. */
        void returned(Optional<Path> value, Consumer<Optional<Path>> original) {
            if (!selected.complete(value)) throw new IllegalStateException("Duplicate native return");
            permission.thenRun(() -> Platform.runLater(() -> {
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
        final Path root = Files.createTempDirectory("cp-native-fx-rescue-");
        final Path evidence = evidenceDirectory(root, "fx-");
        final Path memory = Files.createDirectories(root.resolve("CashMemory"));
        final String node = "ru/cashprediction/selftest/" + UUID.randomUUID();
        final Map<String, String> realBefore = registryImage("ru/cashprediction/session");
        final AppEnvironment environment;
        final XmlSessionStore xml;
        final RegistrySessionStore registry;
        final Robot robot = new Robot();
        final List<Attempt> attempts = new ArrayList<>();
        final AtomicReference<Attempt> pending = new AtomicReference<>();
        final SessionSnapshot original;
        final byte[] originalXml;
        final Map<String, String> originalRegistry;
        FxUiPort port;
        AppController controller;

        /** Сеет корректно закодированный снимок с неисправным Markdown, не повреждая транспорт снимка. */
        Harness() throws Exception {
            environment = AppEnvironment.from(LaunchOptions.parse("--home", root.toString(), "--registry-node", node,
                    "--today", "2026-09-13", "--ui", "core"));
            assertFalse(environment.options().isSelftest(), "Native chooser must not take selftest stub path");
            xml = environment.xmlStore("fx"); registry = environment.registryStore("fx");
            assertTrue(registry.isAvailable(), registry.unavailableReason());
            Instant savedAt = Instant.now().minusSeconds(60);
            original = SessionSnapshot.of(savedAt, "fx", MainWindowState.empty(), PlanState.dirty(PAYLOAD), List.of());
            // Маркер собственной JVM считается предыдущим оборванным сеансом, а не другим живым экземпляром.
            SessionMarker marker = SessionMarker.running(ProcessHandle.current().pid(), savedAt.minusSeconds(60), "fx");
            xml.markDirty(marker); registry.markDirty(marker); xml.save(original); registry.save(original);
            assertEquals(original, xml.load().orElseThrow()); assertEquals(original, registry.load().orElseThrow());
            originalXml = Files.readAllBytes(xml.file()); originalRegistry = registryImage(node);
            Files.write(root.resolve("before.xml"), originalXml);
            Files.writeString(root.resolve("before.snapshot.json"), new JsonSnapshotCodec().encode(original));
            assertEquals(original, new JsonSnapshotCodec().decode(Files.readString(root.resolve("before.snapshot.json"))));
            assertEquals(CrashDetector.Status.CRASHED, CrashDetector.detect(List.of(registry, xml), "fx").status());
        }

        /** Создаёт настоящий порт и контроллер; proxy не заменяет окна или результаты выбора. */
        void start() throws Exception {
            fx(() -> {
                assertTrue(Window.getWindows().stream().noneMatch(Window::isShowing), "Desktop JVM already has visible windows");
                port = new FxUiPort(new Stage(), environment.clock()::today);
                assertFalse(port.selftest);
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
        FxAlerts alert(String purpose) throws Exception {
            AtomicReference<FxAlerts> result = new AtomicReference<>();
            until(() -> read(() -> {
                for (WindowHandle handle : port.windows.values()) {
                    if (handle instanceof FxAlerts alert && alert.spec.purpose().equals(purpose)
                            && alert.showing() && alert.alert.getDialogPane().getScene().getWindow().isFocused()) {
                        result.set(alert); return true;
                    }
                }
                return false;
            }), "Actual alert not active: " + purpose);
            return result.get();
        }

        /** Нажимает физическую кнопку Robot-щелчком, не вызывая контроллер или doClick. */
        void clickAlert(String purpose, String button) throws Exception {
            FxAlerts alert = alert(purpose);
            assertTrue(fx(() -> alert.spec.buttons().stream().anyMatch(value -> value.id().equals(button) && value.enabled())),
                    "Production AlertSpec lacks enabled id " + button);
            Button actual = fx(() -> alert.buttons.get(button));
            assertNotNull(actual, "Actual alert lacks button " + button); click(actual);
        }

        /** Вводит путь только настоящими OS-событиями в принадлежащий JVM native chooser. */
        void nativeSave(Path target, String evidence) throws Exception {
            NativeFxRescueKeyboard.capture(root, node, root.resolve(evidence + ".png"), WAIT);
            publishNativePng(evidence);
            NativeFxRescueKeyboard.save(target, root, node, WAIT);
        }

        /** Копирует только созданный OS-helper PNG; приватные снимки остаются в изолированном TEMP. */
        void publishNativePng(String name) throws Exception {
            if (!evidence.equals(root)) Files.copy(root.resolve(name + ".png"), evidence.resolve(name + ".png"));
        }

        /** Проверяет точные bytes/значения обоих хранилищ и отсутствие старта записи до решения. */
        void unchanged() throws Exception {
            assertArrayEquals(originalXml, Files.readAllBytes(xml.file()));
            assertEquals(originalRegistry, registryImage(node));
            assertEquals(original, xml.load().orElseThrow()); assertEquals(original, registry.load().orElseThrow());
            assertFalse(fx(() -> controller.recorder().isStarted()), "Recorder started before rescue was resolved");
        }

        /** Наблюдает начало настоящего рекордера после штатной успешной записи. */
        boolean started() { return read(() -> controller.recorder().isStarted()); }

        /** Нажимает настоящий Button в сфокусированном FX-окне без fire или callback-подмены. */
        void click(Button button) throws Exception {
            Rectangle rectangle = fx(() -> {
                Window owner = button.getScene().getWindow();
                assertTrue(button.isVisible() && !button.isDisabled() && owner.isShowing() && owner.isFocused());
                Bounds b = button.localToScreen(button.getBoundsInLocal());
                assertNotNull(b);
                return new Rectangle((int) Math.floor(b.getMinX()), (int) Math.floor(b.getMinY()),
                        (int) Math.ceil(b.getWidth()), (int) Math.ceil(b.getHeight()));
            });
            assertTrue(rectangle.width > 0 && rectangle.height > 0);
            robot.mouseMove(rectangle.x + rectangle.width / 2, rectangle.y + rectangle.height / 2);
            // Фокус окна ещё не доказывает доставку OS pointer после первого layout/pulse.
            until(() -> read(button::isHover), "OS pointer did not reach actual FX button");
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            try { } finally { robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); }
        }

        /** Сохраняет реальные OS-пиксели показанного окна после успешного спасения. */
        void capture(String name, Window window) throws Exception {
            Rectangle rectangle = fx(() -> {
                assertNotNull(window); assertTrue(window.isShowing());
                return new Rectangle((int) window.getX(), (int) window.getY(),
                        (int) Math.ceil(window.getWidth()), (int) Math.ceil(window.getHeight()));
            });
            assertTrue(ImageIO.write(robot.createScreenCapture(rectangle), "png", evidence.resolve(name + ".png").toFile()));
        }

        /** Освобождает только окна своего порта, таймеры и UUID-узел; System.exit не вызывается. */
        @Override public void close() throws Exception {
            try {
                fx(() -> {
                    if (controller != null && controller.recorder() != null) controller.recorder().shutdownClean();
                    if (port != null) {
                        port.scheduler().shutdown();
                        new ArrayList<>(port.windows.values()).forEach(WindowHandle::close);
                        port.stage.hide();
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

    /** Ожидает наблюдаемое условие с конечным пределом, без assumption и фиксированной задержки готовности. */
    private static void until(BooleanSupplier ready, String error) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!ready.getAsBoolean()) { if (System.nanoTime() >= deadline) throw new AssertionError(error); Thread.sleep(25); }
    }

    /** Выполняет ограниченное чтение виджетов в FX thread. */
    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); Platform.runLater(task);
        return task.get(5, TimeUnit.SECONDS);
    }

    /** Преобразует ошибку наблюдения в явное падение ожидания, а не пропуск теста. */
    private static boolean read(Callable<Boolean> action) {
        try { return fx(action); } catch (Exception e) { throw new AssertionError("Actual FX observation failed", e); }
    }
}
