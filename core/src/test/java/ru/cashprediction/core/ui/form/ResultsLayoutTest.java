package ru.cashprediction.core.ui.form;

import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.core.ui.forms.plan.GoalCalculatorForm;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет общий резерв высоты результатов и передачу правила тонкому клиенту. */
class ResultsLayoutTest {
    /** Резерв является частью модели, а не отдельным правилом браузера. */
    @Test void minimumLinesReachTheProtocol() {
        var row = new FormRow.Results("results", 3);
        assertEquals(Map.of("kind", "Results", "id", "results", "minLines", 3), UiJson.toTree(row));
    }

    /** Старый конструктор не навязывает резерв остальным формам. */
    @Test void defaultIsZeroAndInvalidValuesAreRejected() {
        assertEquals(0, new FormRow.Results("results").minLines());
        assertThrows(IllegalArgumentException.class, () -> new FormRow.Results("results", -1));
        assertThrows(NullPointerException.class, () -> new FormRow.Results(null, 3));
    }

    /** Все отрисовщики получают правило калькулятора из одной формы ядра. */
    @Test void goalCalculatorDeclaresThreeReservedLines() {
        var blocks = new GoalCalculatorForm().spec(null).pages().stream()
                .flatMap(page -> page.rows().stream())
                .filter(FormRow.Results.class::isInstance)
                .map(FormRow.Results.class::cast).toList();
        assertEquals(1, blocks.size());
        assertEquals(3, blocks.getFirst().minLines());
    }
}
