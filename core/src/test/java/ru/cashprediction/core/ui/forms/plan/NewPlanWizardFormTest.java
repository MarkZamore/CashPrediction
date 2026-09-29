package ru.cashprediction.core.ui.forms.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.fake.FakeStates;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormOutcome;
import ru.cashprediction.core.ui.form.FormState;

/** Проверяет мастер создания плана. */
class NewPlanWizardFormTest {

    /** Завершение создаёт план только с заполненной быстрой операцией. */
    @Test
    void finishBuildsPlanAndSkipsEmptyQuickOperation(@TempDir Path folder) {
        NewPlanWizardForm form = new NewPlanWizardForm();
        FormContext context = new FormContext("w1", "main", java.util.Map.of(), FakeStates.empty(ClientProfile.swing(), folder));
        LinkedHashMap<String, String> values = new LinkedHashMap<>(form.defaults(context));
        values.put("quickIncomeAmount", "1000,00");
        FormOutcome.Close close = assertInstanceOf(FormOutcome.Close.class, form.onButton("finish", new FormState(2, values), context));
        NewPlanWizardForm.Created created = assertInstanceOf(NewPlanWizardForm.Created.class, close.result());
        assertEquals(1, created.plan().rules().size());
        assertEquals(Kind.INCOME, created.plan().rules().getFirst().kind());
        assertEquals("Мой план", created.plan().name());
    }

    /** Занятое имя блокирует переход со страницы названия. */
    @Test
    void existingNameBlocksNext(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("Мой план.md"), "");
        NewPlanWizardForm form = new NewPlanWizardForm();
        FormContext context = new FormContext("w1", "main", java.util.Map.of(), FakeStates.empty(ClientProfile.swing(), folder));
        var values = new LinkedHashMap<>(form.defaults(context));
        values.put("name", "Мой план");
        FormState state = new FormState(0, values);
        assertEquals(ru.cashprediction.core.ui.form.Problem.Severity.ERROR, form.evaluate(state, context).problem().severity());
    }

    /** Enter на последней странице равносилен «Готово». */
    @Test
    void enterFinishesOnLastPage(@TempDir Path folder) {
        NewPlanWizardForm form = new NewPlanWizardForm();
        FormContext context = new FormContext("w1", "main", java.util.Map.of(), FakeStates.empty(ClientProfile.swing(), folder));
        FormOutcome outcome = form.onFieldSubmitted("quickIncomeTitle", new FormState(2, form.defaults(context)), context).orElseThrow();
        assertInstanceOf(FormOutcome.Close.class, outcome);
    }
}
