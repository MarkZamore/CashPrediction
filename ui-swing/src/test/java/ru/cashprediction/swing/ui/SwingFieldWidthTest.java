package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.BorderLayout;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Map;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.forms.ops.QuickEditForm;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.token.DesignTokens;

/** Проверяет реальную пиксельную ширину общих контролов без показа окон и событий Robot. */
class SwingFieldWidthTest {
    @TempDir Path directory;

    @Test void actualBindingUsesGenericWidthAndQuickFormMetadataWithoutShowingWindow() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            var env = AppEnvironment.from(LaunchOptions.parse("--home", directory.toString(), "--registry", "memory", "--selftest", "s12-quick-edit"));
            var port = new SwingUiPort(env); var app = new AppController(port, env); port.bind(app);
            var host = (FormSession.Host) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{FormSession.Host.class}, (proxy, method, args) -> null);
            var session = new FormSession(WindowType.QUICK_EDIT_POPUP, false, new QuickEditForm(),
                    new FormContext("width-test", "main", Map.of(), app.state()), host);
            // JavaFX: Popup → Swing: SwingQuickEditPopup → Web: div быстрой правки
            var form = new SwingQuickEditPopup(port, session, session.spec(), session.view(), Placement.centered("main"));
            session.attach(form);
            try {
                for (FieldSpec spec : new FieldSpec[]{
                        FieldSpecs.withWidthPx(FieldSpecs.money("money-width", ""), 173),
                        FieldSpecs.withWidthPx(FieldSpecs.choice("choice-width", "", java.util.List.of()), 191),
                        FieldSpecs.text("default-width", "", "")}) {
                    var binding = SwingFieldWidgets.create(spec, form);
                    binding.root.setSize(420, DesignTokens.CONTROL_HEIGHT); layout(binding.root);
                    assertEquals(spec.widthPx() > 0 ? spec.widthPx() : 420, binding.input.getWidth());
                }
                form.sizeContent(); form.dialog.pack(); layout(form.content);
                assertEquals(form.spec.width(), form.content.getWidth());
                var fixed = form.fields.values().stream().flatMap(java.util.List::stream).filter(binding -> binding.spec.widthPx() > 0).toList();
                assertFalse(fixed.isEmpty());
                for (var binding : fixed) {
                    assertEquals(binding.spec.widthPx(), binding.input.getWidth());
                    var inputRect = SwingUtilities.convertRectangle(binding.input.getParent(), binding.input.getBounds(), form.content);
                    assertTrue(inputRect.x >= form.content.getInsets().left);
                    assertTrue(inputRect.x + inputRect.width <= form.content.getWidth() - form.content.getInsets().right);
                    System.out.println("actual quick binding " + binding.spec.id() + " width=" + binding.input.getWidth());
                }
                assertTrue(form.problem.isVisible());
                var hint = form.hints.getFirst();
                int fieldY = SwingUtilities.convertPoint(fixed.getFirst().input, 0, fixed.getFirst().input.getHeight(), form.content).y;
                int errorY = SwingUtilities.convertPoint(form.problem, 0, 0, form.content).y;
                int errorBottom = SwingUtilities.convertPoint(form.problem, 0, form.problem.getHeight(), form.content).y;
                int hintY = SwingUtilities.convertPoint(hint, 0, 0, form.content).y;
                assertTrue(fieldY <= errorY); assertTrue(errorBottom <= hintY);
                for (var label : new JLabel[]{form.header, form.problem, hint}) {
                    var rect = SwingUtilities.convertRectangle(label.getParent(), label.getBounds(), form.content);
                    assertTrue(rect.x >= form.content.getInsets().left);
                    assertTrue(rect.x + rect.width <= form.content.getWidth() - form.content.getInsets().right);
                    assertTrue(label.getPreferredSize().height <= label.getHeight());
                }
                System.out.println("actual popup content=" + form.content.getSize() + " fieldBottom=" + fieldY
                        + " errorY=" + errorY + " errorBottom=" + errorBottom + " hintY=" + hintY);
                int invalidHeight = form.content.getHeight();
                session.fieldChanged("amount", "1000", true, 1); form.sizeContent(); form.dialog.pack(); layout(form.content);
                assertEquals(form.spec.width(), form.content.getWidth());
                assertFalse(form.problem.isVisible()); assertTrue(form.content.getHeight() < invalidHeight);
                assertEquals(140, fixed.getFirst().input.getWidth());
                System.out.println("actual popup valid content=" + form.content.getSize() + " invalidHeight=" + invalidHeight);
                assertFalse(form.dialog.isVisible());
            } finally { form.close(); port.exit(ExitKind.CLEAN, 0); }
        });
    }

    /** Раскладывает настоящие контейнеры без показа окна. */
    private static void layout(Container parent) {
        parent.doLayout();
        for (var child : parent.getComponents()) if (child instanceof Container nested) layout(nested);
    }

    @Test void positiveWidthWinsOverColumnsAndStretchForTextAndChoice() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            var spec = FieldSpecs.withWidthPx(FieldSpecs.withColumns(FieldSpecs.text("field", "", ""), 40), 140);
            for (JComponent control : new JComponent[]{new JTextField(spec.columns()),
                    new JComboBox<>(new String[]{UiText.get("button.save")})}) {
                var holder = SwingFieldWidgets.controlWithWidth(control, spec.widthPx());
                var row = new JPanel(new BorderLayout()); row.add(holder);
                for (int available : new int[]{260, 420}) {
                    row.setSize(available, DesignTokens.CONTROL_HEIGHT); row.doLayout(); holder.doLayout();
                    assertEquals(available, holder.getWidth()); assertEquals(140, control.getWidth());
                    assertEquals(DesignTokens.CONTROL_HEIGHT, control.getHeight()); assertEquals(0, control.getX());
                }
                System.out.println("actual " + control.getClass().getSimpleName() + " width=" + control.getWidth()
                        + " row=" + row.getWidth() + " height=" + control.getHeight());
            }
        });
    }

    @Test void zeroWidthKeepsOrdinaryStretchAndIdentity() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var control = new JTextField(); var holder = SwingFieldWidgets.controlWithWidth(control, 0);
            assertSame(control, holder);
            var row = new JPanel(new BorderLayout()); row.add(holder);
            row.setSize(420, DesignTokens.CONTROL_HEIGHT); row.doLayout();
            assertEquals(420, control.getWidth());
        });
    }

    @Test void scrollControlKeepsWidthAndLiveContentHeight() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var text = new JTextArea(3, 40); var control = new JScrollPane(text);
            var holder = SwingFieldWidgets.controlWithWidth(control, 180);
            assertEquals(control.getPreferredSize().height, holder.getPreferredSize().height);
            int oldHeight = holder.getPreferredSize().height;
            text.setRows(6); assertTrue(holder.getPreferredSize().height > oldHeight);
            holder.setSize(400, holder.getPreferredSize().height); holder.doLayout(); control.doLayout();
            assertEquals(180, control.getWidth()); assertEquals(holder.getHeight(), control.getHeight());
        });
    }
}
