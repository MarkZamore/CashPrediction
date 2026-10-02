package ru.cashprediction.core.ui.selftest;

import java.util.List;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.forms.plan.HorizonFields;
import static org.junit.jupiter.api.Assertions.*;

/** Радиогруппа в нескольких строках не дублирует id дампа и не теряет вариант «до даты». */
class LogicalRadioFieldsTest {
    /** Горизонт остаётся тремя логическими полями с вариантами в порядке раскладки. */
    @Test void horizonFragmentsBecomeOneLogicalRadioGroup() {
        var fields = ModelUiDriver.fields(new FormPage("horizon", HorizonFields.rows()));
        assertEquals(List.of("horizonValue", "horizonKind", "horizonUntil"), fields.stream().map(FieldSpec::id).toList());
        var radio = fields.get(1);
        assertEquals(FieldKind.RADIO, radio.kind());
        assertEquals(List.of("MONTHS", "YEARS", "UNTIL"), radio.options().stream().map(Option::value).toList());
        assertEquals(3, radio.options().stream().map(Option::text).distinct().count());
        assertEquals(4, HorizonFields.rows().stream().mapToInt(row -> ((FormRow.Inline) row).fields().size()).sum());
    }

    /** Повтор обычного поля не подменяется последним виджетом. */
    @Test void duplicateNonRadioIsRejected() {
        var text = FieldSpecs.text("name", "Name", "");
        assertThrows(IllegalArgumentException.class, () -> ModelUiDriver.fields(new FormPage("bad",
                List.of(new FormRow.Field(text), new FormRow.Field(text)))));
    }

    /** Одинаковый вариант в двух фрагментах означал бы два переключателя одного значения. */
    @Test void repeatedRadioOptionIsRejected() {
        var radio = FieldSpecs.radio("kind", "", Orientation.HORIZONTAL, List.of(Option.of("a", "A")));
        assertThrows(IllegalArgumentException.class, () -> ModelUiDriver.fields(new FormPage("bad",
                List.of(new FormRow.Field(radio), new FormRow.Field(radio)))));
    }

    /** Совпадение id у разных видов не считается составной радиогруппой. */
    @Test void mixedKindsAreRejected() {
        var radio = FieldSpecs.radio("kind", "", Orientation.HORIZONTAL, List.of(Option.of("a", "A")));
        assertThrows(IllegalArgumentException.class, () -> ModelUiDriver.fields(new FormPage("bad",
                List.of(new FormRow.Field(radio), new FormRow.Field(FieldSpecs.text("kind", "Name", ""))))));
    }
}
