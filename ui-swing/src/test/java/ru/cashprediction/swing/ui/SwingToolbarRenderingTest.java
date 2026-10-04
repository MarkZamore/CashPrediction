package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.UiIntents;
import ru.cashprediction.core.ui.command.CommandArgs;
import ru.cashprediction.core.ui.command.CommandId;
import ru.cashprediction.core.ui.command.InvokeSource;
import ru.cashprediction.core.ui.menu.Emphasis;
import ru.cashprediction.core.ui.menu.ToolbarModel;
import ru.cashprediction.core.ui.menu.ToolbarNode;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.token.ColorToken;
import ru.cashprediction.core.ui.token.DesignTokens;
import ru.cashprediction.core.ui.token.UiIcons;

/** Проверяет все пиксели настоящих кнопок и подсказки без запуска окон и без допусков. */
class SwingToolbarRenderingTest {
    /** Все три самостоятельных действия целиком рисуют общий PNG в обоих состояниях. */
    @Test void completeStandaloneIconsMatchCorePixelsAndKeepSemantics() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            SwingToolbar toolbar = new SwingToolbar(null);
            toolbar.render(model("sample"));
            layout(toolbar);
            List<AbstractButton> buttons = List.of((AbstractButton) toolbar.widget("tb.undo"),
                    (AbstractButton) toolbar.widget("tb.redo"), clear(toolbar));
            List<String> keys = List.of(UiText.get("toolbar.tb.undo"), UiText.get("toolbar.tb.redo"), "✕");
            for (int index = 0; index < buttons.size(); index++) {
                AbstractButton button = buttons.get(index);
                String key = keys.get(index);
                assertEquals(28, button.getWidth()); assertEquals(28, button.getHeight());
                assertEquals(new Insets(6, 6, 6, 6), button.getInsets());
                assertEquals("", button.getText());
                assertEquals(key, button.getAccessibleContext().getAccessibleName());
                assertEquals(key, button.getClientProperty("cp.text"));
                assertFalse(button.isFocusable());
                for (boolean enabled : new boolean[]{true, false, true}) {
                    button.setEnabled(enabled);
                    assertEquals(16, button.getIcon().getIconWidth());
                    assertEquals(16, button.getIcon().getIconHeight());
                    assertEquals(16, button.getDisabledIcon().getIconWidth());
                    assertEquals(16, button.getDisabledIcon().getIconHeight());
                    assertArrayEquals(pixels(expectedButton(button, key, enabled)), pixels(paint(button)), key + ":" + enabled);
                    if (index < 2) {
                        var item = SwingUiDumper.toolbar(toolbar).items().get(index);
                        assertEquals(key, item.text()); assertEquals(enabled, item.enabled());
                    }
                }
            }
        });
    }

    /** PNG может оставаться центрированным при неверных Insets: геометрия проверяется независимо. */
    @Test void oldTwentyPixelPaddingIsRejectedByGeometryOracle() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install(); SwingToolbar toolbar = new SwingToolbar(null);
            toolbar.render(model("sample")); layout(toolbar);
            AbstractButton undo = (AbstractButton) toolbar.widget("tb.undo");
            int[] expected = pixels(expectedButton(undo, UiText.get("toolbar.tb.undo"), true));
            assertArrayEquals(expected, pixels(paint(undo)));
            assertEquals(new Insets(6, 6, 6, 6), undo.getInsets());
            undo.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(SwingLook.color(ColorToken.BORDER)),
                    BorderFactory.createEmptyBorder(3, 9, 3, 9)));
            assertThrows(AssertionError.class,
                    () -> assertEquals(new Insets(6, 6, 6, 6), undo.getInsets()));
        });
    }

    /** Подсказка берётся из модели, исчезает при вводе и возвращается после очистки без текста в документе. */
    @Test void emptyPopulatedAndClearedPromptMatchesActualPixelsWithoutDocumentWrites() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install(); List<String> submitted = new ArrayList<>();
            UiIntents intents = (UiIntents) Proxy.newProxyInstance(UiIntents.class.getClassLoader(),
                    new Class<?>[]{UiIntents.class}, (proxy, method, args) -> {
                        if (method.getName().equals("filterText")) submitted.add((String) args[0]);
                        return null;
                    });
            SwingToolbar toolbar = new SwingToolbar(intents);
            toolbar.render(model("")); layout(toolbar);
            assertFalse(clear(toolbar).isVisible());
            assertPromptPixels(toolbar.filter(), true);
            assertEquals("", toolbar.filter().getText());
            assertTrue(submitted.isEmpty());

            JTextField same = toolbar.filter();
            var document = same.getDocument();
            same.setText("sample");
            assertSame(same, toolbar.filter()); assertSame(document, toolbar.filter().getDocument());
            assertPromptPixels(toolbar.filter(), false);
            assertEquals("sample", toolbar.filter().getText());
            assertEquals("sample", SwingUiDumper.toolbar(toolbar).items().get(2).text());
            same.setText("");
            assertSame(same, toolbar.filter()); assertSame(document, toolbar.filter().getDocument());
            assertFalse(clear(toolbar).isVisible());
            assertPromptPixels(toolbar.filter(), true);
            var dump = SwingUiDumper.toolbar(toolbar).items().get(2);
            assertEquals("", dump.text()); assertEquals(UiText.get("toolbar.tb.filter.prompt"), dump.prompt());
            assertEquals(UiText.get("toolbar.tb.filter.tip"), dump.tooltip());
            assertEquals(220, toolbar.filter().getWidth());
            assertTrue(submitted.isEmpty());
            same.postActionEvent();
            assertEquals(List.of(""), submitted);
        });
    }

    /** Модель повторно задаёт доступность, подсказки и цвета; отключённые кнопки не передают команду. */
    @Test void rerenderedModelsDispatchOnlyEnabledUndoRedoWithLocalizedAccessibility() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install(); Recorder recorder = new Recorder();
            SwingToolbar toolbar = new SwingToolbar(recorder.intents);
            for (int state = 0; state < 3; state++) {
                boolean undoEnabled = state != 1, redoEnabled = state == 1;
                String undoTip = state == 0 ? UiText.get("toolbar.tb.undo.tip")
                        : UiText.get("toolbar.tb.undo.tip.named", "sample");
                String redoTip = state == 0 ? UiText.get("toolbar.tb.redo.tip")
                        : UiText.get("toolbar.tb.redo.tip.named", "sample");
                toolbar.render(model("sample", undoEnabled, redoEnabled, undoTip, redoTip)); layout(toolbar);
                assertEquals(3, SwingUiDumper.toolbar(toolbar).items().size());
                for (String id : List.of("tb.undo", "tb.redo")) {
                    AbstractButton button = (AbstractButton) toolbar.widget(id);
                    boolean undo = id.equals("tb.undo");
                    boolean enabled = undo ? undoEnabled : redoEnabled;
                    String tip = undo ? undoTip : redoTip;
                    String glyph = UiText.get(undo ? "toolbar.tb.undo" : "toolbar.tb.redo");
                    assertEquals(enabled, button.isEnabled());
                    assertTipAndAccessibility(button, glyph, tip);
                    assertArrayEquals(pixels(expectedButton(button, glyph, enabled)), pixels(paint(button)));
                    var dump = SwingUiDumper.toolbar(toolbar).items().get(undo ? 0 : 1);
                    assertEquals(enabled, dump.enabled()); assertEquals(tip, dump.tooltip());
                    recorder.events.clear(); button.doClick(0);
                    assertEquals(enabled ? List.of(command(undo ? CommandId.EDIT_UNDO : CommandId.EDIT_REDO,
                            InvokeSource.TOOLBAR)) : List.of(), recorder.events);
                }
                assertTipAndAccessibility(clear(toolbar), "✕", UiText.get("toolbar.tb.filter.clear.tip"));
                recorder.events.clear(); clear(toolbar).doClick(0);
                assertEquals(List.of(filterEvent("")), recorder.events);
            }
        });
    }

    /** Дамп читает изменённое свойство виджета и настоящий текст при отсутствующем или чужом типе свойства. */
    @Test void dumperSemanticMutationAndFallbackRejectCachedModelOrVisibleEmptyText() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install(); SwingToolbar toolbar = new SwingToolbar(new Recorder().intents);
            toolbar.render(model("")); layout(toolbar);
            AbstractButton undo = (AbstractButton) toolbar.widget("tb.undo");
            assertEquals(UiText.get("toolbar.tb.undo"), SwingUiDumper.toolbar(toolbar).items().get(0).text());
            undo.putClientProperty("cp.text", "mutated"); undo.setText("fallback");
            assertEquals("mutated", SwingUiDumper.toolbar(toolbar).items().get(0).text());
            undo.putClientProperty("cp.text", null);
            assertEquals("fallback", SwingUiDumper.toolbar(toolbar).items().get(0).text());
            undo.putClientProperty("cp.text", 7);
            assertEquals("fallback", SwingUiDumper.toolbar(toolbar).items().get(0).text());
            undo.setText("");
            assertEquals("", SwingUiDumper.toolbar(toolbar).items().get(0).text());
            assertEquals(List.of("tb.undo", "tb.redo", "tb.filter"),
                    SwingUiDumper.toolbar(toolbar).items().stream().map(item -> item.id()).toList());
            assertEquals("", SwingUiDumper.toolbar(toolbar).items().get(2).text());
            assertEquals(UiText.get("toolbar.tb.filter.prompt"), SwingUiDumper.toolbar(toolbar).items().get(2).prompt());
        });
    }

    /** Настоящий документ запускает однократную задержку; Enter передаёт текст прежде команды и отменяет таймер. */
    @Test void sameDocumentDebounceDeletionAndEnterDeliverRealIntentsOnceInOrder() throws Exception {
        Recorder recorder = new Recorder(); SwingToolbar[] owner = new SwingToolbar[1];
        JTextField[] same = new JTextField[1]; javax.swing.text.Document[] document = new javax.swing.text.Document[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                SwingLook.install(); owner[0] = new SwingToolbar(recorder.intents);
                owner[0].render(model("")); layout(owner[0]);
                same[0] = owner[0].filter(); document[0] = same[0].getDocument();
                assertPromptPixels(same[0], true);
                same[0].replaceSelection("sam"); same[0].replaceSelection("ple");
                assertEquals("sample", same[0].getText()); assertTrue(recorder.events.isEmpty());
                assertPromptPixels(same[0], false);
            });
            assertTrue(recorder.delivered.await(3, TimeUnit.SECONDS));
            waitPastDebounce();
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(List.of(filterEvent("sample")), recorder.events);
                recorder.events.clear(); recorder.delivered = new CountDownLatch(1);
                same[0].setText("");
                assertSame(same[0], owner[0].filter()); assertSame(document[0], same[0].getDocument());
                assertPromptPixels(same[0], true); assertTrue(recorder.events.isEmpty());
            });
            assertTrue(recorder.delivered.await(3, TimeUnit.SECONDS));
            waitPastDebounce();
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(List.of(filterEvent("")), recorder.events); recorder.events.clear();
                same[0].replaceSelection("enter"); same[0].postActionEvent();
                assertEquals(List.of(filterEvent("enter"), command(CommandId.FILTER_FOCUS_TABLE, InvokeSource.MAIN)),
                        recorder.events);
            });
            waitPastDebounce();
            SwingUtilities.invokeAndWait(() -> assertEquals(
                    List.of(filterEvent("enter"), command(CommandId.FILTER_FOCUS_TABLE, InvokeSource.MAIN)), recorder.events));
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (owner[0] != null) owner[0].render(model("")); });
        }
    }

    /** Применение модели отменяет старый ввод, а очистка передаёт ровно пустую строку без позднего повтора. */
    @Test void modelRerenderAndClearCancelPendingDocumentDelivery() throws Exception {
        Recorder recorder = new Recorder(); SwingToolbar[] owner = new SwingToolbar[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                SwingLook.install(); owner[0] = new SwingToolbar(recorder.intents);
                owner[0].render(model("")); owner[0].filter().setText("stale");
                owner[0].render(model("sample")); layout(owner[0]);
                assertTrue(recorder.events.isEmpty()); assertTrue(clear(owner[0]).isVisible());
                assertPromptPixels(owner[0].filter(), false);
            });
            waitPastDebounce();
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(recorder.events.isEmpty()); owner[0].filter().setText("pending");
                clear(owner[0]).doClick(0); assertEquals(List.of(filterEvent("")), recorder.events);
                owner[0].render(model("")); layout(owner[0]);
                assertFalse(clear(owner[0]).isVisible()); assertPromptPixels(owner[0].filter(), true);
            });
            waitPastDebounce();
            SwingUtilities.invokeAndWait(() -> assertEquals(List.of(filterEvent("")), recorder.events));
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (owner[0] != null) owner[0].render(model("")); });
        }
    }

    /** Проверяет точную локализованную подсказку и непустые доступные имя и описание реального контрола. */
    private static void assertTipAndAccessibility(AbstractButton button, String name, String tip) {
        assertFalse(name.isBlank()); assertFalse(tip.isBlank());
        assertEquals(name, button.getAccessibleContext().getAccessibleName());
        assertEquals(tip, button.getAccessibleContext().getAccessibleDescription());
        assertEquals(tip, button.getClientProperty("cp.tooltip"));
        assertEquals(SwingLook.tooltipHtml(tip), button.getToolTipText());
    }

    /** Даёт настоящему Swing Timer время повториться при ошибке, не блокируя поток интерфейса. */
    private static void waitPastDebounce() throws Exception {
        CountDownLatch barrier = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            Timer timer = new Timer(650, event -> barrier.countDown()); timer.setRepeats(false); timer.start();
        });
        assertTrue(barrier.await(3, TimeUnit.SECONDS));
    }

    /** Сохраняет точный метод и аргументы намерения для проверки порядка и числа отправок. */
    private record IntentEvent(String method, List<Object> args) { }

    /** Записывает вызовы настоящих обработчиков; список читается и изменяется только в EDT. */
    private static final class Recorder {
        final List<IntentEvent> events = new ArrayList<>();
        volatile CountDownLatch delivered = new CountDownLatch(1);
        final UiIntents intents = (UiIntents) Proxy.newProxyInstance(UiIntents.class.getClassLoader(),
                new Class<?>[]{UiIntents.class}, (proxy, method, args) -> {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    assertTrue(method.getName().equals("filterText") || method.getName().equals("command"));
                    events.add(new IntentEvent(method.getName(), List.copyOf(Arrays.asList(args))));
                    if (method.getName().equals("filterText")) delivered.countDown();
                    return null;
                });
    }

    /** Создаёт ожидание передачи текста без подстановки подсказки в документ. */
    private static IntentEvent filterEvent(String text) { return new IntentEvent("filterText", List.of(text)); }

    /** Создаёт ожидание команды с пустыми аргументами и точным источником. */
    private static IntentEvent command(CommandId id, InvokeSource source) {
        return new IntentEvent("command", List.of(id, CommandArgs.NONE, source));
    }

    /** Рисование подсказки сравнивается с обычным полем и не порождает событий документа. */
    private static void assertPromptPixels(JTextField field, boolean empty) {
        int[] changes = {0};
        DocumentListener listener = new DocumentListener() {
            /** Считает вставки, чтобы рисование не могло добавить подсказку в документ. */
            @Override public void insertUpdate(DocumentEvent event) { changes[0]++; }
            /** Считает удаления, чтобы рисование не могло временно заменить настоящий ввод. */
            @Override public void removeUpdate(DocumentEvent event) { changes[0]++; }
            /** Считает изменения атрибутов документа при рисовании. */
            @Override public void changedUpdate(DocumentEvent event) { changes[0]++; }
        };
        field.getDocument().addDocumentListener(listener);
        try {
            String text = field.getText();
            String accessible = field.getAccessibleContext().getAccessibleName();
            String prompt = (String) field.getClientProperty("cp.prompt");
            assertEquals(UiText.get("toolbar.tb.filter.prompt"), prompt);
            assertEquals(prompt, accessible); assertFalse(accessible.isBlank());
            assertEquals(UiText.get("toolbar.tb.filter.tip"), field.getAccessibleContext().getAccessibleDescription());
            BufferedImage actual = paint(field);
            JTextField plain = new JTextField(text);
            plain.setFont(field.getFont()); plain.setForeground(field.getForeground());
            plain.setBackground(field.getBackground()); plain.setBorder(field.getBorder());
            plain.setMargin(field.getMargin()); plain.setSize(field.getSize());
            BufferedImage expected = paint(plain);
            int[] withoutPrompt = pixels(expected);
            if (empty) {
                Graphics2D graphics = expected.createGraphics();
                try {
                    Insets insets = plain.getInsets();
                    graphics.clipRect(insets.left, insets.top, plain.getWidth() - insets.left - insets.right,
                            plain.getHeight() - insets.top - insets.bottom);
                    graphics.setFont(plain.getFont()); graphics.setColor(SwingLook.color(ColorToken.TEXT_MUTED));
                    FontMetrics metrics = graphics.getFontMetrics();
                    int baseline = insets.top + (plain.getHeight() - insets.top - insets.bottom
                            - metrics.getHeight()) / 2 + metrics.getAscent();
                    graphics.drawString(prompt, insets.left, baseline);
                } finally { graphics.dispose(); }
                assertFalse(Arrays.equals(withoutPrompt, pixels(actual)));
            }
            assertArrayEquals(pixels(expected), pixels(actual));
            field.putClientProperty("cp.prompt", "");
            try {
                if (empty) assertFalse(Arrays.equals(pixels(actual), pixels(paint(field))));
                else assertArrayEquals(pixels(actual), pixels(paint(field)));
            } finally { field.putClientProperty("cp.prompt", prompt); }
            assertEquals(text, field.getText());
            assertEquals(accessible, field.getAccessibleContext().getAccessibleName());
            assertEquals(0, changes[0]);
        } finally { field.getDocument().removeDocumentListener(listener); }
    }

    /** Эталон складывает чистый фон настоящей кнопки и полный PNG из физических байтов ядра. */
    private static BufferedImage expectedButton(AbstractButton button, String key, boolean enabled) {
        Icon icon = button.getIcon(), disabled = button.getDisabledIcon();
        BufferedImage expected;
        try {
            button.setIcon(null); button.setDisabledIcon(null);
            expected = paint(button);
        } finally { button.setIcon(icon); button.setDisabledIcon(disabled); }
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(
                    UiIcons.png(key, enabled ? ColorToken.TEXT_PRIMARY : ColorToken.TEXT_MUTED).orElseThrow()));
            ImageIcon reference = new ImageIcon(source.getScaledInstance(16, 16, Image.SCALE_SMOOTH));
            Graphics2D graphics = expected.createGraphics();
            try { reference.paintIcon(button, graphics, 6, 6); } finally { graphics.dispose(); }
        } catch (java.io.IOException exception) { throw new AssertionError(exception); }
        return expected;
    }

    /** Создаёт модель с общими локализованными подписями и неизменными размерами фильтра. */
    private static ToolbarModel model(String text) {
        return model(text, true, false, UiText.get("toolbar.tb.undo.tip"), UiText.get("toolbar.tb.redo.tip"));
    }

    /** Передаёт доступность и подсказки исключительно через модель при каждом повторном отображении. */
    private static ToolbarModel model(String text, boolean undoEnabled, boolean redoEnabled, String undoTip, String redoTip) {
        return new ToolbarModel(List.of(
                new ToolbarNode.Button("tb.undo", CommandId.EDIT_UNDO, UiText.get("toolbar.tb.undo"),
                        undoTip, undoEnabled, Emphasis.NONE),
                new ToolbarNode.Button("tb.redo", CommandId.EDIT_REDO, UiText.get("toolbar.tb.redo"),
                        redoTip, redoEnabled, Emphasis.NONE),
                new ToolbarNode.FilterField("tb.filter", text, UiText.get("toolbar.tb.filter.prompt"),
                        UiText.get("toolbar.tb.filter.tip"), 220, 300, !text.isEmpty(),
                        UiText.get("toolbar.tb.filter.clear.tip"))));
    }

    /** Измеряет настоящую кнопку очистки внутри поля фильтра. */
    private static AbstractButton clear(SwingToolbar toolbar) {
        JPanel panel = (JPanel) toolbar.widget("tb.filter");
        return Arrays.stream(panel.getComponents()).filter(AbstractButton.class::isInstance)
                .map(AbstractButton.class::cast).findFirst().orElseThrow();
    }

    /** Выполняет только раскладку лёгких компонентов без окна. */
    private static void layout(SwingToolbar toolbar) {
        toolbar.setSize(600, DesignTokens.TOOLBAR_HEIGHT); toolbar.doLayout();
        ((JPanel) toolbar.widget("tb.filter")).doLayout();
    }

    /** Получает реальное полное изображение компонента с его фактическими размерами. */
    private static BufferedImage paint(JComponent component) {
        BufferedImage image = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try { component.paint(graphics); } finally { graphics.dispose(); }
        return image;
    }

    /** Возвращает все пиксели без обрезки значка и без ослабления сравнения. */
    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }
}
