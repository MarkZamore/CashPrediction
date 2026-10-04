package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.text.ParseException;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.form.FieldSpecs;

/** Проверяет редактор чисел без окон, фокуса рабочего стола и хранилищ. */
class SwingSpinnerEditorTest {
    /** Ошибка подтверждения не заменяет введённый текст последним числом модели. */
    @Test void invalidCommitKeepsRawTextAndLastNumericValue() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JSpinner spinner = SwingFieldWidgets.spinner(FieldSpecs.spinner("horizonValue", "", 1, 600));
            JFormattedTextField editor = ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField();
            assertEquals(JFormattedTextField.COMMIT, editor.getFocusLostBehavior());
            spinner.setValue(12L);
            assertInstanceOf(Long.class, spinner.getValue());
            // Кодовые точки отличают настоящую букву из отчёта фокуса от ошибки декодирования отчёта.
            for (String raw : new String[]{"invalid", "", "-", "601", "9223372036854775808", "\u0438", "\uFFFD"}) {
                editor.setText(raw);
                String diagnostic = "raw code points=" + raw.codePoints().mapToObj(cp -> String.format("U+%04X", cp))
                        .collect(java.util.stream.Collectors.joining(" "));
                assertEquals(raw, editor.getText(), diagnostic);
                assertThrows(ParseException.class, spinner::commitEdit, diagnostic);
                assertEquals(raw, editor.getText(), diagnostic);
                assertEquals(12L, spinner.getValue(), diagnostic);
            }
        });
    }

    /** Подтверждение корректного текста и настоящие обработчики стрелок сохраняют числовую модель. */
    @Test void validCommitAndArrowButtonsUseEditedNumber() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JSpinner spinner = SwingFieldWidgets.spinner(FieldSpecs.spinner("horizonValue", "", 1, 600));
            JFormattedTextField editor = ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField();
            editor.setText("24");
            assertDoesNotThrow(spinner::commitEdit);
            assertEquals(24L, spinner.getValue());
            assertEquals("24", editor.getText());
            assertInstanceOf(Long.class, spinner.getValue());
            editor.setText("30");
            arrow(spinner, "Spinner.nextButton").doClick(0);
            assertEquals(31L, spinner.getValue());
            assertEquals("31", editor.getText());
            arrow(spinner, "Spinner.previousButton").doClick(0);
            assertEquals(30L, spinner.getValue());
            assertEquals("30", editor.getText());
        });
    }

    /** Находит штатную кнопку спиннера, чтобы проверить её обработчик, а не подменять шаг setValue. */
    static JButton arrow(JSpinner spinner, String name) {
        for (var child : spinner.getComponents()) {
            if (child instanceof JButton button && name.equals(button.getName())) return button;
        }
        throw new AssertionError("Missing spinner button: " + name);
    }
}
