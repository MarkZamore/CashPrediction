package ru.cashprediction.fx.ui;

import java.util.List;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Window;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.token.ColorToken;
import ru.cashprediction.core.ui.token.DesignTokens;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет вычисленный CSS и настоящие пиксели стандартных скинов в скрытой Scene, не реальную передачу фокуса ОС. */
@EnabledIfSystemProperty(named = "fx.styleProof", matches = "true")
class FxComputedStylesTest {
    private static VBox root;
    private static TextField text;
    private static TextArea area;
    private static ComboBox<String> choice;
    private static Spinner<Integer> spinner;
    private static Button primary, secondary;

    @BeforeAll static void prepareHiddenWidgets() throws Exception {
        Platform.startup(() -> Platform.setImplicitExit(false));
        FxComputedFontTest.onFx(() -> {
            text = new TextField(); area = new TextArea(); choice = new ComboBox<>(); choice.setEditable(true);
            spinner = new Spinner<>(0, 12, 1); spinner.setEditable(true);
            primary = new Button(UiText.get("button.save")); secondary = new Button(UiText.get("button.cancel"));
            FxStyles.dialogButton(primary); FxStyles.dialogButton(secondary); primary.setDefaultButton(true);
            root = new VBox(8, text, area, choice, spinner, primary, secondary); root.getStyleClass().add("cp-form");
            for (Control control : List.of(text, choice, spinner)) control.setPrefHeight(DesignTokens.CONTROL_HEIGHT);
            area.setPrefHeight(56); FxStyles.root(root); new Scene(root, 240, 300);
            FxComputedFontTest.css(root, 240, 300);
            assertTrue(Window.getWindows().stream().noneMatch(Window::isShowing));
        });
    }

    @AfterAll static void releaseHiddenWidgets() { Platform.exit(); }

    /** Рамка покоя состоит из настоящего border.strong слоя и белой поверхности. */
    @Test void restBordersAreComputedAndPaintedFromTokens() throws Exception {
        FxComputedFontTest.onFx(() -> {
            for (Control control : List.of(text, area, choice, spinner)) {
                state(control, false); layers(control, ColorToken.BORDER_STRONG);
                edge(control, ColorToken.BORDER_STRONG);
            }
        });
    }

    /** Focus pseudo-class меняет настоящий paint на accent, не только логические свойства. */
    @Test void focusedBordersAreComputedAndPaintedAccent() throws Exception {
        FxComputedFontTest.onFx(() -> {
            for (Control control : List.of(text, area, choice, spinner)) {
                state(control, true); layers(control, ColorToken.ACCENT); edge(control, ColorToken.ACCENT); state(control, false);
            }
        });
    }

    /** Составные редакторы сохраняют единственную внешнюю рамку и белый внутренний фон. */
    @Test void compoundEditorsKeepSingleOuterBorder() throws Exception {
        FxComputedFontTest.onFx(() -> {
            for (TextField editor : List.of(choice.getEditor(), spinner.getEditor())) {
                assertEquals(Color.web(ColorToken.BG_SURFACE.hex()), editor.getBackground().getFills().getFirst().getFill());
            }
            for (Control control : List.of(choice, spinner)) {
                control.pseudoClassStateChanged(PseudoClass.getPseudoClass("contains-focus"), true);
                root.applyCss(); layers(control, ColorToken.ACCENT);
                control.pseudoClassStateChanged(PseudoClass.getPseudoClass("contains-focus"), false);
            }
            root.applyCss();
        });
    }

    /** Настоящий defaultButton имеет accent, а соседняя CANCEL не получает выделение. */
    @Test void defaultButtonPaintAndForegroundAreAccent() throws Exception {
        FxComputedFontTest.onFx(() -> {
            layers(primary, ColorToken.ACCENT); edge(primary, ColorToken.ACCENT);
            assertEquals(Color.web(ColorToken.ACCENT.hex()), primary.getTextFill());
            assertNotEquals(primary.getTextFill(), secondary.getTextFill());
            assertTrue(primary.isDefaultButton()); assertFalse(secondary.isDefaultButton());
        });
    }

    /** Изменение состояния рисования не меняет реальную раскладку и высоту контролов. */
    @Test void focusAndDefaultStatesDoNotResizeWidgets() throws Exception {
        FxComputedFontTest.onFx(() -> {
            for (Control control : List.of(text, choice, spinner, primary)) {
                var before = control.getLayoutBounds(); state(control, true);
                assertEquals(before, control.getLayoutBounds()); state(control, false);
                assertEquals(DesignTokens.CONTROL_HEIGHT, control.getHeight(), 0.01);
            }
            assertTrue(Window.getWindows().stream().noneMatch(Window::isShowing));
        });
    }

    private static void state(Control control, boolean focused) {
        control.pseudoClassStateChanged(PseudoClass.getPseudoClass("focused"), focused);
        root.applyCss(); root.layout();
    }

    private static void layers(Region control, ColorToken outer) {
        List<BackgroundFill> fills = control.getBackground().getFills();
        assertEquals(2, fills.size(), control.getClass().getSimpleName());
        assertEquals(Color.web(outer.hex()), fills.getFirst().getFill());
        assertEquals(Color.web(ColorToken.BG_SURFACE.hex()), fills.getLast().getFill());
    }

    private static void edge(Region control, ColorToken expected) {
        WritableImage paint = control.snapshot(null, null);
        assertEquals(Color.web(expected.hex()), paint.getPixelReader().getColor((int) paint.getWidth() / 2, 0));
    }
}
