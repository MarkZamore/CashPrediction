package ru.cashprediction.core.session;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.fake.FakeStates;
import ru.cashprediction.core.session.codec.*;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.forms.plan.NewPlanWizardForm;

/** Проверяет новый стык schema-1 кодеков, восстановления мастера и повторной записи без native UI. */
class WizardSnapshotCompatibilityIntegrationTest {
    /** Фиксированный старый XML не строится текущим encoder и намеренно не содержит displayPeriod. */
    private static final String OLD_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <session schema="1" client="fx" savedAt="2026-09-13T10:15:30Z">
              <main view="TABLE" period="12m" maximized="false" filterText="">
                <plan path=""/><filters/><selection rowId=""/>
              </main>
              <unsavedPlan dirty="false"/>
              <windows>
                <window id="old-wizard" type="NEW_PLAN_WIZARD" modal="true" owner="main"
                        x="400" y="300" width="600" height="480">
                  <context key="page" value="2"/>
                  <field id="name" value="Legacy"/>
                  <field id="currency" value="₽"/>
                  <field id="startDate" value="2026-"/>
                  <field id="startBalance" value="1,234"/>
                  <field id="quickIncomeAmount" value="10,"/>
                  <field id="quickIncomeDay" value="3x"/>
                </window>
              </windows>
            </session>
            """;

    /** Перечисляет реальные кодеки; хранилища и ОС-реестр в этом тесте не вызываются. */
    private static Stream<SnapshotCodec<String>> codecs() {
        return Stream.of(new JsonSnapshotCodec(), new XmlSnapshotCodec(), new MarkdownSnapshotCodec());
    }

    /** Старый XML без нового поля проходит recovery, получает default и повторно читается всеми кодеками. */
    @ParameterizedTest @MethodSource("codecs")
    void legacyWizardMigratesThroughRecoveryAndResave(SnapshotCodec<String> codec, @TempDir Path memory)
            throws Exception {
        SessionSnapshot legacy = new XmlSnapshotCodec().decode(OLD_XML);
        WindowState oldWindow = legacy.windows().getFirst();
        assertFalse(oldWindow.fields().containsKey("displayPeriod"));
        SessionSnapshot input = codec.decode(codec.encode(legacy));
        SessionSnapshot saved = recoverAndSave(input, memory, "M12");
        SessionSnapshot reopened = codec.decode(codec.encode(saved));
        assertEquals(saved, reopened, codec.formatName());
        WindowState restored = reopened.windows().getFirst();
        oldWindow.fields().forEach((key, value) -> assertEquals(value, restored.field(key), key));
        assertEquals("M12", restored.field("displayPeriod"));
        assertEquals("2", restored.contextValue("page"));
        assertEquals(oldWindow.bounds(), restored.bounds());
        assertEquals("main", restored.ownerId());
        assertNotEquals(oldWindow.id(), restored.id());
        assertEquals(1, reopened.schemaVersion(), "Добавление поля не меняет схему снимка");
    }

    /** Новое поле, включая незавершённый выбор, и raw значения не нормализуются при двух recovery циклах. */
    @ParameterizedTest @MethodSource("codecs")
    void currentWizardRawFieldsSurviveCodecRecoveryAndSecondResave(SnapshotCodec<String> codec,
                                                                  @TempDir Path memory) throws Exception {
        SessionSnapshot legacy = new XmlSnapshotCodec().decode(OLD_XML);
        for (String period : List.of("ALL", "not-yet")) {
            var fields = new LinkedHashMap<>(legacy.windows().getFirst().fields());
            fields.put("displayPeriod", period);
            fields.put("cushion", "");
            fields.put("horizonValue", "0?");
            fields.put("quickExpenseAmount", "-");
            fields.put("quickExpenseDay", "");
            fields.put("quickIncomeTitle", "line1\nline2\tend");
            var window = legacy.windows().getFirst().withFields(fields);
            SessionSnapshot input = SessionSnapshot.of(legacy.savedAt(), "fx", legacy.main(),
                    legacy.plan(), List.of(window));
            SessionSnapshot decoded = codec.decode(codec.encode(input));
            assertEquals(fields, decoded.windows().getFirst().fields());
            SessionSnapshot first = recoverAndSave(decoded, memory, period);
            SessionSnapshot second = recoverAndSave(codec.decode(codec.encode(first)), memory, period);
            SessionSnapshot reopened = codec.decode(codec.encode(second));
            fields.forEach((key, value) -> assertEquals(value, reopened.windows().getFirst().field(key), key));
            assertEquals(first.windows().getFirst().fields(), reopened.windows().getFirst().fields());
            assertEquals("2", reopened.windows().getFirst().contextValue("page"));
            assertEquals("main", reopened.windows().getFirst().ownerId());
            assertEquals(1, reopened.schemaVersion());
            assertEquals(period, first.windows().getFirst().field("displayPeriod"),
                    "Поздний recovery не меняет ранее захваченный owned snapshot");
        }
    }

    /** Восстанавливает настоящий FormSession через координатор и захватывает его настоящим рекордером. */
    private static SessionSnapshot recoverAndSave(SessionSnapshot input, Path memory, String period) {
        var store = new FakeStore("memory");
        var recorder = new SessionRecorder("fx", List.of(store), UiExecutor.direct(), new FakeSource(),
                new FakeScheduler(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 1);
        var shown = new AtomicReference<FormSession>();
        var report = new AtomicReference<RestoreReport>();
        var target = new HeadlessTarget();
        try {
            new RestoreCoordinator().restore(input, target, (prepared, owner, onShown, onFailed) -> {
                assertEquals(WindowType.NEW_PLAN_WIZARD, prepared.type());
                var context = new FormContext(prepared.id(), owner, prepared.context(),
                        FakeStates.empty(ClientProfile.fx("25"), memory));
                var form = new FormSession(prepared.type(), prepared.modal(), new NewPlanWizardForm(),
                        context, new QuietHost());
                form.applyState(prepared);
                form.shown();
                shown.set(form);
                onShown.accept(form);
            }, recorder, report::set);
            assertNotNull(report.get());
            assertTrue(report.get().warnings().isEmpty(), report.get().warnings().toString());
            assertEquals(1, report.get().windowsRestored());
            assertEquals(1, target.loads.get());
            assertEquals(1, target.shows.get());
            assertTrue(recorder.isStarted());
            assertEquals(2, shown.get().state().page());
            assertEquals(period, shown.get().state().value("displayPeriod"));
            assertEquals(Problem.Severity.ERROR, shown.get().view().problem().severity());
            assertFalse(shown.get().view().buttons().get("finish").enabled());
            assertEquals(1, recorder.registeredWindows().size());
            recorder.saveNow();
            assertEquals(1, store.saved.size());
            assertEquals(store.saved.getFirst(), recorder.lastCaptured().orElseThrow());
            return store.saved.getFirst();
        } finally {
            recorder.shutdownClean();
        }
    }

    /** Главный target без GUI: реальный coordinator вызывает последовательность load/apply/show. */
    private static final class HeadlessTarget implements RestoreTarget {
        private final AtomicInteger loads = new AtomicInteger();
        private final AtomicInteger shows = new AtomicInteger();
        /** В этой fixture нет несохранённого финансового плана; его загрузка не подменяется успешным parsing. */
        @Override public void loadPlan(PlanState plan, String path, Consumer<String> warn) {
            assertEquals(PlanState.CLEAN, plan);
            assertEquals("", path);
            loads.incrementAndGet();
        }
        /** Принимает неизменяемую модель главного окна. */
        @Override public void applyMain(MainWindowState main) { assertNotNull(main); }
        /** Фиксирует headless show callback, не нативное окно. */
        @Override public void showMainWindow() { shows.incrementAndGet(); }
        /** Пустой снимок не содержит выбранной строки. */
        @Override public void selectRow(String row) { fail("selectRow"); }
        /** У мастера нет ссылок на существующие финансовые операции. */
        @Override public Set<String> existingTargetIds() { return Set.of(); }
    }

    /** Регистрацией владеет coordinator; GUI и пользовательские commit действия отсутствуют. */
    private static final class QuietHost implements FormSession.Host {
        /** Coordinator зарегистрирует окно после onShown. */
        @Override public void registered(FormSession form) { }
        /** Форму не закрывают во время восстановления. */
        @Override public void unregistered(FormSession form) { fail("unregistered"); }
        /** Применение снимка не является пользовательским вводом. */
        @Override public void touched(FormSession form) { fail("touched"); }
        /** Незавершённый мастер не должен завершаться. */
        @Override public void closed(FormSession form, Object result) { fail("closed"); }
        /** Восстановление мастера не открывает дочерние формы. */
        @Override public void openChild(FormSession form, WindowState state) { fail("openChild"); }
        /** Невалидные данные не должны применяться к плану. */
        @Override public void applied(FormSession form, Object action) { fail("applied"); }
    }
}
