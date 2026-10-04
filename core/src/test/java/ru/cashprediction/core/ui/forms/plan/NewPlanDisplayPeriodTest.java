package ru.cashprediction.core.ui.forms.plan;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.fake.FakeStates;
import ru.cashprediction.core.document.PeriodChoice;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.ui.form.*;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет независимый начальный период показа и совместимость старых снимков мастера. */
class NewPlanDisplayPeriodTest {
    /** Новый control объявлен на странице2 с кодами общего закрытого списка и default M12. */
    @Test void defaultChoiceDeclaredOnCreationPage(@TempDir Path folder) {
        var form = new NewPlanWizardForm(); var context = context(folder);
        assertEquals("M12", form.defaults(context).get("displayPeriod"));
        var field = form.spec(context).pages().get(1).rows().stream().filter(row -> row instanceof FormRow.Field)
                .map(row -> ((FormRow.Field) row).field()).filter(spec -> spec.id().equals("displayPeriod")).findFirst().orElseThrow();
        assertEquals(FieldKind.CHOICE, field.kind());
        assertEquals(Arrays.stream(PeriodChoice.values()).map(PeriodChoice::name).toList(), field.options().stream().map(Option::value).toList());
        for (var option : field.options()) {
            assertEquals(ru.cashprediction.core.ui.text.UiText.get("form.newPlan.displayPeriod." + option.value()), option.text());
        }
    }

    /** Все варианты показа независимы от горизонта24 месяца, включая ALL. */
    @Test void displayPeriodDoesNotChangeForecastHorizon(@TempDir Path folder) {
        var form = new NewPlanWizardForm(); var context = context(folder);
        for (PeriodChoice period : PeriodChoice.values()) {
            var values = new LinkedHashMap<>(form.defaults(context));
            values.put("displayPeriod", period.name()); values.put("horizonValue", "24");
            var state = new FormState(2, values);
            var close = assertInstanceOf(FormOutcome.Close.class, form.onButton("finish", state, context));
            var created = assertInstanceOf(NewPlanWizardForm.Created.class, close.result());
            assertEquals(period, created.displayPeriod());
            assertEquals(new Horizon.Months(24), created.plan().horizon());
            assertFalse(ru.cashprediction.core.markdown.PlanMarkdownWriter.write(created.plan()).contains("displayPeriod"));
        }
    }

    /** Ошибочный код не исправляется молча, блокирует кнопку/Enter и сохраняет ввод. */
    @Test void invalidPeriodBlocksFinishAndEnter(@TempDir Path folder) {
        var form = new NewPlanWizardForm(); var context = context(folder);
        for (String invalid : List.of("", "M9", "oops")) {
            var values = new LinkedHashMap<>(form.defaults(context)); values.put("displayPeriod", invalid);
            var state = new FormState(1, values); var before = Map.copyOf(values);
            assertEquals(Problem.Severity.ERROR, form.evaluate(state, context).problem().severity());
            assertFalse(form.evaluate(state, context).buttons().get("finish").enabled());
            assertInstanceOf(FormOutcome.Stay.class, form.onButton("finish", state, context));
            assertInstanceOf(FormOutcome.Stay.class, form.onFieldSubmitted("displayPeriod", state, context).orElseThrow());
            assertEquals(before, state.values());
        }
    }

    /** Старый срез без нового поля получает M12; существующий конструктор результата тоже совместим. */
    @Test void missingFieldDefaultsToM12(@TempDir Path folder) {
        var form = new NewPlanWizardForm(); var context = context(folder);
        var values = new LinkedHashMap<>(form.defaults(context)); values.remove("displayPeriod");
        var close = assertInstanceOf(FormOutcome.Close.class, form.onButton("finish", new FormState(2, values), context));
        var created = assertInstanceOf(NewPlanWizardForm.Created.class, close.result());
        assertEquals(PeriodChoice.M12, created.displayPeriod());
        assertEquals(PeriodChoice.M12, new NewPlanWizardForm.Created(created.plan()).displayPeriod());
    }

    /** Реальный FormSession применяет старые/new WindowState, не теряя выбор или незавершённый ввод. */
    @Test void restoreUsesDictionaryAndMergesMissingDefaults(@TempDir Path folder) {
        var context = context(folder);
        for (Map<String, String> fields : List.of(Map.of("name", "Restored", "startBalance", "oops"),
                Map.of("name", "Restored", "startBalance", "oops", "displayPeriod", "ALL"))) {
            var host = (FormSession.Host) java.lang.reflect.Proxy.newProxyInstance(FormSession.Host.class.getClassLoader(),
                    new Class<?>[]{FormSession.Host.class}, (proxy, method, args) -> null);
            var session = new FormSession(WindowType.NEW_PLAN_WIZARD, true, new NewPlanWizardForm(), context, host);
            session.applyState(new WindowState("w1", WindowType.NEW_PLAN_WIZARD, true, "main", null, Map.of("page", "1"), fields));
            assertEquals(fields.getOrDefault("displayPeriod", "M12"), session.state().value("displayPeriod"));
            assertEquals("oops", session.state().value("startBalance"));
            assertEquals(session.state().value("displayPeriod"), session.captureState().field("displayPeriod"));
        }
        assertTrue(WindowType.NEW_PLAN_WIZARD.fieldIds().contains("displayPeriod"));
    }

    private static FormContext context(Path folder) {
        return new FormContext("w1", "main", Map.of(), FakeStates.empty(ClientProfile.swing(), folder));
    }
}
