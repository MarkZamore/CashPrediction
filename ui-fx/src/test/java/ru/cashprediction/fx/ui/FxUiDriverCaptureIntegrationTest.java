package ru.cashprediction.fx.ui;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.stage.Popup;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.AppController;
import ru.cashprediction.core.app.AppEnvironment;
import ru.cashprediction.core.app.LaunchOptions;
import ru.cashprediction.core.app.SamplePlan;
import ru.cashprediction.core.ui.selftest.paint.PaintCaptureRequest;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Интеграция драйвера с настоящими сценами, Robot и замороженным журналом.
 * Требует свободный рабочий стол и явный -Dfx.captureProof=true; успешный snapshot
 * не объявляется доказательством краски узла или полным нативным проходом S5.
 */
@EnabledIfSystemProperty(named = "fx.captureProof", matches = "true")
class FxUiDriverCaptureIntegrationTest {
    @TempDir static Path home;
    private static FxUiPort port;
    private static FxUiDriver driver;
    private static AppController controller;
    private static AppEnvironment environment;

    /** Создаёт только изолированный стенд с реальным контроллером и памятью вместо реестра. */
    @BeforeAll static void start() throws Exception {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); }
        catch (IllegalStateException alreadyStarted) { fx(() -> { Platform.setImplicitExit(false); return null; }); }
        fx(() -> {
            environment = AppEnvironment.from(LaunchOptions.parse("--home", home.toString(), "--registry", "memory",
                    "--today", "2026-09-13"));
            port = new FxUiPort(new Stage()); port.selftest = true;
            controller = new AppController(port, environment); port.bind(controller);
            controller.document().replace(SamplePlan.create(environment.clock().today()), null, true, List.of());
            controller.showMain(null);
            driver = new FxUiDriver(port, controller, environment);
            return null;
        });
    }

    /** Устанавливает размер содержимого, не округляя и не подменяя outputScale. */
    @BeforeEach void ready() throws Exception {
        fx(() -> {
            port.main.dismissHover();
            // JavaFX: PopupWindow → Swing: JWindow → Web: div.popover
            javafx.stage.Window.getWindows().stream()
                    .filter(w -> w instanceof javafx.stage.PopupWindow popup && popup.getOwnerWindow() == port.stage)
                    .toList().forEach(javafx.stage.Window::hide);
            port.stage.setWidth(1200 + port.stage.getWidth() - port.stage.getScene().getWidth());
            port.stage.setHeight(800 + port.stage.getHeight() - port.stage.getScene().getHeight());
            port.stage.requestFocus(); Platform.requestNextPulse(); return null;
        });
        await(() -> port.stage.isFocused() && port.stage.getScene().getWidth() == 1200
                && port.stage.getScene().getHeight() == 800);
    }

    /** Закрывает окна и таймеры только своего стенда; toolkit может принадлежать другим тестам. */
    @AfterAll static void stop() throws Exception {
        fx(() -> {
            if (port != null) {
                port.main.dismissHover(); port.windows.values().forEach(ru.cashprediction.core.app.WindowHandle::close);
                port.scheduler.shutdown(); port.stage.close();
            }
            return null;
        });
    }

    /** Истёкший дедлайн не выполняет даже первый физический ввод и не удерживает capture lock. */
    @Test void expiredDeadlineDoesNotTouchUi() throws Exception {
        Node owner = fx(() -> port.stage.getScene().getFocusOwner());
        var request = request(Map.of(), System.nanoTime() - 1);
        assertThrows(TimeoutException.class, () -> driver.captureDiagnostic(request));
        assertSame(owner, fx(() -> port.stage.getScene().getFocusOwner()));
        assertThrows(TimeoutException.class, () -> driver.capture(request));
    }

    /** Дедлайн охватывает очередь: отменённый захват не начинает поздний ввод после освобождения FX. */
    @Test void deadlineCancelsCaptureStillQueuedOnFxThread() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Node owner = fx(() -> port.stage.getScene().getFocusOwner());
        Platform.runLater(() -> {
            entered.countDown();
            try { assertTrue(release.await(2, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
        });
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        try {
            assertThrows(TimeoutException.class, () -> driver.captureDiagnostic(request(Map.of(),
                    System.nanoTime() + Duration.ofMillis(50).toNanos())));
        } finally { release.countDown(); }
        fx(() -> null);
        assertSame(owner, fx(() -> port.stage.getScene().getFocusOwner()));
        assertThrows(UnsupportedOperationException.class, () -> driver.captureDiagnostic(
                request(Map.of("absent-card", PaintCaptureRequest.CardState.HOVER), deadline())));
        fx(() -> null);
    }

    /** Синхронный вызов из FX callback отклоняется вместо ожидания собственной очереди. */
    @Test void fxCallerCannotStartBlockingCapture() throws Exception {
        fx(() -> {
            assertThrows(IllegalStateException.class, () -> driver.captureDiagnostic(request(Map.of(), deadline())));
            return null;
        });
    }

    /** Драйвер возвращает настоящую диагностику и отказывает строгому пути при отсутствии node-paint hook. */
    @Test void diagnosticPairNeverBecomesStrictSuccess() throws Exception {
        var request = request(Map.of(), deadline());
        var result = captureWithInputDiagnostics(request);
        assertEquals("fx", result.raw().client()); assertEquals(request.scenario(), result.raw().scenario());
        assertEquals(request.step(), result.raw().step()); assertEquals(request.captureId(), result.observation().identity().captureId());
        assertTrue(result.observation().unsupported().stream().anyMatch(u -> u.property().equals("journal")));
        assertTrue(result.observation().unsupported().stream().anyMatch(u -> u.property().equals("lastPaintEpoch")));
        assertFalse(result.observation().synchronization().settled());
        assertTrue(result.observation().cards().stream().allMatch(card -> card.lastPaintEpoch() == 0));
        var image = new Image(new ByteArrayInputStream(result.png()));
        assertFalse(image.isError()); assertEquals(result.observation().viewport().pngWidth(), image.getWidth());
        assertEquals(result.observation().viewport().pngHeight(), image.getHeight());
        fx(() -> null); // Очистка lock стоит в FX-очереди после попытки.
        var failure = assertThrows(FxUiDriver.UnsupportedCapture.class, () -> driver.capture(request(Map.of(), deadline())));
        assertFalse(failure.result().observation().unsupported().isEmpty());
    }

    /** Hover и focus читаются из настоящих свойств после Robot, а не из намерения запроса. */
    @Test void physicalHoverAndKeyboardFocusAreObserved() throws Exception {
        Node card = fx(() -> port.main.summary.getChildren().stream()
                .filter(n -> !FxUiDumper.id(n).isEmpty()).findFirst().orElseThrow());
        String id = fx(() -> FxUiDumper.id(card));
        for (var state : List.of(PaintCaptureRequest.CardState.HOVER, PaintCaptureRequest.CardState.FOCUS)) {
            var result = captureWithInputDiagnostics(request(Map.of(id, state), deadline()));
            var actual = result.observation().cards().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
            assertEquals(fx(card::isHover).booleanValue(), actual.hover());
            assertEquals(fx(() -> descendant(port.stage.getScene().getFocusOwner(), card)).booleanValue(), actual.focusWithin());
            if (state == PaintCaptureRequest.CardState.HOVER) assertTrue(actual.hover());
            else { assertTrue(actual.focusWithin()); assertEquals("keyboard", result.observation().interaction().modality()); }
            assertTrue(result.observation().interaction().gestureAcknowledged());
            assertFalse(result.observation().unsupported().isEmpty());
            fx(() -> null);
        }
    }

    /** Проверяет факты отказа реального ввода и повторно бросает timeout, не превращая blocker в PASS. */
    private static FxPaintCollector.Result captureWithInputDiagnostics(PaintCaptureRequest request) throws Exception {
        try { return driver.captureDiagnostic(request); }
        catch (TimeoutException timeout) {
            String message = timeout.getMessage();
            if (message != null && message.startsWith("FX native")) {
                assertTrue(message.contains("sequence="), message);
                assertTrue(message.contains("modality="), message);
                assertTrue(message.contains("armedCallbackNanos="), message);
                assertTrue(message.contains("requested="), message);
                assertTrue(message.contains("physical="), message);
                assertTrue(message.contains("event="), message);
                assertTrue(message.contains("delivered="), message);
                assertTrue(message.contains("reason="), message);
                assertTrue(message.contains("nativeEventTime=unavailable"), message);
            }
            throw timeout;
        }
    }

    /** Источник конкретного Image не переносится на другой объект с теми же пикселями. */
    @Test void decodedSourceIsBoundToInstalledImageIdentity() throws Exception {
        fx(() -> {
            Image installed = retainedImage(port.main.toolbar.root);
            assertNotNull(installed);
            try (var transaction = transaction(r -> new FxUiDumper(port, controller).dump(r.scenario(), r.step()))) {
                var source = transaction.source(installed); assertNotNull(source); assertSame(installed, source.installed());
                byte[] bytes = source.png(); byte original = bytes[0]; bytes[0] ^= 1;
                assertEquals(original, transaction.source(installed).png()[0]);
                Image replacement = new Image(new ByteArrayInputStream(source.png()));
                assertNull(transaction.source(replacement));
                assertNull(transaction.lastPaintEpoch(port.main.toolbar.root));
            }
            return null;
        });
    }

    /** Отдельный факт возврата snapshot не устанавливает полноту журнала или стабильность DTO. */
    @Test void reportsSingleSnapshotWithoutCertifyingPaint() throws Exception {
        fx(() -> {
            port.main.dismissHover();
            try (var transaction = transaction(r -> new FxUiDumper(port, controller).dump(r.scenario(), r.step()))) {
                assertTrue(transaction.snapshots().isEmpty());
                transaction.png(); var after = transaction.stamp();
                transaction.png(); var repeated = transaction.stamp();
                assertEquals(after, repeated);
                assertEquals(1, transaction.snapshots().size());
                var snapshot = transaction.snapshots().getFirst();
                assertSame(port.stage.getScene(), snapshot.scene());
                assertEquals(after.renderGeneration(), snapshot.generationBefore());
                assertEquals(repeated.renderGeneration(), snapshot.generationAfter());
                assertEquals(snapshot.generationAfter(), snapshot.sceneSnapshotGeneration());
                assertEquals(1200, snapshot.pixelWidth()); assertEquals(800, snapshot.pixelHeight());
                assertFalse(after.mutationJournalComplete()); assertEquals(0, after.paintRevision());
                assertNull(transaction.lastPaintEpoch(port.main.root));
            }
            return null;
        });
    }

    /** Возврат A-B-A внутри raw callback увеличивает реальные поколения, даже когда свойства снова равны. */
    @Test void collectorBracketDetectsRealPropertyAbaWithoutSnapshotBump() throws Exception {
        fx(() -> {
            Node card = port.main.summary.getChildren().getFirst(); double opacity = card.getOpacity();
            try (var transaction = transaction(r -> {
                card.setOpacity(0.5); card.setOpacity(opacity);
                return new FxUiDumper(port, controller).dump(r.scenario(), r.step());
            })) {
                var result = new FxPaintCollector().collect(request(Map.of(), deadline()), port.stage.getScene(),
                        port.main.toolbar.root, port.main.summary, transaction);
                var bracket = result.observation().synchronization();
                assertTrue(bracket.epochAfter() > bracket.epochBefore());
                assertTrue(bracket.renderGenerationAfter() > bracket.renderGenerationBefore());
                assertTrue(bracket.changes().contains("mutation journal changed"));
                assertTrue(bracket.changes().contains("render/pulse generation changed"));
                assertFalse(bracket.stable()); assertEquals(opacity, card.getOpacity());
            }
            return null;
        });
    }

    /** PNG и Frame используют одни и те же округлённые преобразования overlay, включая обрезку слева. */
    @Test void ordinaryCompositorAndTransactionUseSameOverlayPixels() throws Exception {
        fx(() -> {
            // JavaFX: Popup → Swing: PopupFactory → Web: div.popover
            Popup popup = new Popup(); Pane content = new Pane();
            content.setPrefSize(20, 12); content.setStyle("-fx-background-color: #153759;"); popup.getContent().add(content);
            popup.setAutoFix(false); popup.setAnchorLocation(javafx.stage.PopupWindow.AnchorLocation.CONTENT_TOP_LEFT);
            var base = port.main.root.localToScreen(port.main.root.getBoundsInLocal());
            popup.show(port.stage, base.getMinX() - 4.25, base.getMinY() + 10.25);
            try (var transaction = transaction(r -> new FxUiDumper(port, controller).dump(r.scenario(), r.step()))) {
                var frame = transaction.frame();
                var surface = frame.surfaces().stream().filter(s -> s.scene() == popup.getScene()).findFirst().orElseThrow();
                var location = popup.getScene().getRoot().localToScreen(popup.getScene().getRoot().getBoundsInLocal());
                assertEquals(Math.round(location.getMinX() - base.getMinX()), surface.sceneToPng().tx());
                assertEquals(Math.round(location.getMinY() - base.getMinY()), surface.sceneToPng().ty());
                byte[] nativePng = transaction.png();
                assertEquals(frame.surfaces().size(), transaction.snapshots().size());
                for (int i = 0; i < frame.surfaces().size(); i++) {
                    var completed = transaction.snapshots().get(i);
                    assertSame(frame.surfaces().get(i).scene(), completed.scene());
                    assertTrue(completed.generationAfter() >= completed.generationBefore());
                    assertEquals(completed.generationAfter(), completed.sceneSnapshotGeneration());
                }
                byte[] ordinary = driver.screenshot("compositor-regression");
                assertArrayEquals(ordinary, nativePng);
                var image = new Image(new ByteArrayInputStream(nativePng));
                assertEquals(0xff153759, image.getPixelReader().getArgb(2, (int) surface.sceneToPng().ty() + 3));
                var afterFirst = transaction.stamp(); transaction.png();
                assertEquals(afterFirst, transaction.stamp());
                assertNull(transaction.lastPaintEpoch(content));
            } finally { popup.hide(); }
            return null;
        });
    }

    /** Создаёт реальные зависимости, не предоставляя тестовых штампов или фиктивного paint hook. */
    private static FxUiDriver.CaptureTransaction transaction(java.util.function.Function<PaintCaptureRequest,
            ru.cashprediction.core.ui.dump.UiDump> raw) {
        return new FxUiDriver.CaptureTransaction(port.stage, raw, Map.of());
    }

    /** Ищет установленное изображение с удержанным источником в настоящих виджетах панели. */
    private static Image retainedImage(Node node) {
        if (node instanceof ImageView view && FxIcons.decodedSource(view.getImage()).isPresent()) return view.getImage();
        if (node instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) {
            Image found = retainedImage(child); if (found != null) return found;
        }
        return null;
    }

    /** Сопоставляет фактического владельца фокуса с поддеревом карточки. */
    private static boolean descendant(Node node, Node root) {
        for (; node != null; node = node.getParent()) if (node == root) return true;
        return false;
    }

    /** Фиктивен только внешний хеш плана; он никогда не используется как доказательство пикселей. */
    private static PaintCaptureRequest request(Map<String, PaintCaptureRequest.CardState> states, long deadline) {
        return new PaintCaptureRequest(UUID.randomUUID(), UUID.randomUUID(), 1, "driver-capture", "attempt", 1,
                "a".repeat(64), states, deadline);
    }

    /** Ограничивает только стенд; рабочий драйвер использует дедлайн запроса. */
    private static long deadline() { return System.nanoTime() + Duration.ofSeconds(5).toNanos(); }

    /** Ждёт фактическое условие стенда, не создавая paint acknowledgement. */
    private static void await(Callable<Boolean> condition) throws Exception {
        long end = deadline();
        while (!fx(condition)) { if (System.nanoTime() - end >= 0) throw new TimeoutException("desktop fixture"); Thread.sleep(5); }
    }

    /** Передаёт операцию стенда на настоящий FX-поток. */
    private static <T> T fx(Callable<T> action) throws Exception {
        var task = new FutureTask<T>(action); Platform.runLater(task); return task.get(10, TimeUnit.SECONDS);
    }
}
