package ru.cashprediction.core.app.session.capture;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.app.flow.*;
import ru.cashprediction.core.document.*;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.markdown.PlanMarkdownWriter;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.session.store.*;
import ru.cashprediction.core.ui.text.UiText;

/** Проверяет содержимое снимка и восстановление независимо от контроллера и инструментов интерфейса. */
class SessionBridgeTest {
    @TempDir Path home;

    /** Каждый формат переносит весь вид, выделение и геометрию в новый контекст. */
    @Test void roundTripAllStores() throws SessionStoreException {
        CaptureContext before = new CaptureContext(home, ClientProfile.fx("25"));
        WindowBounds bounds = new WindowBounds(17, 29, 1234, 876);
        before.port.setMainGeometry(new MainGeometry(bounds, true));
        before.document.setViewState(new ViewState(ViewMode.CHART, PeriodChoice.M24, false, true,
                false, true, false, false, true, false, " salary ", WhatIf.ofPercent(-10, 10, Money.parse("7300,50"))));
        before.past = true;
        before.selection = "r1@2026-10-05";
        SessionBridge source = new SessionBridge(before.flow);
        MainWindowState main = source.captureMain();
        assertEquals(bounds, main.bounds());
        assertTrue(main.maximized());
        assertEquals("7300,50", main.whatIfExtra());
        assertEquals(11, main.filters().size());
        assertEquals("CHART", main.view());
        assertEquals("M24", main.period());
        List<SessionStore> stores = List.of(RegistrySessionStore.inMemory("fx", before.environment.cashMemory()),
                before.environment.xmlStore("fx"), before.environment.webStore());
        for (SessionStore store : stores) {
            SessionSnapshot snapshot = SessionSnapshot.of(Instant.parse("2026-10-01T00:00:00Z"), "fx",
                    main, source.capturePlan(), List.of());
            store.save(snapshot);
            SessionSnapshot saved = store.load().orElseThrow();
            assertEquals(main, saved.main());
            CaptureContext after = new CaptureContext(home, ClientProfile.fx("25"));
            SessionBridge target = new SessionBridge(after.flow);
            target.loadPlan(saved.plan(), saved.main().planPath(), warning -> fail(warning));
            target.applyMain(saved.main());
            assertEquals(0, after.shownCount);
            target.showMainWindow();
            assertEquals(saved.main(), after.shown);
            after.port.setMainGeometry(new MainGeometry(after.shown.bounds(), after.shown.maximized()));
            target.selectRow(saved.main().selectedRowId());
            assertEquals(main, target.captureMain());
            assertEquals(before.document.viewState(), after.document.viewState());
            assertTrue(after.past);
            assertEquals(before.selection, after.selection);
            assertEquals(source.capturePlan(), target.capturePlan());
            assertEquals(source.existingTargetIds(), target.existingTargetIds());
            assertEquals(RevealMode.SELECT_AND_SCROLL, after.port.calls("revealRow").getFirst().args().get(1));
            store.clear();
        }
    }

    /** Отсутствующие добавленные поля старого снимка сбрасывают прежний режим «что-если». */
    @Test void legacyDefaultsAndPeriodKeys() {
        CaptureContext fake = new CaptureContext(home, ClientProfile.swing());
        fake.past = true;
        fake.document.setViewState(ViewState.defaults().withWhatIf(WhatIf.ofPercent(-10, 10, Money.parse("42"))));
        SessionBridge bridge = new SessionBridge(fake.flow);
        bridge.applyMain(new MainWindowState(null, false, "CHART", "", "6m", Map.of("showIncome", false), "x", ""));
        assertEquals(PeriodChoice.M6, fake.document.viewState().period());
        assertEquals(ViewMode.CHART, fake.document.viewState().mode());
        assertFalse(fake.document.viewState().showIncome());
        assertTrue(fake.document.viewState().showExpense());
        assertTrue(fake.document.viewState().whatIf().isNone());
        assertFalse(fake.past);
        bridge.selectRow("");
        assertTrue(fake.port.calls("revealRow").isEmpty());
    }

    /** Захват всегда читает новую геометрию, имя относительное только внутри CashMemory. */
    @Test void pathsAndCleanPlan() {
        CaptureContext fake = new CaptureContext(home, ClientProfile.fx("25"));
        SessionBridge bridge = new SessionBridge(fake.flow);
        assertTrue(bridge.capturePlan().dirty());
        Path inside = fake.environment.cashMemory().resolve("nested/budget.md");
        fake.document.markSaved(inside);
        assertEquals(Path.of("nested/budget.md").toString(), bridge.captureMain().planPath());
        assertEquals(PlanState.CLEAN, bridge.capturePlan());
        Path outside = home.resolve("external.md").toAbsolutePath().normalize();
        fake.document.markSaved(outside);
        assertEquals(outside.toString(), bridge.captureMain().planPath());
        fake.port.setMainGeometry(new MainGeometry(new WindowBounds(1, 2, 900, 600), false));
        assertEquals(1, bridge.captureMain().bounds().x());
        fake.port.setMainGeometry(new MainGeometry(new WindowBounds(7, 8, 1200, 800), true));
        assertEquals(7, bridge.captureMain().bounds().x());
    }

    /** Нечитаемый несохранённый план не подменяется пустым и сохраняет исходный документ. */
    @Test void failedDirtyPlanIsNotReplaced() {
        CaptureContext fake = new CaptureContext(home, ClientProfile.web());
        SessionBridge bridge = new SessionBridge(fake.flow);
        Plan before = fake.document.plan();
        assertThrows(RuntimeException.class, () -> bridge.loadPlan(PlanState.dirty("not a plan"), "", warning -> { }));
        assertSame(before, fake.document.plan());
    }

    /** Потерянный файл даёт точные предупреждения и безопасный пустой план. */
    @Test void missingCleanPlanWarns() {
        CaptureContext fake = new CaptureContext(home, ClientProfile.web());
        List<String> warnings = new ArrayList<>();
        new SessionBridge(fake.flow).loadPlan(PlanState.CLEAN, "missing.md", warnings::add);
        Path missing = fake.environment.cashMemory().resolve("missing.md");
        assertEquals(List.of(UiText.get("restore.warn.planMissing", missing),
                UiText.get("restore.warn.emptyPlan", missing)), warnings);
        assertEquals(UiText.get("plan.defaultName"), fake.document.plan().name());
        assertTrue(fake.document.file().isEmpty());
        assertFalse(fake.document.isDirty());
    }

    /** Несохранённый снимок имеет приоритет над существующим путём и сохраняет путь для будущей записи. */
    @Test void dirtyPlanKeepsItsNameAndPath() {
        CaptureContext fake = new CaptureContext(home, ClientProfile.swing());
        Plan plan = fake.document.plan().withName("snapshot name");
        new SessionBridge(fake.flow).loadPlan(PlanState.dirty(PlanMarkdownWriter.write(plan)), "saved.md", warning -> fail(warning));
        assertEquals("snapshot name", fake.document.plan().name());
        assertTrue(fake.document.isDirty());
        assertEquals(fake.environment.cashMemory().resolve("saved.md"), fake.document.file().orElseThrow());
    }
}
