package ru.cashprediction.core.ui.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.DocumentView;
import ru.cashprediction.core.app.SamplePlan;
import ru.cashprediction.core.app.WindowHandle;
import ru.cashprediction.core.app.fake.FakeWindowHandle;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.ForecastEngine;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.io.PlanFileInfo;
import ru.cashprediction.core.markdown.PlanMarkdownReader;
import ru.cashprediction.core.markdown.ReadResult;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.session.SnapshotSchema;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.alert.AlertCatalog;
import ru.cashprediction.core.ui.alert.AlertSession;
import ru.cashprediction.core.ui.alert.AlertSpec;
import ru.cashprediction.core.ui.form.FieldCodec;
import ru.cashprediction.core.ui.form.FieldSpec;
import ru.cashprediction.core.ui.form.FieldView;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormLogic;
import ru.cashprediction.core.ui.form.FormPage;
import ru.cashprediction.core.ui.form.FormRow;
import ru.cashprediction.core.ui.form.FormSession;
import ru.cashprediction.core.ui.form.FormSpec;
import ru.cashprediction.core.ui.form.FormView;
import ru.cashprediction.core.ui.form.Option;
import ru.cashprediction.core.ui.forms.ops.AdjustmentForm;
import ru.cashprediction.core.ui.forms.ops.OneTimeForm;
import ru.cashprediction.core.ui.forms.ops.QuickEditForm;
import ru.cashprediction.core.ui.forms.ops.RuleEditorForm;
import ru.cashprediction.core.ui.forms.plan.GoalCalculatorForm;
import ru.cashprediction.core.ui.forms.plan.NewPlanWizardForm;
import ru.cashprediction.core.ui.forms.plan.PlanSettingsForm;
import ru.cashprediction.core.ui.forms.simple.ChoiceForms;
import ru.cashprediction.core.ui.forms.simple.ConfirmForms;
import ru.cashprediction.core.ui.forms.simple.CsvExportForm;
import ru.cashprediction.core.ui.forms.simple.OpenPlanForm;
import ru.cashprediction.core.ui.forms.simple.TextInputForms;
import ru.cashprediction.core.ui.legacy.LegacyFixtures.Client;
import ru.cashprediction.core.ui.legacy.LegacyFixtures.Fixture;

/**
 * Совместимость снимков сеанса прежних клиентов с интерфейсом ядра (архитектура §1 правило R3, §3.5; этап S1, задача
 * core-legacy-fixtures).
 *
 * <p>После перехода на интерфейс ядра (этап S4) пользователь может запустить новую версию над снимком, записанным
 * прежним клиентом до аварии. Поэтому здесь проверяются настоящие снимки прежних клиентов, записанные на сборке
 * этапа S0.5: откуда и как они получены, описано в {@link LegacyFixtures} (таблица «Источники фикстур»). Кратко:</p>
 * <ul>
 *   <li><b>fx</b> - {@code ui-fx}: самотест {@code FxSelfTest}, окна {@code FxWindowFactory}, значения
 *       {@code FxFieldBinder} + {@code FieldValues}; файлы {@code registry.json} (узел реестра) и {@code session-fx.xml};</li>
 *   <li><b>swing</b> - {@code ui-swing}: самотест {@code SwingSelfTest}, окна {@code SwingWindowFactory}, значения
 *       {@code SwingFieldBinder}, {@code MoneyField}, {@code DateField}; {@code registry.json} и {@code session-swing.xml};</li>
 *   <li><b>web</b> - {@code web}: {@code SessionApi} + {@code WebWindow} на сервере, тела запросов как у {@code windows.js},
 *       {@code forms.js} и {@code util.js} браузера; {@code web-session.md} и {@code web-session.plan.md}.</li>
 * </ul>
 *
 * <p>Проверки, которым хватает ядра этапа S0, работают сразу: каждая фикстура на месте и читается кодеком своего
 * хранилища, окна пользуются общим словарём {@link WindowType}, каталог покрывает каждый тип окна и каждое назначение
 * каждого клиента, а значения полей записаны именно в тех формах, которые описывает таблица прежних значений.
 * Проверки новых форм отключены до слияния задач S1 core-forms-framework, core-forms-plan и core-forms-ops: интегратор
 * снимает {@link Disabled} после слияния (критерий готовности задачи: тест зелёный на ветке интеграции S1).</p>
 */
class LegacySnapshotCompatTest {

    /** «Сегодня» при записи фикстур (сохранено 2026-09-13T22:2xZ, по местному времени +05:00 - 14.09.2026). */
    private static final LocalDate RECORDED_TODAY = LocalDate.of(2026, 9, 14);

    /** Коэффициент доходов «Доходы −10 %», включённый перед записью {@code fx/alert-apply-what-if} и {@code web/…}. */
    private static final BigDecimal WHAT_IF_INCOME = new BigDecimal("0.9");

    /** Время изменения плана «Семейный бюджет» для списка «Открыть план» (значение не проверяется). */
    private static final FileTime SEED_MODIFIED = FileTime.from(Instant.parse("2026-09-13T22:20:00Z"));

    /** @return все фикстуры каталога */
    static Stream<Fixture> fixtures() {
        return LegacyFixtures.all().stream();
    }

    /** @return фикстуры с окнами форм (все, кроме подтверждений ALERT) */
    static Stream<Fixture> formFixtures() {
        return fixtures().filter(f -> f.type() != WindowType.ALERT);
    }

    /** @return фикстуры подтверждений ALERT */
    static Stream<Fixture> alertFixtures() {
        return fixtures().filter(f -> f.type() == WindowType.ALERT);
    }

    // ================================================================== ядро S0: чтение и словарь

    /**
     * Файлы фикстуры на месте; каждое хранилище читается своим кодеком; у настольных клиентов реестр и XML содержат
     * один и тот же снимок; окна, назначение и план - те, что открывал сценарий записи.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void everyFixtureIsPresentAndDecodes(Fixture fixture) throws Exception {
        for (String file : fixture.requiredFiles()) {
            assertTrue(LegacyFixtures.exists(fixture.dir() + file), "missing " + fixture.dir() + file);
        }
        Map<String, SessionSnapshot> byStore = LegacyFixtures.snapshots(fixture);
        for (Map.Entry<String, SessionSnapshot> entry : byStore.entrySet()) {
            SessionSnapshot snapshot = entry.getValue();
            assertEquals(SnapshotSchema.CURRENT, snapshot.schemaVersion(), fixture + " " + entry.getKey());
            assertEquals(fixture.client().id(), snapshot.client(), fixture + " " + entry.getKey());
        }
        if (fixture.client().desktop()) {
            // Оба хранилища пишет один вызов записи сеанса: расхождение означало бы порчу одной из копий фикстуры.
            assertTrue(byStore.get(LegacyFixtures.STORE_REGISTRY).sameContent(byStore.get(LegacyFixtures.STORE_XML)),
                    fixture + ": registry JSON and XML differ");
        }
        SessionSnapshot snapshot = LegacyFixtures.snapshot(fixture);
        assertEquals(fixture.windowTypes(), snapshot.windows().stream().map(WindowState::type).toList(), fixture.toString());
        assertEquals(fixture.purpose(), snapshot.windows().getFirst().contextValue(WindowType.CONTEXT_PURPOSE),
                fixture.toString());
        if (fixture.samplePlan()) {
            assertTrue(snapshot.plan().dirty(), fixture + ": unsaved sample plan expected");
            ReadResult plan = PlanMarkdownReader.read(snapshot.plan().markdown(), LegacyFixtures.SAMPLE_PLAN_NAME, RECORDED_TODAY);
            assertFalse(plan.hasErrors(), fixture + ": " + plan.diagnostics());
            assertEquals(LegacyFixtures.SAMPLE_PLAN_NAME, plan.plan().name());
        } else {
            assertFalse(snapshot.plan().dirty(), fixture + ": saved seed plan expected");
            assertTrue(snapshot.main().planPath().endsWith(LegacyFixtures.SEED_PLAN_NAME + ".md"),
                    fixture + ": " + snapshot.main().planPath());
        }
    }

    /**
     * Прежние клиенты пишут окна общим словарём: известный тип, модальность типа по умолчанию, владелец - главное окно или
     * ранее открытое окно, ключи контекста и id полей только из {@link WindowType}.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void windowsUseTheSharedWindowVocabulary(Fixture fixture) throws Exception {
        List<String> earlier = new ArrayList<>();
        for (WindowState window : LegacyFixtures.snapshot(fixture).windows()) {
            WindowType type = window.type();
            assertNotNull(type, fixture + " " + window.id());
            assertEquals(type.defaultModal(), window.modal(), fixture + " " + window.id() + " modal");
            assertTrue(WindowState.MAIN_OWNER.equals(window.ownerId()) || earlier.contains(window.ownerId()),
                    fixture + " " + window.id() + " owner " + window.ownerId());
            assertTrue(type.contextKeys().containsAll(window.context().keySet()),
                    fixture + " " + window.id() + " context " + window.context().keySet() + " not in " + type.contextKeys());
            assertTrue(type.fieldIds().containsAll(window.fields().keySet()),
                    fixture + " " + window.id() + " fields " + window.fields().keySet() + " not in " + type.fieldIds());
            earlier.add(window.id());
        }
    }

    /**
     * Каталог покрывает у каждого клиента все 11 типов окон и каждое назначение TEXT_INPUT, CHOICE и ALERT, которое этот
     * клиент умеет восстанавливать (исключения описаны в {@link LegacyFixtures}); у каждого окна с полями есть и
     * случай с некорректными значениями.
     */
    @Test
    void catalogueCoversEveryWindowTypeAndPurposeOfEachClient() {
        Map<Client, Set<String>> expectedPurposes = Map.of(
                Client.FX, purposes(List.of("rename", "reconcile", "customMonths", "customCurrency"),
                        List.of("currency", "openPlan"), AlertCatalog.RESTORABLE_PURPOSES),
                Client.SWING, purposes(List.of("rename", "reconcile", "customMonths", "customCurrency"),
                        List.of("currency", "openPlan"), List.of("deleteRule", "deleteOneTime", "actualize", "clearSnapshots")),
                Client.WEB, purposes(List.of("rename", "reconcile", "customCurrency"),
                        List.of("currency", "openPlan"), List.of("deleteRule", "deleteOneTime", "actualize", "applyWhatIf")));
        for (Client client : Client.values()) {
            List<Fixture> own = LegacyFixtures.all().stream().filter(f -> f.client() == client).toList();
            Set<WindowType> types = own.stream().flatMap(f -> f.windowTypes().stream())
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(WindowType.class)));
            assertEquals(EnumSet.allOf(WindowType.class), types, client.id());
            Set<String> purposes = own.stream().filter(f -> !f.purpose().isEmpty())
                    .map(f -> f.type() + ":" + f.purpose()).collect(Collectors.toCollection(TreeSet::new));
            assertEquals(new TreeSet<>(expectedPurposes.get(client)), purposes, client.id());
            for (Fixture fixture : own) {
                boolean hasFields = !fixture.type().fieldIds().isEmpty() && fixture.type() != WindowType.CHOICE
                        && fixture.type() != WindowType.CSV_EXPORT && fixture.windowTypes().size() == 1;
                if (hasFields && !fixture.name().endsWith("-invalid")) {
                    String prefix = fixture.name().replace("-create", "");
                    assertTrue(own.stream().anyMatch(f -> f.name().startsWith(prefix) && f.name().endsWith("-invalid")),
                            client.id() + ": no invalid-value fixture for " + fixture.name());
                }
            }
        }
    }

    /**
     * Значения полей записаны в тех формах, которые прежние клиенты действительно пишут: корректные суммы
     * {@code Money.formatPlain}, даты ISO, некорректный ввод дословно, коды вариантов прежних клиентов. Таблица -
     * документация прежних форм для задач S2 и S4 и защита фикстур от случайной правки.
     */
    @Test
    void recordedValuesKeepTheLegacyFormsOfEachClient() throws Exception {
        List<String> mismatches = new ArrayList<>();
        for (LegacyValue expected : legacyValues()) {
            SessionSnapshot snapshot = LegacyFixtures.snapshot(LegacyFixtures.find(expected.client(), expected.fixture()));
            WindowState window = snapshot.windows().get(expected.window());
            String actual = window.fields().get(expected.field());
            if (!expected.value().equals(actual)) {
                mismatches.add(expected + " but was «" + actual + "»");
            }
        }
        assertTrue(mismatches.isEmpty(), String.join("\n", mismatches));
    }

    // ================================================================== S1 core-forms-framework: FieldCodec.acceptLegacy

    /**
     * {@link FieldCodec#acceptLegacy} переводит прежние значения в канонические формы нового интерфейса: суммы, даты,
     * дни года, флажки и целые приводятся, некорректный ввод остаётся дословно, прежние коды становятся кодами новых
     * форм ({@code MOVE_DATE} → {@code MOVE}, табуляция → {@code TAB}, «другая…» / {@code __other__} → «своя валюта»,
     * «Из файла…» / {@code __file__} → «из файла»).
     */
    @Disabled("S1 core-forms-framework: FieldCodec.acceptLegacy (enable after the S1 form tasks merge)")
    @Test
    void acceptLegacyYieldsCanonicalValues() throws Exception {
        List<String> mismatches = new ArrayList<>();
        for (LegacyValue expected : canonicalValues()) {
            WindowState window = LegacyFixtures.snapshot(LegacyFixtures.find(expected.client(), expected.fixture()))
                    .windows().get(expected.window());
            String actual = FieldCodec.acceptLegacy(window.type(), expected.field(), window.field(expected.field()));
            if (!expected.value().equals(actual)) {
                mismatches.add(expected + " but was «" + actual + "»");
            }
        }
        assertTrue(mismatches.isEmpty(), String.join("\n", mismatches));
    }

    /**
     * Приведение идемпотентно для всех полей всех фикстур: каноническое значение, пропущенное через
     * {@link FieldCodec#acceptLegacy} ещё раз, не меняется (так восстановление после восстановления не портит ввод).
     */
    @Disabled("S1 core-forms-framework: FieldCodec.acceptLegacy (enable after the S1 form tasks merge)")
    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void acceptLegacyIsIdempotent(Fixture fixture) throws Exception {
        for (WindowState window : LegacyFixtures.snapshot(fixture).windows()) {
            window.fields().forEach((field, value) -> {
                String once = FieldCodec.acceptLegacy(window.type(), field, value);
                assertEquals(once, FieldCodec.acceptLegacy(window.type(), field, once), fixture + " " + field);
            });
        }
    }

    // ================================================================== S1 формы: FormSession.applyState

    /**
     * Новая форма восстанавливает окно прежнего клиента: {@code applyState} с приведёнными значениями даёт те же значения
     * в {@code FormState} и в {@code captureState}, ту же страницу мастера и те же контекст и границы; сеанс не бросает
     * исключений ни на корректном, ни на некорректном вводе.
     */
    @Disabled("S1 core-forms-framework + core-forms-plan + core-forms-ops: FormSession.applyState and the form logics"
            + " (enable after the S1 form tasks merge)")
    @ParameterizedTest(name = "{0}")
    @MethodSource("formFixtures")
    void applyStateRestoresLegacyWindowsInTheNewForms(Fixture fixture, @TempDir Path dir) throws Exception {
        SessionSnapshot snapshot = LegacyFixtures.snapshot(fixture);
        AppState app = appState(fixture, snapshot, cashMemory(dir));
        for (WindowState legacy : snapshot.windows()) {
            WindowState canonical = canonicalWindow(legacy);
            Restored restored = restore(fixture, canonical, app);
            assertEquals(canonical.fields(), pick(restored.session().state().values(), canonical.fields().keySet()),
                    fixture + " " + legacy.id() + " state");
            WindowState captured = restored.session().captureState();
            assertEquals(canonical.fields(), pick(captured.fields(), canonical.fields().keySet()), fixture + " capture");
            assertEquals(legacy.type(), captured.type());
            assertEquals(legacy.ownerId(), captured.ownerId());
            assertEquals(legacy.bounds(), captured.bounds(), fixture + " bounds");
            for (String key : legacy.context().keySet()) {
                assertEquals(legacy.contextValue(key), captured.contextValue(key), fixture + " context " + key);
            }
            if (legacy.type() == WindowType.NEW_PLAN_WIZARD) {
                assertEquals(Integer.parseInt(legacy.contextValue(WindowType.CONTEXT_PAGE)), restored.view().page(), fixture + " page");
            }
        }
    }

    /**
     * Восстановленная форма показывает прежний ввод текстами нового интерфейса: суммы «95 000,00», даты «05.10.2026»,
     * день года «15.03», некорректный ввод - дословно (спецификация v2, §6.0 «Закрытый набор элементов формы»).
     */
    @Disabled("S1 core-forms-framework + core-forms-plan + core-forms-ops: FormSession.applyState and FormView field texts"
            + " (enable after the S1 form tasks merge)")
    @Test
    void applyStateShowsExpectedDisplayTexts(@TempDir Path dir) throws Exception {
        List<String> mismatches = new ArrayList<>();
        for (LegacyValue expected : displayTexts()) {
            Fixture fixture = LegacyFixtures.find(expected.client(), expected.fixture());
            SessionSnapshot snapshot = LegacyFixtures.snapshot(fixture);
            WindowState canonical = canonicalWindow(snapshot.windows().get(expected.window()));
            Restored restored = restore(fixture, canonical, appState(fixture, snapshot, cashMemory(dir)));
            FieldView field = restored.view().fields().get(expected.field());
            String actual = field == null ? null : field.value();
            if (!expected.value().equals(actual)) {
                mismatches.add(expected + " but was «" + actual + "»");
            }
            String kindText = fieldSpec(restored.spec(), expected.field()).map(s -> s.kind().name()).orElse("?");
            assertFalse(kindText.equals("?"), expected + ": no such field in the new form");
        }
        assertTrue(mismatches.isEmpty(), String.join("\n", mismatches));
    }

    /**
     * Выбор в прежних окнах CHOICE попадает в варианты новых форм: валюта «$», «другая…» ({@code __other__} у web) -
     * вариант {@link ChoiceForms#CUSTOM}; «Открыть план» по имени плана - вариант этого плана, «Из файла…»
     * ({@code __file__}) - {@link OpenPlanForm#FROM_FILE}.
     */
    @Disabled("S1 core-forms-framework: ChoiceForms.currency, OpenPlanForm and FieldCodec.acceptLegacy"
            + " (enable after the S1 form tasks merge)")
    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void legacyChoiceValuesSelectTheNewOptions(Fixture fixture, @TempDir Path dir) throws Exception {
        if (fixture.type() != WindowType.CHOICE) {
            return;
        }
        SessionSnapshot snapshot = LegacyFixtures.snapshot(fixture);
        WindowState canonical = canonicalWindow(snapshot.windows().getFirst());
        Restored restored = restore(fixture, canonical, appState(fixture, snapshot, cashMemory(dir)));
        String value = restored.session().state().value("value");
        List<Option> options = options(restored, "value");
        Option selected = options.stream().filter(o -> o.value().equals(value)).findFirst().orElse(null);
        assertNotNull(selected, fixture + ": value «" + value + "» is not an option of " + options);
        switch (fixture.name()) {
            case "choice-currency" -> assertEquals("$", value, fixture.toString());
            case "choice-currency-other" -> assertEquals(ChoiceForms.CUSTOM, value, fixture.toString());
            case "choice-open-plan" -> assertTrue(selected.text().startsWith(LegacyFixtures.SEED_PLAN_NAME), selected.text());
            case "choice-open-plan-from-file" -> assertEquals(OpenPlanForm.FROM_FILE, value, fixture.toString());
            default -> throw new IllegalStateException("Unexpected CHOICE fixture " + fixture);
        }
    }

    /**
     * Прежнее подтверждение пересоздаётся по назначению и цели ({@code ConfirmForms}), восстанавливаемо и записывается в
     * снимок с тем же контекстом и без полей ({@code AlertSession.captureState}).
     */
    @Disabled("S1 core-forms-framework: ConfirmForms, AlertCatalog and AlertSession (enable after the S1 form tasks merge)")
    @ParameterizedTest(name = "{0}")
    @MethodSource("alertFixtures")
    void legacyAlertsAreRecreatedFromPurposeAndTarget(Fixture fixture, @TempDir Path dir) throws Exception {
        SessionSnapshot snapshot = LegacyFixtures.snapshot(fixture);
        AppState app = appState(fixture, snapshot, cashMemory(dir));
        WindowState legacy = snapshot.windows().getFirst();
        String purpose = legacy.contextValue(WindowType.CONTEXT_PURPOSE);
        String targetId = legacy.contextValue(WindowType.CONTEXT_TARGET_ID);
        Optional<ConfirmForms.Confirmation> confirmation = switch (purpose) {
            case "deleteRule" -> ConfirmForms.deleteRule(app, targetId);
            case "deleteOneTime" -> ConfirmForms.deleteOneTime(app, targetId);
            case "actualize" -> ConfirmForms.actualize(app);
            case "applyWhatIf" -> ConfirmForms.applyWhatIf(app);
            case "clearSnapshots" -> Optional.of(ConfirmForms.clearSnapshots());
            default -> throw new IllegalStateException("Unexpected ALERT purpose " + purpose);
        };
        assertTrue(confirmation.isPresent(), fixture + ": confirmation expected");
        AlertSpec spec = confirmation.get().spec();
        assertEquals(purpose, spec.purpose(), fixture.toString());
        assertEquals(targetId, spec.targetId(), fixture.toString());
        assertTrue(spec.restorable(), fixture.toString());
        AlertSession session = new AlertSession(legacy.id(), legacy.ownerId(), spec, new AlertHost());
        session.applyState(legacy);
        WindowState captured = session.captureState();
        assertEquals(WindowType.ALERT, captured.type());
        assertEquals(purpose, captured.contextValue(WindowType.CONTEXT_PURPOSE));
        assertEquals(targetId, captured.contextValue(WindowType.CONTEXT_TARGET_ID));
        assertEquals(Map.of(), captured.fields());
    }

    // ================================================================== таблицы значений

    /**
     * Ожидаемое значение поля окна фикстуры.
     *
     * @param client  клиент
     * @param fixture имя случая
     * @param window  номер окна в снимке
     * @param field   id поля
     * @param value   значение
     */
    record LegacyValue(Client client, String fixture, int window, String field, String value) {

        @Override
        public String toString() {
            return client.id() + "/" + fixture + " w#" + window + " " + field + ": expected «" + value + "»";
        }
    }

    private static LegacyValue v(Client client, String fixture, String field, String value) {
        return new LegacyValue(client, fixture, 0, field, value);
    }

    /** @return значения, как их записали прежние клиенты (см. {@link #recordedValuesKeepTheLegacyFormsOfEachClient}) */
    private static List<LegacyValue> legacyValues() {
        return List.of(
                // FX: FieldValues - корректная сумма formatPlain, дата ISO, некорректный текст дословно (Money.parse строг, L1).
                v(Client.FX, "new-plan-wizard", "startBalance", "120000,00"),
                v(Client.FX, "new-plan-wizard", "startDate", "2026-10-01"),
                v(Client.FX, "new-plan-wizard", "horizonKind", "YEARS"),
                // DatePicker JavaFX нормализует несуществующую дату ещё в виджете, до записи снимка.
                v(Client.FX, "new-plan-wizard-invalid", "startDate", "2026-02-28"),
                v(Client.FX, "new-plan-wizard-invalid", "startBalance", "12 3"),
                v(Client.FX, "new-plan-wizard-invalid", "cushion", "-500,00"),
                v(Client.FX, "rule-editor-create", "monthDay", "03-15"),
                v(Client.FX, "rule-editor-create", "weekday", "TUESDAY"),
                v(Client.FX, "rule-editor-create", "fromEnabled", "true"),
                v(Client.FX, "rule-editor-edit-invalid", "amount", "45 00"),
                v(Client.FX, "rule-editor-edit-invalid", "monthDay", "31.02"),
                v(Client.FX, "rule-editor-edit-invalid", "from", "2026-02-30"),
                v(Client.FX, "adjustment-editor", "action", "REPLACE"),
                v(Client.FX, "adjustment-editor-invalid", "action", "MOVE_DATE"),
                v(Client.FX, "adjustment-editor-invalid", "amount", "-1 0"),
                v(Client.FX, "goal-calculator-invalid", "byDate", "2027-02-29"),
                v(Client.FX, "text-input-reconcile", "value", "171234,56"),
                v(Client.FX, "text-input-reconcile-invalid", "value", "12 3"),
                v(Client.FX, "choice-currency-other", "value", "другая…"),
                v(Client.FX, "choice-open-plan", "value", LegacyFixtures.SEED_PLAN_NAME),
                v(Client.FX, "choice-open-plan-from-file", "value", "Из файла…"),
                v(Client.FX, "quick-edit-popup", "amount", "95000,00"),
                v(Client.FX, "quick-edit-popup-invalid", "amount", "95 0"),
                // Swing: те же канонические формы через MoneyField/DateField.
                v(Client.SWING, "one-time-create", "amount", "85000,00"),
                v(Client.SWING, "one-time-create", "date", "2026-11-20"),
                v(Client.SWING, "one-time-edit-invalid", "date", "20.13.2026"),
                v(Client.SWING, "adjustment-editor-invalid", "action", "MOVE_DATE"),
                v(Client.SWING, "choice-currency-other", "value", "другая…"),
                v(Client.SWING, "csv-export", "separator", "TAB"),
                v(Client.SWING, "quick-edit-popup-invalid", "amount", "95 0"),
                // Web: canonicalMoney браузера убирает любые пробелы («12 3» стало бы суммой), поэтому некорректный ввод -
                // повторённая запятая; неверное число в <input type=number> браузер отдаёт пустой строкой.
                v(Client.WEB, "new-plan-wizard-invalid", "startBalance", "12,3,4"),
                v(Client.WEB, "new-plan-wizard-invalid", "horizonValue", ""),
                v(Client.WEB, "rule-editor-edit-invalid", "monthDay", "31.02"),
                v(Client.WEB, "rule-editor-edit-invalid", "until", "abc"),
                v(Client.WEB, "text-input-reconcile-invalid", "value", "1,234"),
                v(Client.WEB, "choice-currency-other", "value", "__other__"),
                v(Client.WEB, "choice-open-plan", "value", "name:" + LegacyFixtures.SEED_PLAN_NAME),
                v(Client.WEB, "choice-open-plan-from-file", "value", "__file__"),
                v(Client.WEB, "adjustment-editor-invalid", "action", "MOVE_DATE"),
                v(Client.WEB, "quick-edit-popup-invalid", "amount", "95,0,0"),
                new LegacyValue(Client.WEB, "nested-rule-adjustment-goal", 1, "action", "SKIP"));
    }

    /** @return канонические значения нового интерфейса (см. {@link #acceptLegacyYieldsCanonicalValues}) */
    private static List<LegacyValue> canonicalValues() {
        return List.of(
                v(Client.FX, "new-plan-wizard", "startBalance", "120000,00"),
                v(Client.FX, "new-plan-wizard", "horizonKind", "YEARS"),
                v(Client.FX, "new-plan-wizard-invalid", "startDate", "2026-02-28"),
                v(Client.FX, "new-plan-wizard-invalid", "startBalance", "12 3"),
                v(Client.FX, "new-plan-wizard-invalid", "cushion", "-500,00"),
                v(Client.WEB, "new-plan-wizard-invalid", "startBalance", "12,3,4"),
                v(Client.WEB, "new-plan-wizard-invalid", "horizonValue", ""),
                v(Client.FX, "rule-editor-create", "monthDay", "03-15"),
                v(Client.FX, "rule-editor-create", "weekday", "TUESDAY"),
                v(Client.FX, "rule-editor-create", "weekendPolicy", "NEXT_BUSINESS_DAY"),
                v(Client.FX, "rule-editor-create", "everyN", "2"),
                v(Client.FX, "rule-editor-create", "enabled", "true"),
                v(Client.WEB, "rule-editor-create", "monthDay", "03-15"),
                v(Client.WEB, "rule-editor-create", "from", "2026-09-15"),
                v(Client.FX, "rule-editor-edit-invalid", "monthDay", "31.02"),
                v(Client.FX, "rule-editor-edit-invalid", "amount", "45 00"),
                v(Client.FX, "adjustment-editor", "action", "REPLACE"),
                v(Client.FX, "adjustment-editor", "amount", "95000,00"),
                v(Client.FX, "adjustment-editor-invalid", "action", "MOVE"),
                v(Client.SWING, "adjustment-editor-invalid", "action", "MOVE"),
                v(Client.WEB, "adjustment-editor-invalid", "action", "MOVE"),
                v(Client.WEB, "goal-calculator", "byDateEnabled", "true"),
                v(Client.WEB, "goal-calculator", "extraSaving", "5000,00"),
                v(Client.FX, "csv-export", "separator", "TAB"),
                v(Client.FX, "csv-export", "bom", "false"),
                v(Client.FX, "csv-export", "range", "ALL"),
                v(Client.SWING, "csv-export", "separator", "TAB"),
                v(Client.WEB, "csv-export", "separator", "TAB"),
                v(Client.FX, "quick-edit-popup-invalid", "amount", "95 0"),
                v(Client.WEB, "quick-edit-popup", "amount", "95000,00"),
                v(Client.FX, "text-input-reconcile", "value", "171234,56"),
                v(Client.FX, "text-input-custom-months", "value", "24"),
                v(Client.WEB, "text-input-reconcile-invalid", "value", "1,234"),
                v(Client.FX, "choice-currency-other", "value", ChoiceForms.CUSTOM),
                v(Client.SWING, "choice-currency-other", "value", ChoiceForms.CUSTOM),
                v(Client.WEB, "choice-currency-other", "value", ChoiceForms.CUSTOM),
                v(Client.FX, "choice-open-plan-from-file", "value", OpenPlanForm.FROM_FILE),
                v(Client.SWING, "choice-open-plan-from-file", "value", OpenPlanForm.FROM_FILE),
                v(Client.WEB, "choice-open-plan-from-file", "value", OpenPlanForm.FROM_FILE));
    }

    /** @return тексты полей восстановленных форм (см. {@link #applyStateShowsExpectedDisplayTexts}) */
    private static List<LegacyValue> displayTexts() {
        return List.of(
                v(Client.FX, "new-plan-wizard", "startBalance", "120 000,00"),
                v(Client.FX, "new-plan-wizard", "cushion", "30 000,00"),
                v(Client.FX, "new-plan-wizard", "startDate", "01.10.2026"),
                v(Client.FX, "rule-editor-create", "amount", "3 500,00"),
                v(Client.FX, "rule-editor-create", "monthDay", "15.03"),
                v(Client.FX, "rule-editor-create", "from", "15.09.2026"),
                v(Client.FX, "rule-editor-edit-invalid", "amount", "45 00"),
                v(Client.FX, "rule-editor-edit-invalid", "monthDay", "31.02"),
                v(Client.FX, "rule-editor-edit-invalid", "from", "2026-02-30"),
                v(Client.FX, "adjustment-editor", "amount", "95 000,00"),
                v(Client.FX, "adjustment-editor", "date", "07.10.2026"),
                v(Client.FX, "quick-edit-popup-invalid", "amount", "95 0"),
                v(Client.FX, "text-input-reconcile", "value", "171 234,56"),
                v(Client.SWING, "one-time-create", "date", "20.11.2026"),
                v(Client.SWING, "one-time-create", "amount", "85 000,00"),
                v(Client.SWING, "quick-edit-popup", "amount", "95 000,00"),
                v(Client.WEB, "goal-calculator", "target", "500 000,00"),
                v(Client.WEB, "goal-calculator", "byDate", "31.08.2027"),
                v(Client.WEB, "goal-calculator", "extraSaving", "5 000,00"),
                v(Client.WEB, "new-plan-wizard-invalid", "startBalance", "12,3,4"),
                v(Client.WEB, "new-plan-wizard-invalid", "startDate", "31.02.2026"),
                v(Client.WEB, "plan-settings", "goalDate", "01.06.2027"),
                v(Client.WEB, "plan-settings", "horizonUntil", "31.12.2027"));
    }

    // ================================================================== помощники

    private static Set<String> purposes(List<String> textInput, List<String> choice, List<String> alert) {
        Set<String> result = new TreeSet<>();
        textInput.forEach(p -> result.add(WindowType.TEXT_INPUT + ":" + p));
        choice.forEach(p -> result.add(WindowType.CHOICE + ":" + p));
        alert.forEach(p -> result.add(WindowType.ALERT + ":" + p));
        return result;
    }

    /** Окно с полями, приведёнными {@link FieldCodec#acceptLegacy} (так их передаст восстановление ядра). */
    private static WindowState canonicalWindow(WindowState legacy) {
        Map<String, String> fields = new LinkedHashMap<>();
        legacy.fields().forEach((id, value) -> fields.put(id, FieldCodec.acceptLegacy(legacy.type(), id, value)));
        return legacy.withFields(fields);
    }

    /**
     * Значения только указанных полей (новая форма может хранить и служебные поля, которых не было в прежнем снимке).
     *
     * @return поле → значение в порядке имён; отсутствующее поле - {@code null}
     */
    private static Map<String, String> pick(Map<String, String> values, Set<String> keys) {
        Map<String, String> result = new TreeMap<>();
        keys.forEach(key -> result.put(key, values.get(key)));
        return result;
    }

    /**
     * Восстановленная форма.
     *
     * @param session сеанс формы
     * @param spec    раскладка
     * @param view    модель после восстановления
     */
    private record Restored(FormSession session, FormSpec spec, FormView view) {
    }

    /**
     * Восстанавливает окно тем же порядком, что {@code CoreWindowFactory} (архитектура §3.8): сеанс, {@code applyState},
     * раскладка и модель для {@code UiPort.openForm}, затем ручка окна.
     */
    private static Restored restore(Fixture fixture, WindowState state, AppState app) {
        FormContext context = new FormContext(state.id(), state.ownerId(), state.context(), app);
        FormSession session = new FormSession(state.type(), state.modal(), logic(state, app), context, new FormHost());
        session.applyState(state);
        FormSpec spec = session.spec();
        FormView view = session.view();
        WindowHandle handle = new FakeWindowHandle(fixture + "/" + state.id(), state.bounds());
        session.attach(handle);
        return new Restored(session, spec, view);
    }

    /** Логика новой формы для типа и назначения окна (как {@code FormCatalog.forRestore}, этап S2). */
    private static FormLogic logic(WindowState state, AppState app) {
        String purpose = state.contextValue(WindowType.CONTEXT_PURPOSE);
        return switch (state.type()) {
            case NEW_PLAN_WIZARD -> new NewPlanWizardForm();
            case PLAN_SETTINGS -> new PlanSettingsForm();
            case RULE_EDITOR -> new RuleEditorForm();
            case ONE_TIME_EDITOR -> new OneTimeForm();
            case ADJUSTMENT_EDITOR -> new AdjustmentForm();
            case GOAL_CALCULATOR -> new GoalCalculatorForm();
            case TEXT_INPUT -> TextInputForms.forPurpose(purpose);
            case CHOICE -> OpenPlanForm.PURPOSE.equals(purpose)
                    ? new OpenPlanForm(List.of(new PlanFileInfo(LegacyFixtures.SEED_PLAN_NAME,
                            app.cashMemory().resolve(LegacyFixtures.SEED_PLAN_NAME + ".md"), SEED_MODIFIED)))
                    : ChoiceForms.currency();
            case CSV_EXPORT -> new CsvExportForm();
            case QUICK_EDIT_POPUP -> new QuickEditForm();
            case ALERT -> throw new IllegalArgumentException("ALERT windows are restored through ConfirmForms");
        };
    }

    /**
     * Состояние приложения на момент записи фикстуры: план из снимка (или сохранённый «Семейный бюджет»), прогноз,
     * «что-если» для подтверждения applyWhatIf.
     */
    private static AppState appState(Fixture fixture, SessionSnapshot snapshot, Path cashMemory) {
        Plan plan = fixture.samplePlan()
                ? PlanMarkdownReader.read(snapshot.plan().markdown(), LegacyFixtures.SAMPLE_PLAN_NAME, RECORDED_TODAY).plan()
                : SamplePlan.create(RECORDED_TODAY).withName(LegacyFixtures.SEED_PLAN_NAME);
        ViewState view = ViewState.defaults();
        if ("applyWhatIf".equals(fixture.purpose())) {
            view = view.withWhatIf(WhatIf.NONE.withIncomeFactor(WHAT_IF_INCOME));
        }
        Forecast forecast = ForecastEngine.forecast(plan, view.whatIf(), RECORDED_TODAY, view.showSkipped());
        Path file = fixture.samplePlan() ? null : cashMemory.resolve(LegacyFixtures.SEED_PLAN_NAME + ".md");
        DocumentView document = new DocumentView(plan, file, fixture.samplePlan(), false, "", false, "", forecast, "", List.of());
        return new AppState(1, profile(fixture.client()), RECORDED_TODAY, cashMemory, null, document, view, "", false,
                AppSettings.defaults(), null, List.of(), null, null, "");
    }

    private static ClientProfile profile(Client client) {
        return switch (client) {
            case FX -> ClientProfile.fx("25.0.4");
            case SWING -> ClientProfile.swing();
            case WEB -> ClientProfile.web();
        };
    }

    /** Папка CashMemory с сохранённым планом «Семейный бюджет» (для «Открыть план»). */
    private static Path cashMemory(Path dir) throws IOException {
        Path cashMemory = Files.createDirectories(dir.resolve("CashMemory"));
        Path seed = cashMemory.resolve(LegacyFixtures.SEED_PLAN_NAME + ".md");
        if (!Files.exists(seed)) {
            Files.writeString(seed, "# " + LegacyFixtures.SEED_PLAN_NAME + "\n", StandardCharsets.UTF_8);
        }
        return cashMemory;
    }

    private static Optional<FieldSpec> fieldSpec(FormSpec spec, String fieldId) {
        for (FormPage page : spec.pages()) {
            for (FormRow row : page.rows()) {
                List<FieldSpec> fields = switch (row) {
                    case FormRow.Field field -> List.of(field.field());
                    case FormRow.Inline inline -> inline.fields();
                    case FormRow.SideColumn side -> List.of(side.preview());
                    default -> List.of();
                };
                for (FieldSpec field : fields) {
                    if (field != null && field.id().equals(fieldId)) {
                        return Optional.of(field);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static List<Option> options(Restored restored, String fieldId) {
        FieldView view = restored.view().fields().get(fieldId);
        if (view != null && view.options() != null) {
            return view.options();
        }
        return fieldSpec(restored.spec(), fieldId).map(FieldSpec::options).orElse(List.of());
    }

    /** Контроллер форм для теста: восстановление не должно открывать дочерние окна и закрывать форму. */
    private static final class FormHost implements FormSession.Host {

        @Override
        public void registered(FormSession session) {
            // Регистрация в записи сеанса здесь не проверяется: это делает FormSessionTest задачи core-forms-framework.
        }

        @Override
        public void unregistered(FormSession session) {
            // См. registered.
        }

        @Override
        public void touched(FormSession session) {
            // Восстановление может сообщить об изменении: запись сама откладывает сохранение.
        }

        @Override
        public void closed(FormSession session, Object result) {
            throw new AssertionError("restored form closed itself: " + result);
        }

        @Override
        public void openChild(FormSession parent, WindowState child) {
            throw new AssertionError("restored form opened a child window: " + child);
        }

        @Override
        public void applied(FormSession session, Object action) {
            throw new AssertionError("restored form applied an action: " + action);
        }
    }

    /** Контроллер подтверждений для теста. */
    private static final class AlertHost implements AlertSession.Host {

        @Override
        public void registered(AlertSession session) {
            // Регистрацию проверяет AlertSession-тест задачи core-forms-framework.
        }

        @Override
        public void unregistered(AlertSession session) {
            // См. registered.
        }
    }
}
