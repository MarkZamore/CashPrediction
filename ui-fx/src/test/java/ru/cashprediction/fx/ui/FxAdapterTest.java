package ru.cashprediction.fx.ui;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.command.KeyChord;
import static org.junit.jupiter.api.Assertions.*;

/** Чистые проверки адаптеров, которым не нужен рабочий стол JavaFX. */
class FxAdapterTest {
    /** Резерв не создаёт фиктивных результатов и использует дробную метрику настоящего BASE-шрифта. */
    @Test void resultMinimumUsesActualFontMetricsWithoutDummyLines() {
        var region = new javafx.scene.layout.VBox();
        FxResultMetrics.reserve(region, 3);
        var sample = new javafx.scene.text.Text("Ag");
        sample.setFont(javafx.scene.text.Font.font(ru.cashprediction.core.ui.token.FontToken.BASE.primaryFamily(),
                ru.cashprediction.core.ui.token.FontToken.BASE.sizePx()));
        assertEquals(3 * sample.getLayoutBounds().getHeight(), region.getMinHeight());
        assertTrue(region.getChildren().isEmpty());
        FxResultMetrics.reserve(region, 0); assertEquals(0, region.getMinHeight());
    }
    /** Минимум не становится фиксированной высотой при появлении дополнительных или перенесённых строк. */
    @Test void actualResultLinesGrowBeyondTheirMinimum() {
        var region = new javafx.scene.layout.VBox(); FxResultMetrics.reserve(region, 3);
        var text = new javafx.scene.text.Text("one\ntwo\nthree\nfour\nfive");
        text.setFont(javafx.scene.text.Font.font(ru.cashprediction.core.ui.token.FontToken.BASE.primaryFamily(),
                ru.cashprediction.core.ui.token.FontToken.BASE.sizePx()));
        region.getChildren().add(text);
        assertTrue(region.prefHeight(300) > region.getMinHeight());
        text.setText("one two three four five six seven eight nine ten"); text.setWrappingWidth(40);
        assertTrue(region.prefHeight(40) > region.getMinHeight());
        assertEquals(javafx.scene.layout.Region.USE_COMPUTED_SIZE, region.getMaxHeight());
    }
    /** Ограничение свежего окна учитывает монитор со смещением и не подменяет его размеры. */
    @Test void freshPlacementClampsOnlyItsPosition() {
        assertEquals(-1900, FxContentPlacement.clamp(-2100, 560, -1900, 1366));
        assertEquals(-1094, FxContentPlacement.clamp(0, 560, -1900, 1366));
        assertEquals(-1500, FxContentPlacement.clamp(-1500, 560, -1900, 1366));
        assertEquals(4, FxContentPlacement.clamp(20, 764, 0, 768));
    }
    /** Перевод экранных измерений содержимого убирает только начало главного содержимого. */
    @Test void windowContentTranslationKeepsRealSize() {
        var content = new javafx.geometry.BoundingBox(530, 420, 560, 681);
        var main = new javafx.geometry.BoundingBox(210, 160, 1200, 800);
        var relative = FxUiDumper.relativeContent(content, main);
        assertEquals(320, relative.x()); assertEquals(260, relative.y());
        assertEquals(560, relative.width()); assertEquals(681, relative.height());
        assertNull(FxUiDumper.relativeContent(null, main));
        assertNull(FxUiDumper.relativeContent(content, null));
    }
    /** Рамка панели совпадает с её выделенной областью, а не с объединением теней и детей вне панели. */
    @Test void regionBoxMeasuresActualAllocatedLayout() {
        var pane = new javafx.scene.layout.Pane(); pane.resize(1200, 630);
        pane.getChildren().add(new javafx.scene.text.Text(1200, 630, "Outside"));
        assertTrue(pane.getBoundsInLocal().getWidth() > pane.getLayoutBounds().getWidth());
        var box = FxUiDumper.box(pane); assertEquals(1200, box.width()); assertEquals(630, box.height());
    }
    /** Базовая линия учитывает реальный шрифт текста и преобразование в координаты сцены. */
    @Test void baselineUsesActualTextGeometry() {
        var pane = new javafx.scene.layout.Pane();
        var text = new javafx.scene.text.Text(7, 20, "Baseline"); text.setFont(javafx.scene.text.Font.font("Segoe UI", 13));
        text.setTranslateX(11); text.setTranslateY(17); pane.getChildren().add(text);
        var expected = text.localToScene(text.getX(), text.getY());
        var actual = FxUiDumper.baseline(pane).orElseThrow();
        assertEquals(expected.getX(), actual.x(), 0.01); assertEquals(expected.getY(), actual.y(), 0.01);
        assertEquals(text.getBoundsInLocal().getWidth(), actual.width(), 0.01); assertEquals(1, actual.height());
        text.setVisible(false); assertTrue(FxUiDumper.baseline(pane).isEmpty());
    }
    /** Прозрачная ячейка наследует фактический белый фон контейнера, не модель строки. */
    @Test void transparentCellReadsActualBackingSurface() {
        var parent = new javafx.scene.layout.Pane(); var cell = new javafx.scene.layout.Pane(); parent.getChildren().add(cell);
        parent.setBackground(new javafx.scene.layout.Background(new javafx.scene.layout.BackgroundFill(javafx.scene.paint.Color.WHITE, javafx.scene.layout.CornerRadii.EMPTY, javafx.geometry.Insets.EMPTY)));
        cell.setBackground(new javafx.scene.layout.Background(new javafx.scene.layout.BackgroundFill(javafx.scene.paint.Color.TRANSPARENT, javafx.scene.layout.CornerRadii.EMPTY, javafx.geometry.Insets.EMPTY)));
        assertEquals("bg.surface", FxUiDumper.effectiveBackground(cell, null));
        cell.setBackground(new javafx.scene.layout.Background(new javafx.scene.layout.BackgroundFill(javafx.scene.paint.Color.web(ru.cashprediction.core.ui.token.ColorToken.BG_ALT.hex()), javafx.scene.layout.CornerRadii.EMPTY, javafx.geometry.Insets.EMPTY)));
        assertEquals(ru.cashprediction.core.ui.token.ColorToken.BG_ALT.id(), FxUiDumper.effectiveBackground(cell, parent));
    }
    /** Физическая клавиша остаётся той же при русском тексте события. */
    @Test void physicalKeysIgnoreTypedCharacters() {
        KeyEvent event = new KeyEvent(KeyEvent.KEY_PRESSED, "\u0448", "\u0448", KeyCode.I, false, true, false, false);
        assertEquals(KeyChord.parse("Ctrl+I"), FxKeyBridge.chord(event));
    }
    /** Две цифры и модификаторы не превращаются в символ раскладки. */
    @Test void alternateChordRetainsAllModifiers() {
        KeyEvent event = new KeyEvent(KeyEvent.KEY_PRESSED, "!", "!", KeyCode.DIGIT1, true, false, true, false);
        assertEquals(KeyChord.parse("Alt+Shift+1"), FxKeyBridge.chord(event));
    }
    /** Перепись учитывает только реально переданные экземпляры. */
    @Test void censusDoesNotInventToolkitClasses() {
        FxClassUsageProbe probe = new FxClassUsageProbe(); Object instance = new Object();
        assertSame(instance, probe.created(instance)); assertTrue(probe.snapshot().isEmpty());
    }
    /** Alt вызывает меню один раз при отпускании, а сочетание с другой клавишей не вызывает его. */
    @Test void standaloneAltOnlyFiresOnFirstRelease() {
        var gesture = new FxKeyBridge.AltGesture(); gesture.pressed(KeyCode.ALT);
        assertTrue(gesture.released(KeyCode.ALT)); assertFalse(gesture.released(KeyCode.ALT));
        gesture.pressed(KeyCode.ALT); gesture.pressed(KeyCode.I); assertFalse(gesture.released(KeyCode.ALT));
        gesture.pressed(KeyCode.ALT); gesture.pressed(KeyCode.I); gesture.pressed(KeyCode.ALT); assertFalse(gesture.released(KeyCode.ALT));
    }
}
