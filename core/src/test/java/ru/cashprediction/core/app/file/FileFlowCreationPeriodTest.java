package ru.cashprediction.core.app.file;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.document.PeriodChoice;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.ui.forms.plan.NewPlanWizardForm;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет исключительно callback Created: период до первого события нового плана и при ошибке записи. */
class FileFlowCreationPeriodTest {
    @TempDir Path temp;

    /** Первый observer нового плана уже видит выбранный M3; сохранение остаётся обычным. */
    @Test void selectedPeriodAppliedBeforeNewPlanEvent() throws Exception {
        var f = new FakeFileFlowContext(temp); var seen = new ArrayList<PeriodChoice>();
        f.document.addListener(event -> { if (f.document.plan().name().equals("Created")) seen.add(f.document.viewState().period()); });
        creationFlow(f).newPlan();
        f.formResult(new NewPlanWizardForm.Created(Plan.empty("Created", FakeFileFlowContext.TODAY), PeriodChoice.M3));
        assertFalse(seen.isEmpty()); assertTrue(seen.stream().allMatch(period -> period == PeriodChoice.M3));
        assertEquals(PeriodChoice.M3, f.document.viewState().period());
        assertFalse(f.document.isDirty(), () -> f.alerts.toString());
    }

    /** Ошибка записи оставляет новый несохранённый план с ALL, а не прежний период. */
    @Test void writeFailureRetainsSelectedPeriod() throws Exception {
        var f = new FakeFileFlowContext(temp); creationFlow(f).newPlan();
        Files.delete(f.environment.cashMemory()); Files.writeString(f.environment.cashMemory(), "block");
        f.formResult(new NewPlanWizardForm.Created(Plan.empty("Created", FakeFileFlowContext.TODAY), PeriodChoice.ALL));
        assertEquals(PeriodChoice.ALL, f.document.viewState().period());
        assertTrue(f.document.isDirty()); assertTrue(f.document.file().isEmpty());
        assertEquals("err.createdNotSaved", f.alerts.getLast().purpose());
    }

    /** Отмена мастера не меняет ранее выбранный период. */
    @Test void cancelPreservesPreviousPeriod() throws Exception {
        var f = new FakeFileFlowContext(temp);
        f.document.setViewState(f.document.viewState().withPeriod(PeriodChoice.M24));
        creationFlow(f).newPlan(); f.form().closeRequested();
        assertEquals(PeriodChoice.M24, f.document.viewState().period());
    }

    /** Использует настоящий scoped storage, не меняя shared fake и production FileFlow save/load. */
    private static ru.cashprediction.core.app.flow.FileFlow creationFlow(FakeFileFlowContext f) {
        var storage = new ru.cashprediction.core.service.storage.FilePlanStorage(f.environment.cashMemory());
        return new ru.cashprediction.core.app.flow.FileFlow(f.context, storage);
    }
}
