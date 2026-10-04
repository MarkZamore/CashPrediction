package ru.cashprediction.core.service;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.diagnostics.Diagnostic;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.forecast.service.EngineForecastService;
import ru.cashprediction.core.forecast.service.ForecastRequest;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.service.plan.LocalPlanCommands;
import ru.cashprediction.core.service.plan.PlanCommand;
import ru.cashprediction.core.service.plan.PlanCommandError;
import ru.cashprediction.core.service.plan.PlanCommandProblem;
import ru.cashprediction.core.service.plan.PlanCommandRequest;
import ru.cashprediction.core.service.plan.PlanCommandResult;
import ru.cashprediction.core.service.storage.PlanStorage;

/**
 * Проверяет фактическое владение значениями на локальных границах служб, без клиента и файловой записи.
 * Не считает ссылку на внедрённый исполнитель или окно утечкой коллекции и не доказывает весь mutable core.
 */
class ServiceValueOwnershipTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 4);

    /** Аргументы проблемы и список результата копируются и не допускают изменение через accessor. */
    @Test void commandProblemsAndResultOwnTheirCollections() {
        var arguments = new LinkedHashMap<String, String>();
        arguments.put("revision", "1");
        var problem = new PlanCommandProblem(PlanCommandError.STALE_REVISION, null, null, null,
                arguments, "fixture");
        arguments.put("revision", "2");
        assertEquals("1", problem.arguments().get("revision"));
        assertThrows(UnsupportedOperationException.class, () -> problem.arguments().put("revision", "3"));
        var document = new PlanDocument(Plan.empty("fixture", TODAY), null, () -> TODAY);
        var commands = new LocalPlanCommands(document);
        var problems = new ArrayList<>(List.of(problem));
        var result = new PlanCommandResult(UUID.randomUUID(), PlanCommandResult.Status.REJECTED,
                commands.snapshot(), ru.cashprediction.core.service.plan.PlanCommandEffect.NONE, problems);
        problems.clear();
        assertEquals(List.of(problem), result.problems());
        assertThrows(UnsupportedOperationException.class, () -> result.problems().clear());
    }

    /** Старый результат настоящей команды сохраняет свой снимок после следующей команды и при повторе. */
    @Test void completedCommandSnapshotDoesNotFollowMutableOwner() {
        Plan original = Plan.empty("original", TODAY);
        var document = new PlanDocument(original, null, () -> TODAY);
        var commands = new LocalPlanCommands(document);
        var request = new PlanCommandRequest(UUID.randomUUID(), commands.snapshot().revision(),
                "first", new PlanCommand.RenamePlan("first"));
        var first = commands.execute(request);
        assertEquals(PlanCommandResult.Status.APPLIED, first.status());
        var second = commands.execute(new PlanCommandRequest(UUID.randomUUID(), first.snapshot().revision(),
                "second", new PlanCommand.RenamePlan("second")));
        assertEquals(PlanCommandResult.Status.APPLIED, second.status());
        assertEquals("original", original.name());
        assertEquals("first", first.snapshot().plan().name());
        assertEquals("second", commands.snapshot().plan().name());
        assertThrows(UnsupportedOperationException.class, () -> first.snapshot().plan().rules().clear());
        assertSame(first, commands.execute(request));
        assertEquals("second", document.plan().name());
    }

    /** Диагностика хранилища не меняется вслед за входным списком и не выдаёт writable список. */
    @Test void storageSnapshotOwnsDiagnosticList() {
        var diagnostics = new ArrayList<>(List.of(Diagnostic.warning("fixture")));
        var snapshot = new PlanStorage.Snapshot(new PlanStorage.Reference("fixture"),
                new PlanStorage.Version("1"), Plan.empty("fixture", TODAY), diagnostics);
        diagnostics.clear();
        assertEquals(1, snapshot.diagnostics().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.diagnostics().clear());
        assertThrows(NullPointerException.class, () -> new PlanStorage.Snapshot(snapshot.reference(),
                snapshot.version(), snapshot.plan(), java.util.Arrays.asList((Diagnostic) null)));
        assertEquals(1, snapshot.diagnostics().size());
    }

    /** Массив настоящего результата расчёта защищён на входе нового снимка и при каждом чтении. */
    @Test void forecastSnapshotOwnsArrayOnInputAndOutput() {
        var request = new ForecastRequest(Plan.empty("fixture", TODAY), WhatIf.NONE, TODAY, false);
        var calculated = EngineForecastService.DEFAULT.calculate(request);
        long[] input = calculated.dailyBalance();
        long expected = input[0];
        var snapshot = new Forecast(calculated.plan(), calculated.whatIf(), calculated.today(),
                calculated.anchor(), calculated.rows(), input, calculated.dailyStart(),
                calculated.summary(), calculated.warnings());
        input[0] = expected + 1;
        assertEquals(expected, snapshot.dailyBalance()[0]);
        long[] exported = snapshot.dailyBalance();
        exported[0] = expected + 2;
        assertEquals(expected, snapshot.dailyBalance()[0]);
        assertNotSame(exported, snapshot.dailyBalance());
        assertEquals(Optional.of(snapshot.plan()), Optional.of(request.plan()));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.rows().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.warnings().clear());
    }
}
