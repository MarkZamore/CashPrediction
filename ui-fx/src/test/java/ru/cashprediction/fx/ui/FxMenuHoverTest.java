package ru.cashprediction.fx.ui;

import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import ru.cashprediction.core.ui.text.UiText;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет смену реальной строки меню без одновременного синтетического и физического hover. */
@EnabledIfSystemProperty(named = "fx.menuHoverProof", matches = "true")
class FxMenuHoverTest {
    private static Stage stage;
    private static ContextMenu menu;
    private static Node png, save;

    /** Показывает настоящие строки меню с теми же обработчиками подсказок, что использует приложение. */
    @BeforeAll static void prepare() throws Exception {
        Platform.startup(() -> Platform.setImplicitExit(false));
        fx(() -> {
            stage = new Stage(); stage.setScene(new Scene(new StackPane(), 500, 300)); stage.show(); stage.toFront();
            // JavaFX: ContextMenu → Swing: JPopupMenu → Web: div[role=menu]
            menu = new ContextMenu();
            // JavaFX: MenuItem → Swing: JMenuItem → Web: div[role=menuitem]
            MenuItem pngItem = new MenuItem(UiText.get("menu.file.savePng"));
            // JavaFX: MenuItem → Swing: JMenuItem → Web: div[role=menuitem]
            MenuItem saveItem = new MenuItem(UiText.get("menu.file.save"));
            menu.getItems().addAll(pngItem, saveItem); menu.show(stage, stage.getX() + 20, stage.getY() + 50);
            menu.getScene().getRoot().applyCss(); menu.getScene().getRoot().layout();
            png = pngItem.getStyleableNode(); save = saveItem.getStyleableNode();
            var probe = new FxClassUsageProbe();
            FxStyles.menuTip(png, UiText.get("menu.file.savePng.tip"), probe);
            FxStyles.menuTip(save, UiText.get("menu.file.save.tip"), probe);
            return null;
        });
    }

    /** Ранее наведённый PNG не остаётся вторым tooltip после перехода настоящего указателя на Save. */
    @Test void movingFromPngToSaveShowsOnlySaveTooltip() throws Exception {
        FxUiDriver.hoverMenuNode(png); Thread.sleep(750);
        assertEquals(List.of(UiText.get("menu.file.savePng.tip")), fx(FxMenuHoverTest::shownTips));
        // Отрицательный контроль воспроизводит прежний драйвер: указатель остаётся над PNG,
        // а событие Save добавлено искусственно. Наблюдение обязано сохранить обе подсказки.
        fx(() -> {
            save.fireEvent(new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_ENTERED,
                    1, 1, 1, 1, javafx.scene.input.MouseButton.NONE, 0,
                    false, false, false, false, false, false, false, false, false, false, null));
            return null;
        });
        Thread.sleep(750);
        assertEquals(2, fx(FxMenuHoverTest::shownTips).size());
        assertFalse(fx(() -> save.localToScreen(save.getBoundsInLocal())
                .contains(new javafx.scene.robot.Robot().getMousePosition())));
        fx(() -> {
            save.fireEvent(new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_EXITED,
                    1, 1, 1, 1, javafx.scene.input.MouseButton.NONE, 0,
                    false, false, false, false, false, false, false, false, false, false, null));
            return null;
        });
        FxUiDriver.hoverMenuNode(save); Thread.sleep(750);
        assertEquals(List.of(UiText.get("menu.file.save.tip")), fx(FxMenuHoverTest::shownTips));
        assertTrue(fx(save::isHover)); assertFalse(fx(png::isHover));
    }

    /** Читает все действительно показанные Tooltip, не отбрасывая лишние экземпляры из наблюдения. */
    private static List<String> shownTips() {
        return Window.getWindows().stream().filter(Window::isShowing).filter(Tooltip.class::isInstance)
                .map(Tooltip.class::cast).map(Tooltip::getText).sorted().toList();
    }

    /** Закрывает собственные окна и toolkit даже после отказа проверки. */
    @AfterAll static void close() throws Exception {
        try { fx(() -> { if (menu != null) menu.hide(); if (stage != null) stage.close(); return null; }); }
        finally { Platform.exit(); }
    }

    /** Передаёт действие в FX и ограничивает ожидание, не блокируя поток интерфейса. */
    private static <T> T fx(Supplier<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action::get); Platform.runLater(task); return task.get(15, TimeUnit.SECONDS);
    }
}
