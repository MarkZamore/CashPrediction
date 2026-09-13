package ru.cashprediction.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Тесты координатора восстановления с фейковыми фабрикой окон и главным окном: порядок шагов,
 * последовательная асинхронная цепочка, переназначение владельцев, пропуск неизвестных типов
 * и режим создания для исчезнувших операций.
 */
class RestoreCoordinatorTest {

    private final List<String> log = new CopyOnWriteArrayList<>();
    private final FakeStore store = new FakeStore("registry");
    private final SessionRecorder recorder = new SessionRecorder("fx", List.of(store), UiExecutor.direct(),
            new FakeSource(), new FakeScheduler(), Clock.systemUTC(), 1);

    /** Главное окно, записывающее вызовы в журнал. */
    private final class FakeTarget implements RestoreTarget {
        Set<String> ids = Set.of("r1", "r3", "t1");
        boolean failLoad;

        @Override
        public void loadPlan(PlanState plan, String planPath, Consumer<String> warn) {
            log.add("loadPlan " + planPath + " dirty=" + plan.dirty());
            if (failLoad) {
                throw new IllegalStateException("файл повреждён");
            }
            if (planPath.isEmpty()) {
                warn.accept("План не был открыт");
            }
        }

        @Override
        public void applyMain(MainWindowState main) {
            log.add("applyMain " + main.view());
        }

        @Override
        public void showMainWindow() {
            log.add("showMainWindow");
        }

        @Override
        public void selectRow(String rowId) {
            log.add("selectRow " + rowId);
        }

        @Override
        public Set<String> existingTargetIds() {
            return ids;
        }
    }

    /** Одно обращение к фабрике. */
    private record OpenCall(WindowState state, String ownerId, Consumer<StatefulWindow> onShown, Consumer<String> onFailed) {
        void show() {
            onShown.accept(FakeWindow.fromState(state));
        }
    }

    /** Фабрика: в автоматическом режиме показывает окно сразу, в ручном — копит вызовы. */
    private final class FakeFactory implements WindowFactory {
        final List<OpenCall> calls = new ArrayList<>();
        boolean automatic = true;
        String failType;

        @Override
        public void open(WindowState state, String ownerId, Consumer<StatefulWindow> onShown, Consumer<String> onFailed) {
            log.add("open " + state.type() + " as " + state.id() + " owner " + ownerId + " recorderStarted=" + recorder.isStarted());
            OpenCall call = new OpenCall(state, ownerId, onShown, onFailed);
            calls.add(call);
            if (state.type().name().equals(failType)) {
                onFailed.accept("окно не создаётся");
            } else if (automatic) {
                call.show();
            }
        }
    }

    private static WindowState window(String id, WindowType type, boolean modal, String owner, Map<String, String> context) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("value", "поле " + id);
        return new WindowState(id, type, modal, owner, new WindowBounds(10, 20, 300, 200), context, fields);
    }

    private static SessionSnapshot snapshot(List<WindowState> windows) {
        MainWindowState main = new MainWindowState(null, false, "CHART", "План.md", "12m", Map.of(), "", "r3@2026-10-01");
        return SessionSnapshot.of(SessionFixtures.SAVED, "fx", main, PlanState.dirty("# План: План\n"), windows);
    }

    @Test
    void restoresInDocumentedOrderWithRecorderStartedBeforeWindows() {
        FakeFactory factory = new FakeFactory();
        List<RestoreReport> reports = new ArrayList<>();
        SessionSnapshot snapshot = snapshot(List.of(
                window("w1", WindowType.RULE_EDITOR, true, "main", Map.of("mode", "edit", "ruleId", "r3")),
                window("w2", WindowType.GOAL_CALCULATOR, false, "main", Map.of()),
                window("w3", WindowType.ALERT, true, "w1", Map.of("purpose", "deleteRule", "targetId", "r3"))));
        new RestoreCoordinator().restore(snapshot, new FakeTarget(), factory, recorder, reports::add);

        assertEquals(List.of(
                "loadPlan План.md dirty=true",
                "applyMain CHART",
                "showMainWindow",
                "open GOAL_CALCULATOR as w1 owner main recorderStarted=true",
                "open RULE_EDITOR as w2 owner main recorderStarted=true",
                "open ALERT as w3 owner w2 recorderStarted=true",
                "selectRow r3@2026-10-01"), log);
        assertEquals(List.of("registry:markDirty"), store.events, "маркер поставлен до показа окон");
        assertEquals(1, reports.size());
        assertEquals(3, reports.get(0).windowsRestored());
        assertTrue(reports.get(0).clean(), reports.get(0).warnings().toString());
        assertEquals(List.of("w1", "w2", "w3"), recorder.registeredWindows().stream().map(StatefulWindow::windowId).toList());
        // Поля и геометрия переданы фабрике без изменений.
        assertEquals("поле w1", factory.calls.get(1).state().field("value"));
        assertEquals(new WindowBounds(10, 20, 300, 200), factory.calls.get(1).state().bounds());
    }

    @Test
    void windowsOpenSequentiallyOnlyAfterPreviousIsShown() {
        FakeFactory factory = new FakeFactory();
        factory.automatic = false;
        List<RestoreReport> reports = new ArrayList<>();
        SessionSnapshot snapshot = snapshot(List.of(
                window("w1", WindowType.PLAN_SETTINGS, true, "main", Map.of()),
                window("w2", WindowType.CHOICE, true, "w1", Map.of("purpose", "currency")),
                window("w3", WindowType.TEXT_INPUT, true, "w2", Map.of("purpose", "customCurrency"))));
        new RestoreCoordinator().restore(snapshot, new FakeTarget(), factory, recorder, reports::add);

        assertEquals(1, factory.calls.size(), "второе окно ждёт показа первого (модальный диалог Swing блокирует)");
        factory.calls.get(0).show();
        assertEquals(2, factory.calls.size());
        assertEquals("w1", factory.calls.get(1).ownerId());
        assertTrue(reports.isEmpty());
        factory.calls.get(1).show();
        assertEquals(3, factory.calls.size());
        assertEquals("w2", factory.calls.get(2).ownerId());
        // Повторный колбэк от фабрики не должен продвинуть цепочку второй раз.
        factory.calls.get(1).show();
        assertEquals(3, factory.calls.size());
        factory.calls.get(2).show();
        assertEquals(1, reports.size());
        assertEquals(3, reports.get(0).windowsRestored());
    }

    @Test
    void unknownOwnersAndTypesProduceWarnings() {
        FakeFactory factory = new FakeFactory();
        List<RestoreReport> reports = new ArrayList<>();
        SessionSnapshot snapshot = snapshot(List.of(
                new WindowState("w1", null, true, "main", null, Map.of(), Map.of()),
                window("w2", WindowType.ALERT, true, "w1", Map.of("purpose", "info")),
                window("w3", WindowType.CSV_EXPORT, true, "w9", Map.of())));
        new RestoreCoordinator().restore(snapshot, new FakeTarget(), factory, recorder, reports::add);

        assertEquals(2, factory.calls.size());
        assertEquals("main", factory.calls.get(0).ownerId(), "владелец неизвестного типа не восстановлен");
        assertEquals("main", factory.calls.get(1).ownerId());
        RestoreReport report = reports.get(0);
        assertEquals(2, report.windowsRestored());
        assertEquals(List.of(
                "Окно w1 неизвестного типа пропущено",
                "Владелец окна «Подтверждение» (w1) не восстановлен: окно открыто поверх главного окна",
                "Владелец окна «Экспорт в CSV» (w9) не восстановлен: окно открыто поверх главного окна"),
                report.warnings());
    }

    @Test
    void missingTargetOpensInCreateMode() {
        FakeFactory factory = new FakeFactory();
        List<RestoreReport> reports = new ArrayList<>();
        SessionSnapshot snapshot = snapshot(List.of(
                window("w1", WindowType.RULE_EDITOR, true, "main", Map.of("mode", "edit", "ruleId", "r5")),
                window("w2", WindowType.ONE_TIME_EDITOR, true, "main", Map.of("mode", "edit", "txId", "t1")),
                window("w3", WindowType.ONE_TIME_EDITOR, true, "main", Map.of("mode", "create", "txId", "t9"))));
        new RestoreCoordinator().restore(snapshot, new FakeTarget(), factory, recorder, reports::add);

        WindowState rule = factory.calls.get(0).state();
        assertEquals("create", rule.contextValue("mode"));
        assertEquals("r5", rule.contextValue("ruleId"));
        assertEquals("поле w1", rule.field("value"), "введённые значения сохраняются");
        assertEquals("edit", factory.calls.get(1).state().contextValue("mode"), "существующая операция открывается как была");
        assertEquals("create", factory.calls.get(2).state().contextValue("mode"));
        assertEquals(List.of("Операция «r5» не найдена в восстановленном плане: окно «Регулярная операция» открыто "
                + "в режиме создания с введёнными значениями"), reports.get(0).warnings());
    }

    @Test
    void failedWindowIsReportedAndChainContinues() {
        FakeFactory factory = new FakeFactory();
        factory.failType = "RULE_EDITOR";
        List<RestoreReport> reports = new ArrayList<>();
        SessionSnapshot snapshot = snapshot(List.of(
                window("w1", WindowType.RULE_EDITOR, true, "main", Map.of("mode", "create")),
                window("w2", WindowType.ALERT, true, "w1", Map.of("purpose", "info"))));
        new RestoreCoordinator().restore(snapshot, new FakeTarget(), factory, recorder, reports::add);

        RestoreReport report = reports.get(0);
        assertEquals(1, report.windowsRestored());
        assertEquals("Окно «Регулярная операция» не восстановлено: окно не создаётся", report.warnings().get(0));
        assertEquals("main", factory.calls.get(1).ownerId());
    }

    @Test
    void emptySnapshotStillStartsRecorderAndReports() {
        List<RestoreReport> reports = new ArrayList<>();
        MainWindowState main = MainWindowState.empty();
        SessionSnapshot snapshot = SessionSnapshot.of(SessionFixtures.SAVED, "fx", main, PlanState.CLEAN, List.of());
        FakeTarget target = new FakeTarget();
        new RestoreCoordinator().restore(snapshot, target, new FakeFactory(), recorder, reports::add);
        assertTrue(recorder.isStarted());
        assertEquals(List.of("loadPlan  dirty=false", "applyMain ", "showMainWindow"), log, "пустое выделение не выбирается");
        assertEquals(List.of("План не был открыт"), reports.get(0).warnings());
        assertEquals(0, reports.get(0).windowsRestored());
    }

    /** Регрессия: несохранённый план из снимка не загрузился — рекордер не запускается и не затирает снимок сбоя. */
    @Test
    void recorderIsNotStartedWhenDirtyPlanFailsToLoad() {
        FakeTarget target = new FakeTarget();
        target.failLoad = true;
        SessionSnapshot crash = snapshot(List.of(window("w1", WindowType.GOAL_CALCULATOR, false, "main", Map.of())));
        store.marker = SessionFixtures.running("fx");
        store.snapshot = crash;
        List<RestoreReport> reports = new ArrayList<>();
        new RestoreCoordinator().restore(crash, target, new FakeFactory(), recorder, reports::add);

        assertFalse(recorder.isStarted());
        assertEquals(List.of(), store.events, "маркер и снимок сбоя не тронуты");
        assertSame(crash, store.snapshot);
        RestoreReport report = reports.get(0);
        assertEquals(1, report.windowsRestored(), "окна всё равно открыты");
        assertEquals(List.of("Не удалось загрузить план: файл повреждён", RestoreCoordinator.RECORDER_NOT_STARTED), report.warnings());
    }

    /** План из файла (без несохранённых изменений) не загрузился — терять нечего, рекордер запускается. */
    @Test
    void recorderStartsWhenPlanFromFileFailsToLoad() {
        FakeTarget target = new FakeTarget();
        target.failLoad = true;
        List<RestoreReport> reports = new ArrayList<>();
        SessionSnapshot snapshot = SessionSnapshot.of(SessionFixtures.SAVED, "fx", MainWindowState.empty(), PlanState.CLEAN, List.of());
        new RestoreCoordinator().restore(snapshot, target, new FakeFactory(), recorder, reports::add);
        assertTrue(recorder.isStarted());
        assertEquals(List.of("Не удалось загрузить план: файл повреждён"), reports.get(0).warnings());
    }

    @Test
    void startFreshClearsAllStores() {
        FakeStore other = new FakeStore("xml");
        store.marker = SessionFixtures.running("fx");
        other.snapshot = SessionFixtures.simple("fx");
        RestoreCoordinator.startFresh(List.of(store, other));
        assertEquals(List.of("registry:clear"), store.events);
        assertEquals(List.of("xml:clear"), other.events);
        assertNull(store.marker);
        assertNull(other.snapshot);
    }
}
