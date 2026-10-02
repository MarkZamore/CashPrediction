package ru.cashprediction.core.ui.form;

import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.forms.ops.QuickEditForm;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.core.ui.text.UiText;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет независимые единицы ширины поля и общий контракт быстрой правки. */
class FieldWidthLayoutTest {
    /** Пиксели не превращаются в количество символов при копировании описания. */
    @Test void copiesRetainPixelWidth() {
        var field = FieldSpecs.withWidthPx(FieldSpecs.money("amount", ""), 140);
        for (var copy : java.util.List.of(field, FieldSpecs.wide(field), FieldSpecs.focused(field),
                FieldSpecs.withTooltip(field, "hint"), FieldSpecs.withPrompt(field, "prompt"),
                FieldSpecs.withSuffix(field, "RUB"), FieldSpecs.withColumns(field, 12))) {
            assertEquals(140, copy.widthPx());
        }
        assertEquals(0, field.columns());
        assertEquals(12, FieldSpecs.withColumns(field, 12).columns());
    }

    /** Необязательное свойство появляется в протоколе только при явном задании. */
    @Test void protocolPreservesTheDefaultContract() {
        var field = FieldSpecs.money("amount", "");
        assertEquals(0, field.widthPx());
        assertFalse(((Map<?, ?>) UiJson.toTree(field)).containsKey("widthPx"));
        assertEquals(140, ((Map<?, ?>) UiJson.toTree(FieldSpecs.withWidthPx(field, 140))).get("widthPx"));
        assertThrows(IllegalArgumentException.class, () -> FieldSpecs.withWidthPx(field, -1));
    }

    /** Все клиенты получают ширину и постоянную подсказку из одной формы ядра. */
    @Test void quickEditDeclaresPixelsAndHint() {
        var spec = new QuickEditForm().spec(null);
        assertEquals(320, spec.width());
        var rows = spec.pages().getFirst().rows();
        var field = ((FormRow.Field) rows.getFirst()).field();
        assertEquals(140, field.widthPx());
        assertEquals(0, field.columns());
        assertEquals(UiText.get("quick.hint"), ((FormRow.Hint) rows.get(1)).text());
    }
}
