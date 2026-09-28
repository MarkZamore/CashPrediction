package ru.cashprediction.core.ui.legacy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import ru.cashprediction.core.session.PlanState;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.session.codec.JsonSnapshotCodec;
import ru.cashprediction.core.session.codec.MarkdownSnapshotCodec;
import ru.cashprediction.core.session.codec.SnapshotFormatException;
import ru.cashprediction.core.session.codec.XmlSnapshotCodec;

/**
 * Каталог фикстур снимков сеанса, записанных прежними клиентами (этап S1, задача core-legacy-fixtures).
 *
 * <h2>Что это и откуда</h2>
 *
 * <p>Фикстуры лежат в {@code core/src/test/resources/session/legacy/<клиент>/<случай>/}. Каждая - настоящий снимок
 * аварийно завершённого прежнего клиента: 14.09.2026 неизменённые клиенты запускались из классов сборки этапа S0.5
 * (коммит {@code ab3a5c0}) с изолированной папкой {@code -Dcashprediction.home} и узлом реестра
 * {@code -Dcashprediction.registry.node=ru/cashprediction/selftest/<uuid>}. Открывалось одно окно (или цепочка окон),
 * в настоящие поля вписывались типичные или некорректные значения, затем снимок принудительно записывался и процесс
 * завершался {@code Runtime.halt(3)}. Тестовые узлы реестра после записи удалены; поддерево настоящих узлов
 * {@code ru/cashprediction/session} до и после записи совпало ключ в ключ и значение в значение.</p>
 *
 * <table>
 *   <caption>Источники фикстур</caption>
 *   <tr><th>Клиент</th><th>Файлы случая</th><th>Прежний код, который их записал</th></tr>
 *   <tr><td>fx</td>
 *       <td>{@code registry.json} - склеенные куски {@code snapshot.N} узла реестра (CRC32 сверен);
 *           {@code session-fx.xml} - XML-файл из CashMemory; {@code selftest.txt} - сценарий самотеста</td>
 *       <td>{@code ui-fx}: {@code FxMain} → {@code AppController} (хранилища {@code RegistrySessionStore.forClient("fx")} и
 *           {@code XmlSessionStore}), самотест {@code FxSelfTest} (команды {@code sample}, {@code open}, {@code fill},
 *           {@code quickedit}, {@code menu}, {@code snapshot}, {@code crash}), открытие окон {@code FxWindowFactory},
 *           значения полей {@code FxFieldBinder} + {@code FieldValues}, окна {@code dialog/*} и {@code popup/QuickEditPopup}</td></tr>
 *   <tr><td>swing</td>
 *       <td>{@code registry.json}, {@code session-swing.xml}, {@code selftest.txt}</td>
 *       <td>{@code ui-swing}: {@code SwingMain} → {@code StartupFlow} (те же два хранилища), самотест
 *           {@code selftest/SwingSelfTest} + {@code SelfTestTokenizer}, открытие окон {@code SwingWindowFactory.openScripted},
 *           значения полей {@code SwingFieldBinder}, {@code MoneyField}, {@code DateField}, окна {@code dialog/*} и
 *           {@code popup/QuickEditPopup}</td></tr>
 *   <tr><td>web</td>
 *       <td>{@code web-session.md} и {@code web-session.plan.md} - файлы {@code MarkdownSessionStore} из CashMemory;
 *           {@code requests.txt} - запросы API в порядке отправки</td>
 *       <td>{@code web}: {@code WebMain} → {@code ServerState}, {@code SessionApi} ({@code POST/PUT /api/session/windows},
 *           {@code POST /api/session/snapshot}, {@code POST /api/debug/crash}), {@code WebWindow}; тела запросов
 *           построены так же, как их отправляет браузер: {@code windows.js} ({@code TrackedWindow.register/update}),
 *           {@code forms.js} ({@code Form.collect}), {@code util.js} ({@code canonicalMoney}, {@code canonicalDate}),
 *           окна {@code dialogs.js}, {@code editors.js}, {@code plan-dialogs.js}, {@code tools.js}, {@code popup.js},
 *           {@code commands.js}</td></tr>
 * </table>
 *
 * <h2>План в фикстурах</h2>
 *
 * <p>В CashMemory перед запуском лежал план «Семейный бюджет» ({@code SamplePlan} ядра на 14.09.2026 под другим
 * именем): без него прежний клиент открыл бы мастер нового плана поверх главного окна. Случаи с примером выполняли
 * «Файл → Открыть пример», поэтому их снимок несёт несохранённый план «Пример» (начало 01.09.2026, правила r1-r5,
 * разовая t1 20.12.2026); случаи мастера и «Открыть план» оставляли сохранённый «Семейный бюджет» (в снимке только
 * путь к файлу изолированной папки).</p>
 *
 * <h2>Чего нет и почему</h2>
 *
 * <ul>
 *   <li>Swing {@code ALERT purpose=applyWhatIf}: прежний Swing-клиент это подтверждение поддерживает, но его самотест не
 *       умеет включить «что-если» (нет команды {@code menu}), а без режима окно не открывается.</li>
 *   <li>Web {@code TEXT_INPUT purpose=customMonths} и {@code ALERT purpose=clearSnapshots}: прежний web-клиент не делает
 *       эти окна восстанавливаемыми (своего горизонта в месяцах нет, «Очистить снимки» - обычный {@code alertDialog}).</li>
 *   <li>Вложенные окна Swing: самотест открывает окна только от главного окна, поэтому случай {@code nested-rule-goal}
 *       без корректировки поверх редактора.</li>
 * </ul>
 */
final class LegacyFixtures {

    /** Корень фикстур в ресурсах тестов. */
    static final String ROOT = "/session/legacy/";

    /** Имя сохранённого плана в CashMemory на момент записи. */
    static final String SEED_PLAN_NAME = "Семейный бюджет";

    /** Имя несохранённого плана после «Открыть пример». */
    static final String SAMPLE_PLAN_NAME = "Пример";

    /** Хранилище снимка: реестр Windows (JSON в кусках узла). */
    static final String STORE_REGISTRY = "registry";
    /** Хранилище снимка: XML-файл CashMemory. */
    static final String STORE_XML = "xml";
    /** Хранилище снимка: {@code web-session.md} на сервере. */
    static final String STORE_SERVER = "server";

    private LegacyFixtures() {
    }

    /**
     * Прежний клиент, записавший фикстуру.
     */
    enum Client {
        /** JavaFX: реестр и XML. */
        FX("fx"),
        /** Swing: реестр и XML. */
        SWING("swing"),
        /** Web: Markdown-файл сервера. */
        WEB("web");

        private final String id;

        Client(String id) {
            this.id = id;
        }

        /** @return идентификатор клиента в снимке и имя папки фикстур */
        String id() {
            return id;
        }

        /** @return настольный ли клиент (снимок в реестре и XML) */
        boolean desktop() {
            return this != WEB;
        }
    }

    /**
     * Одна фикстура.
     *
     * @param client      клиент
     * @param name        имя папки случая
     * @param windowTypes типы окон снимка в порядке открытия
     * @param purpose     назначение первого окна (TEXT_INPUT, CHOICE, ALERT) или пустая строка
     * @param samplePlan  несёт ли снимок несохранённый план «Пример» (иначе сохранённый «Семейный бюджет»)
     */
    record Fixture(Client client, String name, List<WindowType> windowTypes, String purpose, boolean samplePlan) {

        /** Копирует список. */
        Fixture {
            windowTypes = List.copyOf(windowTypes);
            purpose = Objects.requireNonNullElse(purpose, "");
        }

        /** @return тип первого окна */
        WindowType type() {
            return windowTypes.getFirst();
        }

        /** @return путь папки случая в ресурсах, с косой чертой в конце */
        String dir() {
            return ROOT + client.id() + "/" + name + "/";
        }

        /** @return файлы, которые обязаны быть в папке случая */
        List<String> requiredFiles() {
            return client.desktop()
                    ? List.of("registry.json", "session-" + client.id() + ".xml", "selftest.txt")
                    : List.of("web-session.md", "requests.txt");
        }

        @Override
        public String toString() {
            return client.id() + "/" + name;
        }
    }

    /**
     * Все фикстуры: для каждого клиента - каждый тип окна и каждое назначение, которое клиент умеет восстанавливать.
     *
     * @return фикстуры в порядке клиентов и случаев
     */
    static List<Fixture> all() {
        List<Fixture> result = new ArrayList<>();
        for (Client client : Client.values()) {
            add(result, client, "new-plan-wizard", WindowType.NEW_PLAN_WIZARD, "", false);
            add(result, client, "new-plan-wizard-invalid", WindowType.NEW_PLAN_WIZARD, "", false);
            add(result, client, "plan-settings", WindowType.PLAN_SETTINGS, "", true);
            add(result, client, "plan-settings-invalid", WindowType.PLAN_SETTINGS, "", true);
            add(result, client, "rule-editor-create", WindowType.RULE_EDITOR, "", true);
            add(result, client, "rule-editor-edit-invalid", WindowType.RULE_EDITOR, "", true);
            add(result, client, "one-time-create", WindowType.ONE_TIME_EDITOR, "", true);
            add(result, client, "one-time-edit-invalid", WindowType.ONE_TIME_EDITOR, "", true);
            add(result, client, "adjustment-editor", WindowType.ADJUSTMENT_EDITOR, "", true);
            add(result, client, "adjustment-editor-invalid", WindowType.ADJUSTMENT_EDITOR, "", true);
            add(result, client, "goal-calculator", WindowType.GOAL_CALCULATOR, "", true);
            add(result, client, "goal-calculator-invalid", WindowType.GOAL_CALCULATOR, "", true);
            add(result, client, "text-input-rename", WindowType.TEXT_INPUT, "rename", true);
            add(result, client, "text-input-rename-invalid", WindowType.TEXT_INPUT, "rename", true);
            add(result, client, "text-input-reconcile", WindowType.TEXT_INPUT, "reconcile", true);
            add(result, client, "text-input-reconcile-invalid", WindowType.TEXT_INPUT, "reconcile", true);
            if (client.desktop()) {
                add(result, client, "text-input-custom-months", WindowType.TEXT_INPUT, "customMonths", true);
                add(result, client, "text-input-custom-months-invalid", WindowType.TEXT_INPUT, "customMonths", true);
            }
            add(result, client, "text-input-custom-currency", WindowType.TEXT_INPUT, "customCurrency", true);
            add(result, client, "text-input-custom-currency-invalid", WindowType.TEXT_INPUT, "customCurrency", true);
            add(result, client, "choice-currency", WindowType.CHOICE, "currency", true);
            add(result, client, "choice-currency-other", WindowType.CHOICE, "currency", true);
            add(result, client, "choice-open-plan", WindowType.CHOICE, "openPlan", false);
            add(result, client, "choice-open-plan-from-file", WindowType.CHOICE, "openPlan", false);
            add(result, client, "alert-delete-rule", WindowType.ALERT, "deleteRule", true);
            add(result, client, "alert-delete-one-time", WindowType.ALERT, "deleteOneTime", true);
            add(result, client, "alert-actualize", WindowType.ALERT, "actualize", true);
            if (client != Client.SWING) {
                add(result, client, "alert-apply-what-if", WindowType.ALERT, "applyWhatIf", true);
            }
            if (client.desktop()) {
                add(result, client, "alert-clear-snapshots", WindowType.ALERT, "clearSnapshots", true);
            }
            add(result, client, "csv-export", WindowType.CSV_EXPORT, "", true);
            add(result, client, "quick-edit-popup", WindowType.QUICK_EDIT_POPUP, "", true);
            add(result, client, "quick-edit-popup-invalid", WindowType.QUICK_EDIT_POPUP, "", true);
            if (client == Client.SWING) {
                result.add(new Fixture(client, "nested-rule-goal",
                        List.of(WindowType.RULE_EDITOR, WindowType.GOAL_CALCULATOR), "", true));
            } else {
                result.add(new Fixture(client, "nested-rule-adjustment-goal",
                        List.of(WindowType.RULE_EDITOR, WindowType.ADJUSTMENT_EDITOR, WindowType.GOAL_CALCULATOR), "", true));
            }
        }
        return List.copyOf(result);
    }

    private static void add(List<Fixture> list, Client client, String name, WindowType type, String purpose,
                            boolean samplePlan) {
        list.add(new Fixture(client, name, List.of(type), purpose, samplePlan));
    }

    /**
     * Находит фикстуру по клиенту и имени.
     *
     * @param client клиент
     * @param name   имя случая
     * @return фикстура
     * @throws IllegalArgumentException если такой нет в каталоге
     */
    static Fixture find(Client client, String name) {
        return all().stream().filter(f -> f.client() == client && f.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No legacy fixture " + client.id() + "/" + name));
    }

    /**
     * Снимки фикстуры по хранилищам: у настольного клиента реестр и XML (записаны одним вызовом записи и совпадают
     * по содержанию), у web - файл сервера с подставленным текстом плана из {@code web-session.plan.md}.
     *
     * @param fixture фикстура
     * @return хранилище → снимок в порядке {@link #STORE_REGISTRY}, {@link #STORE_XML} или {@link #STORE_SERVER}
     * @throws SnapshotFormatException если файл не читается кодеком
     */
    static Map<String, SessionSnapshot> snapshots(Fixture fixture) throws SnapshotFormatException {
        Map<String, SessionSnapshot> result = new LinkedHashMap<>();
        if (fixture.client().desktop()) {
            result.put(STORE_REGISTRY, new JsonSnapshotCodec().decode(read(fixture.dir() + "registry.json")));
            result.put(STORE_XML, new XmlSnapshotCodec().decode(read(fixture.dir() + "session-" + fixture.client().id() + ".xml")));
            return result;
        }
        String text = read(fixture.dir() + "web-session.md");
        SessionSnapshot snapshot = new MarkdownSnapshotCodec().decode(text);
        String planFile = MarkdownSnapshotCodec.externalPlanFile(text);
        if (snapshot.plan().dirty() && planFile != null) {
            // Так же, как MarkdownSessionStore.load: текст несохранённого плана вынесен в соседний файл.
            snapshot = new SessionSnapshot(snapshot.schemaVersion(), snapshot.savedAt(), snapshot.client(), snapshot.main(),
                    PlanState.dirty(read(fixture.dir() + planFile)), snapshot.windows());
        }
        result.put(STORE_SERVER, snapshot);
        return result;
    }

    /**
     * Снимок фикстуры из хранилища по умолчанию (реестр у настольных клиентов, файл сервера у web).
     *
     * @param fixture фикстура
     * @return снимок
     * @throws SnapshotFormatException если файл не читается кодеком
     */
    static SessionSnapshot snapshot(Fixture fixture) throws SnapshotFormatException {
        return snapshots(fixture).values().iterator().next();
    }

    /**
     * Есть ли ресурс.
     *
     * @param resource абсолютный путь ресурса
     * @return {@code true}, если ресурс найден
     */
    static boolean exists(String resource) {
        return LegacyFixtures.class.getResource(resource) != null;
    }

    /**
     * Текст ресурса в UTF-8.
     *
     * @param resource абсолютный путь ресурса
     * @return содержимое
     * @throws IllegalArgumentException если ресурса нет
     */
    static String read(String resource) {
        try (InputStream in = LegacyFixtures.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing test resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
