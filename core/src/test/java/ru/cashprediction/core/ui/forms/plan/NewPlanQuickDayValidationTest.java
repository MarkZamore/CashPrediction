package ru.cashprediction.core.ui.forms.plan;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.fake.FakeStates;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.ui.form.*;

/** Проверяет неполный и ошибочный день быстрой операции до сборки плана. */
class NewPlanQuickDayValidationTest {
    /** Даёт ошибочные дни для обоих видов регулярных операций. */
    static Stream<Arguments> invalidDays() {
        return Stream.of("quickIncome", "quickExpense").flatMap(prefix ->
                Stream.of("", "0", "32", "-1", "1.5", "oops", "2147483648", "999999999999999999")
                        .map(raw -> Arguments.of(prefix, raw)));
    }

    /** Ошибка блокирует завершение и Enter, сохраняя весь незавершённый ввод. */
    @ParameterizedTest
    @MethodSource("invalidDays")
    void invalidDayBlocksFinishAndPreservesInput(String prefix, String raw, @TempDir Path folder) {
        var form = new NewPlanWizardForm();
        var context = context(folder);
        var values = new LinkedHashMap<>(form.defaults(context));
        values.put(prefix + "Amount", "1000,00");
        values.put(prefix + "Day", raw);
        values.put(prefix + "Title", "draft");
        var state = new FormState(2, values);
        var before = Map.copyOf(state.values());
        var view = assertDoesNotThrow(() -> form.evaluate(state, context));
        assertEquals(Problem.Severity.ERROR, view.problem().severity());
        assertFalse(view.problem().text().isBlank());
        assertFalse(view.buttons().get("finish").enabled());
        assertInstanceOf(FormOutcome.Stay.class, assertDoesNotThrow(() -> form.onButton("finish", state, context)));
        assertInstanceOf(FormOutcome.Stay.class, assertDoesNotThrow(() ->
                form.onFieldSubmitted(prefix + "Day", state, context).orElseThrow()));
        assertEquals(before, state.values());
        assertEquals(raw, state.value(prefix + "Day"));
    }

    /** Проверяет обе допустимые границы дня для дохода и расхода. */
    @Test
    void boundaryDaysRemainValid(@TempDir Path folder) {
        for (String prefix : new String[] {"quickIncome", "quickExpense"}) {
            for (String day : new String[] {"1", "31"}) {
                var form = new NewPlanWizardForm();
                var context = context(folder);
                var values = new LinkedHashMap<>(form.defaults(context));
                values.put(prefix + "Amount", "1000,00");
                values.put(prefix + "Day", day);
                var state = new FormState(2, values);
                assertTrue(form.evaluate(state, context).buttons().get("finish").enabled());
                var close = assertInstanceOf(FormOutcome.Close.class, form.onButton("finish", state, context));
                var plan = assertInstanceOf(NewPlanWizardForm.Created.class, close.result()).plan();
                var recurrence = assertInstanceOf(Recurrence.Monthly.class, plan.rules().getFirst().recurrence());
                assertEquals(Integer.parseInt(day), recurrence.dayOfMonth());
            }
        }
    }

    /** Пустая сумма пропускает операцию вместе с её недопечатанным днём. */
    @Test
    void unusedQuickOperationDoesNotBlockCreation(@TempDir Path folder) {
        var form = new NewPlanWizardForm();
        var context = context(folder);
        var values = new LinkedHashMap<>(form.defaults(context));
        values.put("quickIncomeDay", "oops");
        values.put("quickExpenseDay", "");
        var state = new FormState(2, values);
        assertTrue(form.evaluate(state, context).buttons().get("finish").enabled());
        var close = assertInstanceOf(FormOutcome.Close.class, form.onButton("finish", state, context));
        assertTrue(assertInstanceOf(NewPlanWizardForm.Created.class, close.result()).plan().rules().isEmpty());
    }

    /** Создаёт снимок окружения формы без GUI, реестра и файлов продукта. */
    private static FormContext context(Path folder) {
        return new FormContext("w1", "main", Map.of(), FakeStates.empty(ClientProfile.swing(), folder));
    }
}
