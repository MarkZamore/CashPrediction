package ru.cashprediction.fx.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет физический focus-путь драйвера в видимых сценах; скрытая Scene не доказывает native focusLost. */
@EnabledIfSystemProperty(named = "fx.focusProof", matches = "true")
class FxInputFocusTest {
    private static Stage editor, other;
    private static TextField input;
    private static VBox root;
    private static final List<String> observed = new ArrayList<>();

    /** Показывает два настоящих окна, чтобы первоначальный ввод требовал активации окна редактора. */
    @BeforeAll static void startDesktop() throws Exception {
        Platform.startup(() -> Platform.setImplicitExit(false));
        fx(() -> {
            input = new TextField(); root = new VBox(input); editor = new Stage();
            editor.setScene(new Scene(root, 260, 100)); editor.show();
            other = new Stage(); other.initOwner(editor); other.setScene(new Scene(new VBox(new Button("other")), 260, 100));
            other.show(); other.requestFocus();
            input.textProperty().addListener((observable, old, text) -> observed.add("typed:" + text + ":" + input.isFocused()));
            input.focusedProperty().addListener((observable, old, focused) -> {
                if (!focused) observed.add("blur:" + input.getText());
            });
            return null;
        });
    }

    /** Сырой ввод выполняется в фокусе; loss гарантирован до возврата, без преобразования текста самим драйвером. */
    @Test void completedFillProducesRealBlurBeforeReturning() throws Exception {
        fx(() -> { observed.clear(); return null; });
        FxUiDriver.typeCommittedText(input, "5000");
        fx(() -> {
            assertTrue(observed.contains("typed:5000:true"), observed.toString());
            assertTrue(observed.contains("blur:5000"), observed.toString());
            assertEquals("5000", input.getText(), "Driver must not normalize money itself");
            assertTrue(input.isFocused()); assertTrue(editor.isFocused());
            assertFalse(root.isFocusTraversable(), "Temporary commit target flag must be restored");
            return null;
        });
        FxUiDriver.typeCommittedText(input, "0");
        FxUiDriver.typeCommittedText(input, "bad");
        fx(() -> {
            assertTrue(observed.containsAll(List.of("typed:0:true", "blur:0", "typed:bad:true", "blur:bad")));
            assertEquals("bad", input.getText()); return null;
        });
        // Реальный Popup принадлежит главному окну, но перед его открытием
        // фокус может оставаться в немодальном калькуляторе другого Stage.
        TextField popupInput = fx(TextField::new);
        List<String> popupEvents = new ArrayList<>();
        javafx.stage.Popup popup = fx(() -> {
            // JavaFX: Popup → Swing: PopupFactory → Web: div.quick-edit
            var p = new javafx.stage.Popup(); p.getContent().add(new VBox(popupInput));
            popupInput.textProperty().addListener((observable, old, text) ->
                    popupEvents.add("typed:" + text + ":" + popupInput.isFocused()));
            popupInput.focusedProperty().addListener((observable, old, focused) -> {
                if (!focused) popupEvents.add("blur:" + popupInput.getText());
            });
            p.show(editor); other.requestFocus(); return p;
        });
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!fx(other::isFocused) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(fx(other::isFocused), "Other native stage must initially own focus");
            assertFalse(fx(editor::isFocused), "Popup owner must need native activation");
            FxUiDriver.typeCommittedText(popupInput, "bad733");
            fx(() -> {
                assertEquals("bad733", popupInput.getText());
                assertTrue(popupEvents.contains("typed:bad733:true"), popupEvents.toString());
                assertTrue(popupEvents.contains("blur:bad733"), popupEvents.toString());
                assertTrue(editor.isFocused()); assertTrue(popup.isFocused());
                assertTrue(popupInput.isFocused());
                return null;
            });
        } finally { fx(() -> { popup.hide(); return null; }); }
    }

    /** Закрывает только окна теста. */
    @AfterAll static void stopDesktop() throws Exception {
        try { fx(() -> { if (other != null) other.close(); if (editor != null) editor.close(); return null; }); }
        finally { Platform.exit(); }
    }

    /** Читает и меняет реальные виджеты только в FX-потоке с конечным ожиданием. */
    private static <T> T fx(Supplier<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action::get); Platform.runLater(task);
        return task.get(5, TimeUnit.SECONDS);
    }
}
