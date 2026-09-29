package ru.cashprediction.core.ui.forms.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.ui.form.FormState;

/** Проверяет общие поля горизонта форм плана. */
class HorizonFieldsTest {

    /** «До даты» выключает счётчик и требует дату. */
    @Test
    void untilUsesDateAndValidatesIt() {
        FormState empty = new FormState(0, Map.of(HorizonFields.KIND, "UNTIL", HorizonFields.VALUE, "12", HorizonFields.UNTIL, ""));
        assertFalse(HorizonFields.views(empty).get(HorizonFields.VALUE).enabled());
        assertTrue(HorizonFields.views(empty).get(HorizonFields.UNTIL).enabled());
        assertTrue(HorizonFields.error(empty, LocalDate.of(2026, 1, 1)).isPresent());

        FormState valid = new FormState(0, Map.of(HorizonFields.KIND, "UNTIL", HorizonFields.VALUE, "12", HorizonFields.UNTIL, "2027-01-01"));
        assertEquals(new Horizon.Until(LocalDate.of(2027, 1, 1)), HorizonFields.toHorizon(valid));
    }

    /** Годы имеют собственный предел и длинный горизонт выдаёт предупреждение. */
    @Test
    void yearsHaveOwnRangeAndLongWarning() {
        FormState state = new FormState(0, Map.of(HorizonFields.KIND, "YEARS", HorizonFields.VALUE, "21", HorizonFields.UNTIL, ""));
        assertEquals(50L, HorizonFields.views(state).get(HorizonFields.VALUE).max());
        assertTrue(HorizonFields.warning(state, LocalDate.of(2026, 1, 1)).isPresent());
    }
}
