package ru.cashprediction.core.ui.selftest;

import java.util.List;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.forms.ops.QuickEditForm;
import static org.junit.jupiter.api.Assertions.*;

/** Независимая регрессия сохранения пиксельной ширины при сборке логических полей модельного драйвера. */
final class LogicalFieldWidthReviewTest {
    /** Объединение двух частей RADIO должно сохранить заданную ширину исходного логического поля. */
    @Test void mergingRadioFragmentsPreservesExplicitWidth() {
        var first = FieldSpecs.withWidthPx(FieldSpecs.radio("choice", "", Orientation.HORIZONTAL,
                List.of(Option.of("A", "A"))), 140);
        var second = FieldSpecs.withWidthPx(FieldSpecs.radio("choice", "", Orientation.HORIZONTAL,
                List.of(Option.of("B", "B"))), 140);
        var fields = ModelUiDriver.fields(new FormPage("main", List.of(new FormRow.Field(first), new FormRow.Field(second))));
        assertEquals(1, fields.size());
        assertEquals(List.of("A", "B"), fields.getFirst().options().stream().map(Option::value).toList());
        assertEquals(140, fields.getFirst().widthPx());
    }

    /** Поле текущей быстрой правки проходит без объединения и сохраняет ширину и приоритет пикселей. */
    @Test void currentQuickEditFieldDoesNotLoseItsWidth() {
        var page = new QuickEditForm().spec(null).pages().getFirst();
        var original = ((FormRow.Field) page.rows().getFirst()).field();
        var logical = ModelUiDriver.fields(page).getFirst();
        assertSame(original, logical);
        assertEquals(140, logical.widthPx());
        assertEquals(0, logical.columns());
        assertTrue(logical.focusFirst());
    }
}
