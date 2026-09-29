package ru.cashprediction.core.ui.forms.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.fake.FakeStates;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormState;
import ru.cashprediction.core.ui.form.Problem;

/** Проверяет обработку недоступного прогноза калькулятором цели. */
class GoalCalculatorFormTest {

    /** Ошибка прогноза отображается в строке проблем, а форма не бросает исключение. */
    @Test
    void unavailableForecastIsShownAsProblem(@TempDir Path folder) {
        GoalCalculatorForm form = new GoalCalculatorForm();
        FormContext context = new FormContext("w1", "main", Map.of(), FakeStates.empty(ClientProfile.swing(), folder));
        var values = new java.util.LinkedHashMap<>(form.defaults(context));
        values.put("target", "1000,00");
        var view = form.evaluate(new FormState(0, values), context);
        assertEquals(Problem.Severity.ERROR, view.problem().severity());
        assertTrue(view.problem().display().contains("Прогноз не рассчитан"));
    }
}
