package ru.cashprediction.fx.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.token.ColorToken;
import ru.cashprediction.core.ui.token.DesignTokens;
import ru.cashprediction.core.ui.token.UiIcons;
import ru.cashprediction.core.ui.view.table.DecoratedTooltip;

/** Проверяет настоящие узлы подсказки в отдельном opt-in процессе без показа окон. */
@EnabledIfSystemProperty(named = "fx.decoratedTooltipProof", matches = "true")
public class FxDecoratedTooltipTest {
    private static final List<String> TEXT_KEYS = List.of("table.tip.amountChanged", "table.tip.moved",
            "table.tip.shifted", "table.tip.skipped", "table.tip.whatIfAmount");
    private static final List<String> ICON_KEYS = List.of("\u270e", "\u2192", "\u21c4", "\u2715", "\u0394");

    /** Изолирует жизненный цикл toolkit и ограничивает время проверки реальных объектов JavaFX. */
    @Test void actualTextFlowUsesOnlyDeclaredUtf16Positions() throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-XX:-UsePerfData", "-cp", classpath, FxDecoratedTooltipTest.class.getName())
                .redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Tooltip probe timed out");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("decorated-tooltip-ok"), output);
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    /** Создаёт подсказки только в FX-потоке; отдельный процесс завершается даже при ошибке проверки. */
    public static void main(String[] args) {
        try {
            Platform.startup(() -> Platform.setImplicitExit(false));
            FutureTask<Void> task = new FutureTask<>(() -> {
                decoratedServiceLinesKeepUserText();
                plainServiceLikeTextHasNoGraphic();
                return null;
            });
            Platform.runLater(task);
            task.get(10, TimeUnit.SECONDS);
            Platform.exit();
            System.out.println("decorated-tooltip-ok");
            System.exit(0);
        } catch (Throwable error) {
            error.printStackTrace();
            System.exit(1);
        }
    }

    /** Повторённые строки заголовка и примечания остаются текстом, значки заменяют только явные позиции. */
    private static void decoratedServiceLinesKeepUserText() {
        String serviceText = String.join("\n", TEXT_KEYS.stream().map(UiText::get).toList());
        String title = "\ud83d\ude00\n" + serviceText;
        String prefix = title + "\n";
        String suffix = "\n" + UiText.get("table.tip.note", "\ud83d\ude80\n" + serviceText);
        String source = prefix + serviceText + suffix;
        List<DecoratedTooltip.IconPosition> positions = new ArrayList<>();
        int offset = prefix.length();
        assertEquals(prefix.codePointCount(0, prefix.length()) + 1, offset);
        for (int i = 0; i < TEXT_KEYS.size(); i++) {
            positions.add(new DecoratedTooltip.IconPosition(offset, ICON_KEYS.get(i)));
            offset += UiText.get(TEXT_KEYS.get(i)).length() + 1;
        }
        FxClassUsageProbe probe = new FxClassUsageProbe();
        // JavaFX: Tooltip → Swing: JToolTip → Web: div.tooltip
        Tooltip tip = FxStyles.tip(new DecoratedTooltip(source, positions), probe);
        assertEquals(source, tip.getText());
        assertEquals(1, probe.snapshot().get("Tooltip").intValue());
        assertEquals(ContentDisplay.GRAPHIC_ONLY, tip.getContentDisplay());
        TextFlow flow = assertInstanceOf(TextFlow.class, tip.getGraphic());
        assertEquals(source, flow.getAccessibleText());
        assertEquals(prefix, assertInstanceOf(Text.class, flow.getChildren().getFirst()).getText());
        assertTrue(assertInstanceOf(Text.class, flow.getChildren().getLast()).getText().endsWith(suffix));

        StringBuilder reconstructed = new StringBuilder();
        int iconIndex = 0;
        for (Node child : flow.getChildren()) {
            if (child instanceof Text text) {
                assertEquals(Color.web(ColorToken.TOOLTIP_TEXT.hex()), text.getFill());
                assertTrue(text.fontProperty().isBound());
                assertEquals(tip.getFont(), text.getFont());
                assertEquals(source.substring(reconstructed.length(), reconstructed.length() + text.getText().length()),
                        text.getText());
                reconstructed.append(text.getText());
            } else {
                ImageView image = assertInstanceOf(ImageView.class, child);
                assertTrue(iconIndex < positions.size(), "Unexpected user-text icon");
                DecoratedTooltip.IconPosition position = positions.get(iconIndex++);
                assertEquals(position.offset(), reconstructed.length());
                assertEquals(position.key(), image.getProperties().get("cp.icon"));
                assertEquals(DesignTokens.INLINE_ICON_SIZE, image.getFitWidth());
                assertEquals(DesignTokens.INLINE_ICON_SIZE, image.getFitHeight());
                assertTrue(image.isPreserveRatio());
                assertSharedPixels(position.key(), image.getImage());
                reconstructed.append(position.key());
            }
        }
        assertEquals(positions.size(), iconIndex);
        assertEquals(source, reconstructed.toString());
    }

    /** Даже полная служебная строка и одиночный ключ без отмеченных позиций не становятся изображением. */
    private static void plainServiceLikeTextHasNoGraphic() {
        for (String source : List.of("", "\u270e", "\ud83d\ude00\n"
                + String.join("\n", TEXT_KEYS.stream().map(UiText::get).toList()))) {
            // JavaFX: Tooltip → Swing: JToolTip → Web: div.tooltip
            Tooltip tip = FxStyles.tip(DecoratedTooltip.plain(source), new FxClassUsageProbe());
            assertEquals(source, tip.getText());
            assertNull(tip.getGraphic());
            assertEquals(ContentDisplay.LEFT, tip.getContentDisplay());
        }
    }

    /** Сравнивает пиксели узла с PNG ядра нужного цвета, независимо от кэша адаптера. */
    private static void assertSharedPixels(String key, Image actual) {
        Image expected = new Image(new ByteArrayInputStream(UiIcons.png(key, ColorToken.TOOLTIP_TEXT).orElseThrow()));
        assertFalse(expected.isError());
        assertFalse(actual.isError());
        assertEquals(expected.getWidth(), actual.getWidth());
        assertEquals(expected.getHeight(), actual.getHeight());
        assertNotNull(expected.getPixelReader());
        assertNotNull(actual.getPixelReader());
        for (int y = 0; y < (int) expected.getHeight(); y++)
            for (int x = 0; x < (int) expected.getWidth(); x++)
                assertEquals(expected.getPixelReader().getArgb(x, y), actual.getPixelReader().getArgb(x, y), key);
    }
}
