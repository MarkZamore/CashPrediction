package ru.cashprediction.core.ui.forms.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.DocumentView;
import ru.cashprediction.core.app.fake.FakeStates;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormOutcome;
import ru.cashprediction.core.ui.form.FormState;

/** Проверяет параметры плана. */
class PlanSettingsFormTest {

    /** Сохранённый план не позволяет менять имя через параметры. */
    @Test
    void storedPlanNameIsReadOnly(@TempDir Path folder) {
        Plan plan = Plan.empty("Рабочий", FakeStates.TODAY);
        var app = FakeStates.withPlan(ClientProfile.swing(), plan, folder);
        app = new ru.cashprediction.core.app.AppState(app.revision(), app.profile(), app.today(), app.cashMemory(), app.plansFolder(),
                new DocumentView(plan, folder.resolve("Рабочий.md"), false, false, "", false, "", null, "", java.util.List.of()), app.view(), app.selectedRowId(), app.pastExpanded(), app.settings(), app.recorder(), app.stores(), app.windows(), app.status(), app.autosaveProblem());
        PlanSettingsForm form = new PlanSettingsForm();
        FormContext context = new FormContext("w1", "main", java.util.Map.of(), app);
        assertTrue(form.evaluate(new FormState(0, form.defaults(context)), context).fields().get("name").readOnly());
    }

    /** Подтверждение возвращает единый новый неизменяемый план. */
    @Test
    void saveReturnsChangedPlan(@TempDir Path folder) {
        PlanSettingsForm form = new PlanSettingsForm();
        FormContext context = new FormContext("w1", "main", java.util.Map.of(), FakeStates.withPlan(ClientProfile.swing(), Plan.empty("План", FakeStates.TODAY), folder));
        var values = new LinkedHashMap<>(form.defaults(context));
        values.put("note", "проверка");
        FormOutcome.Close close = assertInstanceOf(FormOutcome.Close.class, form.onButton("ok", new FormState(0, values), context));
        assertEquals("проверка", assertInstanceOf(Plan.class, close.result()).note());
    }
}
