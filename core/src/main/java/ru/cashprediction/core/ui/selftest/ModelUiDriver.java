package ru.cashprediction.core.ui.selftest;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.core.ui.command.*;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.menu.*;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.view.popup.*;
import ru.cashprediction.core.app.AppEnvironment;
import ru.cashprediction.core.app.ClientKind;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.ui.dump.UiDump;

/**
 * Драйвер без интерфейса: выполняет сценарий над {@code AppController} и внутренним записывающим портом и строит
 * дамп из моделей (архитектура §6.2). Так генерируются эталоны {@code core/src/test/resources/ui-golden/<сценарий>/
 * <шаг>.json} ({@code UiGoldenTest}, обновление {@code -Dcashprediction.golden.update=true}).
 *
 * <p><b>Поведение (этап S2):</b> порт работает в потоке теста (прямой {@code UiExecutor}, планировщик с виртуальным
 * временем, который {@link #awaitIdle(Duration)} прокручивает); меню, тулбар и контекстные меню «нажимаются» через
 * {@code UiIntents.command} с источником соответствующего вида; поля форм — через {@code FormSession.fieldChanged};
 * {@code answer} отвечает на верхнее сообщение по тексту кнопки; {@code chooser} задаёт ответ следующему выбору; дамп
 * строится из {@code MainScreenModel}, открытых {@code FormView}/{@code AlertSpec} и записанных запросов выбора; границ
 * областей нет (эталон сравнивает их только попарно между клиентами); {@code screenshot} не поддерживается.</p>
 *
 * <p>Не потокобезопасен.</p>
 */
public final class ModelUiDriver implements UiDriver {

    private final AppEnvironment environment;
    private final ClientProfile profile;
    private AppController controller;
    private UiIntents intents;
    private final RecordingUiPort port;
    private boolean started;
    private final java.util.ArrayList<UiDump.Popup> popups = new java.util.ArrayList<>();
    private long clientRevision;
    private ru.cashprediction.core.session.Scheduler.Task filterTask, spinnerTask;

    /**
     * Создаёт драйвер.
     *
     * @param environment окружение (изолированный {@code --home}, {@code --registry memory}, {@code --today})
     * @param profile     профиль клиента, чьи различия §10 воспроизводятся (для эталонов — FX)
     */
    public ModelUiDriver(AppEnvironment environment, ClientProfile profile) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.profile = Objects.requireNonNull(profile, "profile");
        if (environment.options().home() == null || !environment.options().registryMemory()
                && (environment.options().registryNode() == null
                || !environment.options().registryNode().startsWith("ru/cashprediction/selftest/")))
            throw new IllegalArgumentException("isolated selftest environment required");
        port = new RecordingUiPort(profile);
    }

    /** Внутренняя точка тестирования событий, не меняющая публичный контракт драйвера. */
    ModelUiDriver(AppEnvironment environment, RecordingUiPort port, UiIntents intents) {
        this.environment = environment; this.port = port; this.profile = port.profile;
        this.intents = intents; this.started = true;
    }

    /** @return окружение */
    public AppEnvironment environment() {
        return environment;
    }

    /** @return профиль клиента */
    public ClientProfile profile() {
        return profile;
    }

    /** Возвращает клиент профиля; дамп при этом помечен как модель. */
    @Override
    public ClientKind client() {
        return profile.kind();
    }

    /** Исполняет доступное пользователю действие через намерения или сеанс открытой формы. */
    @Override
    public void execute(SelfTestCommand command) {
        Objects.requireNonNull(command, "command");
        if (command instanceof SelfTestCommand.Today today) {
            if (!environment.clock().today().equals(today.date())) fail("today requires --today");
            return;
        }
        start();
        if (port.exited) fail("exited");
        switch (command) {
            case SelfTestCommand.Sample _ -> menu("file.sample");
            case SelfTestCommand.Save _ -> menu("file.save");
            case SelfTestCommand.Snapshot _ -> menu("recovery.snapshotNow");
            case SelfTestCommand.View c -> menu("view." + c.mode().name().toLowerCase(java.util.Locale.ROOT));
            case SelfTestCommand.Period c -> menu("view.period." + c.period().name());
            case SelfTestCommand.Menu c -> menu(c.idOrPath());
            case SelfTestCommand.Click c -> click(c.toolbarId());
            case SelfTestCommand.Key c -> key(c.chord());
            case SelfTestCommand.Size c -> {
                mainAvailable(); var old = port.geometry.bounds();
                port.geometry = new MainGeometry(new WindowBounds(old.x(), old.y(), c.width(), c.height()), false);
                intents.mainGeometry(port.geometry.bounds(), false);
            }
            case SelfTestCommand.Select c -> { mainAvailable(); intents.selectRow(row(c.rowId())); port.focus = FocusTarget.TABLE; }
            case SelfTestCommand.DoubleClick c -> activate(c.rowId(), c.columnId(), Activation.DOUBLE_CLICK);
            case SelfTestCommand.RowClick c -> activate(c.rowId(), c.columnId(), Activation.CLICK);
            case SelfTestCommand.QuickEdit c -> {
                var r = port.screen.table().row(port.screen.table().indexOf(row(c.rowId())));
                if (!r.quickEditable()) fail(c.rowId());
                String amountColumn = "income";
                var columns = port.screen.table().columns();
                for (int i = 0; i < columns.size(); i++) {
                    if (columns.get(i).id().equals("expense") && FieldCodec.parseMoney(r.cells().get(i)).filter(m -> m.isPositive()).isPresent()) amountColumn = "expense";
                }
                activate(c.rowId(), amountColumn, Activation.DOUBLE_CLICK);
                var h = window("last");
                var f = fields(h).stream().filter(s -> s.kind() == FieldKind.MONEY).findFirst().orElseThrow();
                fill(h, f.id(), c.amount());
            }
            case SelfTestCommand.FilterType c -> {
                mainAvailable(); port.focus = FocusTarget.FILTER;
                if (filterTask != null) filterTask.cancel();
                filterTask = port.scheduler.schedule(() -> intents.filterText(c.text()), Duration.ofMillis(300));
            }
            case SelfTestCommand.Filter c -> {
                String id = switch (c.key()) {
                    case "pastExpanded" -> "past.toggle";
                    case "whatIfIncome" -> "whatIf.income";
                    case "whatIfExpense" -> "whatIf.expense";
                    default -> "view.flag." + c.key();
                };
                // Флажок устанавливается щелчком только при несовпадении текущего состояния.
                if (id.equals("past.toggle")) {
                    boolean expanded = controller.state().pastExpanded();
                    if (expanded != c.value()) menu(id);
                } else {
                    MenuNode n = node(id);
                    if (!(n instanceof MenuNode.Check check)) fail(id);
                    else if (check.checked() != c.value()) menu(id);
                }
            }
            case SelfTestCommand.SliderSet c -> {
                mainAvailable(); var n = node(c.itemId());
                if (!(n instanceof MenuNode.Slider s) || c.value() < s.min() || c.value() > s.max()) fail(c.itemId());
                intents.sliderCommit(c.itemId(), c.value());
            }
            case SelfTestCommand.SpinnerSet c -> {
                mainAvailable(); var n = node(c.itemId());
                if (!(n instanceof MenuNode.Spinner s) || c.value() < s.min() || c.value() > s.max()) fail(c.itemId());
                if (spinnerTask != null) spinnerTask.cancel();
                spinnerTask = port.scheduler.schedule(() -> intents.spinnerCommit(c.itemId(), c.value()), Duration.ofMillis(600));
            }
            case SelfTestCommand.Fill c -> { var h = window(c.window()); c.values().forEach((id, value) -> fill(h, id, value)); }
            case SelfTestCommand.Field c -> { var h = window(c.windowTitle()); fill(h, field(h, c.label()).id(), c.text()); }
            case SelfTestCommand.Ok c -> { var h = window(c.window()); press(h, h.spec.defaultButtonId()); }
            case SelfTestCommand.Cancel c -> window(c.window()).form.closeRequested();
            case SelfTestCommand.Button c -> {
                var h = window(c.windowTitle());
                var b = ModelDump.buttons(h).stream().filter(s -> s.text().equals(c.label())).findFirst().orElseThrow();
                press(h, b.id());
            }
            case SelfTestCommand.FieldEnter c -> { var h = window(c.windowTitle()); var f = field(h, c.label()); editable(h, f); h.form.fieldSubmitted(f.id()); }
            case SelfTestCommand.ListPick c -> pick(c);
            case SelfTestCommand.Answer c -> answer(c.buttonText());
            case SelfTestCommand.Chooser c -> {
                Path path = c.path() == null ? null : environment.cashMemory().resolve(c.path()).normalize();
                if (path != null && !path.startsWith(environment.cashMemory())) fail("chooser outside CashMemory");
                port.chooseAnswer(Optional.ofNullable(path));
            }
            case SelfTestCommand.Open c -> open(c);
            case SelfTestCommand.Context c -> context(c.target());
            case SelfTestCommand.Hover c -> hover(c.target());
            case SelfTestCommand.Exit _ -> menu("file.exit");
            case SelfTestCommand.Crash _ -> menu("recovery.simulate.halt");
            case SelfTestCommand.Throw _ -> menu("recovery.simulate.exception");
            default -> fail(command.getClass().getSimpleName());
        }
    }

    /** Прокручивает ближайшие задержки ввода без ожидания в реальном времени. */
    @Override
    public void awaitIdle(Duration timeout) {
        start(); port.scheduler.idle(timeout);
    }

    void advance(Duration duration) { start(); port.scheduler.advance(duration); }

    /** Собирает видимые данные моделей, не придумывая геометрию графических клиентов. */
    @Override
    public UiDump dump(String step) {
        start(); var dump = ModelDump.build(port, controller, environment.options().selftest(), step, popups);
        port.context = List.of(); return dump;
    }

    /** Останавливает только виртуальные задачи после изолированного тестового прогона. */
    void stopTimers() { port.scheduler.shutdown(); }

    /** Отказывает в снимке: у модельного драйвера нет экрана. */
    @Override
    public byte[] screenshot(String step) throws IOException {
        throw new IOException(UiText.get("s2.selftest.screen"));
    }

    private void start() {
        if (!started) {
            controller = new AppController(port, environment); intents = controller;
            controller.start(); started = true;
        }
    }
    private void mainAvailable() { if (port.screen == null || port.modal() != null || port.chooser != null) fail("main blocked"); }
    private static void fail(String operation) { throw new IllegalStateException(UiText.get("s2.selftest.operation", operation)); }
    private MenuNode node(String id) {
        if (port.screen == null) { fail(id); }
        var direct = find(port.screen.menuBar().menus(), id);
        if (direct != null) return direct;
        if (id.contains("/")) {
            List<? extends MenuNode> level = port.screen.menuBar().menus(); MenuNode result = null;
            for (String label : id.split("/")) {
                result = level.stream().filter(n -> java.util.Objects.equals(ModelDump.menu(n).text(), label)).findFirst().orElseThrow();
                if (result instanceof MenuNode.Submenu s) { if (!s.enabled()) fail(id); level = s.children(); }
            }
            return result;
        }
        return Optional.<MenuNode>empty().orElseGet(() -> {
            var found = find(port.context, id);
            if (found == null) fail(id);
            return found;
        });
    }
    private static MenuNode find(List<? extends MenuNode> nodes, String id) {
        for (var n : nodes) {
            if (n.id().equals(id)) return n;
            if (n instanceof MenuNode.Submenu s && s.enabled()) { var child = find(s.children(), id); if (child != null) return child; }
        }
        return null;
    }
    private void menu(String id) {
        boolean preview = port.contextTarget.startsWith("preview:") && find(port.context, id) != null;
        if (preview) window(port.contextTarget.substring(8, port.contextTarget.lastIndexOf(':')));
        else mainAvailable();
        // JavaFX: MenuItem → Swing: JMenuItem → Web: menuitem
        MenuNode n = node(id);
        InvokeSource source = preview ? InvokeSource.FORM : port.context.stream().anyMatch(x -> x.id().equals(id)) ? InvokeSource.CONTEXT_MENU : InvokeSource.MENU;
        invoke(n, source);
        port.context = List.of();
    }
    private void invoke(MenuNode n, InvokeSource source) {
        switch (n) {
            case MenuNode.Action a -> { if (!a.enabled()) fail(a.id()); intents.command(a.command(), a.args(), source); }
            case MenuNode.Check a -> { if (!a.enabled()) fail(a.id()); intents.command(a.command(), a.args(), source); }
            case MenuNode.Radio a -> { if (!a.enabled()) fail(a.id()); intents.command(a.command(), a.args(), source); }
            default -> fail(n.id());
        }
    }
    private void click(String id) {
        mainAvailable();
        // JavaFX: SplitMenuButton → Swing: JButton → Web: button
        String[] split = id.split("\\.menu:", 2);
        var n = port.screen.toolbar().items().stream().filter(x -> x.id().equals(split[0])).findFirst().orElseThrow();
        switch (n) {
            case ToolbarNode.SplitButton b -> {
                if (split.length == 1) invoke(b.main(), InvokeSource.TOOLBAR);
                else { var item = find(b.items(), split[1]); if (item == null) fail(id); invoke(item, InvokeSource.TOOLBAR); }
            }
            case ToolbarNode.MenuButton b -> { if (split.length == 1) fail(id); var item = find(b.items(), split[1]); if (item == null) fail(id); invoke(item, InvokeSource.TOOLBAR); }
            case ToolbarNode.Toggle b -> intents.command(b.command(), CommandArgs.NONE, InvokeSource.TOOLBAR);
            case ToolbarNode.Button b -> { if (!b.enabled()) fail(id); intents.command(b.command(), CommandArgs.NONE, InvokeSource.TOOLBAR); }
            default -> fail(id);
        }
    }
    private void key(KeyChord chord) {
        var h = port.top();
        if (h != null) {
            if (chord.key().equals("ESCAPE")) {
                if (h.form != null) h.form.closeRequested();
                else answer(h.alert.buttons().stream().filter(b -> b.role() == ButtonRole.CANCEL).findFirst().orElseThrow().text());
                return;
            }
            if (chord.key().equals("ENTER")) {
                if (h.form != null) {
                    if (h.spec.presentation() == Presentation.POPUP) h.form.fieldSubmitted(fields(h).getFirst().id());
                    else if (h.spec.presentation() == Presentation.WIZARD) press(h, h.view.page() == h.spec.pages().size() - 1 ? "finish" : "next");
                    else if (!h.spec.defaultButtonId().isEmpty()) press(h, h.spec.defaultButtonId());
                }
                else answer(h.alert.buttons().stream().filter(b -> b.id().equals(h.alert.defaultButtonId())).findFirst().orElseThrow().text());
                return;
            }
        }
        intents.key(chord, h != null ? FocusScope.TEXT_INPUT : port.focus == FocusTarget.FILTER ? FocusScope.FILTER : FocusScope.TABLE,
                h == null ? "" : h.id);
    }
    private String row(String id) {
        if (port.screen.table().indexOf(id) < 0) fail(id);
        return id;
    }
    private void activate(String id, String column, Activation how) {
        mainAvailable(); id = row(id); intents.selectRow(id);
        intents.activateRow(id, column.isEmpty() ? "operation" : column, how);
    }
    private RecordingUiPort.Handle window(String title) {
        var h = port.windows.stream().filter(w -> w.open && w.form != null
                && (title.equals("last") || w.id.equals(title) || w.spec.windowTitle().equals(title)))
                .reduce((a, b) -> b).orElseThrow();
        if (port.modal() != null && port.modal() != h || port.chooser != null) fail(title);
        return h;
    }
    static List<FieldSpec> fields(RecordingUiPort.Handle h) {
        java.util.ArrayList<FieldSpec> fields = new java.util.ArrayList<>();
        for (var row : h.spec.pages().get(h.view.page()).rows()) {
            switch (row) {
                case FormRow.Field f -> fields.add(f.field());
                case FormRow.Inline i -> fields.addAll(i.fields());
                case FormRow.SideColumn s -> fields.add(s.preview());
                default -> { }
            }
        }
        return fields;
    }
    private FieldSpec field(RecordingUiPort.Handle h, String label) {
        return fields(h).stream().filter(f -> {
            var v = h.view.fields().get(f.id());
            return (v != null && v.label() != null ? v.label() : f.label()).equals(label);
        }).findFirst().orElseThrow();
    }
    private void editable(RecordingUiPort.Handle h, FieldSpec f) {
        var v = h.view.fields().get(f.id());
        if (v == null || !v.visible() || !v.enabled() || v.readOnly()) fail(f.id());
    }
    private void fill(RecordingUiPort.Handle h, String id, String value) {
        var f = fields(h).stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
        editable(h, f);
        if (f.kind() == FieldKind.CHOICE || f.kind() == FieldKind.RADIO || f.kind() == FieldKind.LIST) {
            var options = h.view.fields().get(id).options(); if (options == null) options = f.options();
            if (options.stream().noneMatch(o -> o.value().equals(value))) fail(id);
        }
        if (f.kind() == FieldKind.CHECK && !List.of("true", "false").contains(value)) fail(id);
        h.form.fieldChanged(id, value, true, ++clientRevision);
    }
    private void press(RecordingUiPort.Handle h, String id) {
        var b = ModelDump.buttons(h).stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
        if (!b.enabled()) fail(id);
        h.form.buttonPressed(id);
    }
    private void answer(String text) {
        var h = port.top(); if (h == null || h.alert == null) fail(text);
        var b = h.alert.buttons().stream().filter(x -> x.text().equals(text) && x.enabled()).findFirst().orElseThrow();
        h.open = false; if (h.alertSession != null) h.alertSession.closed(); h.answer.accept(b.id());
    }
    private void pick(SelfTestCommand.ListPick c) {
        var h = window(c.windowTitle());
        var side = h.spec.pages().get(h.view.page()).rows().stream().filter(r -> r instanceof FormRow.SideColumn s && s.caption().equals(c.label())).findFirst();
        if (side.isPresent()) {
            for (int i = 0; i < h.view.preview().size(); i++) {
                var p = h.view.preview().get(i);
                if (p.selectable() && p.text().equals(c.itemText())) { h.form.previewSelected(i, c.activate()); return; }
            }
            fail(c.itemText());
        }
        var f = field(h, c.label()); editable(h, f);
        var options = h.view.fields().get(f.id()).options(); if (options == null) options = f.options();
        for (int i = 0; i < options.size(); i++) {
            var o = options.get(i);
            if (o.text().equals(c.itemText())) {
                h.form.fieldChanged(f.id(), o.value(), true, ++clientRevision);
                if (c.activate()) h.form.fieldActivated(f.id(), i); return;
            }
        }
        fail(c.itemText());
    }
    private void open(SelfTestCommand.Open c) {
        if (!c.context().isEmpty()) fail("open context requires user commands");
        String id = switch (c.type()) {
            case NEW_PLAN_WIZARD -> "file.new";
            case PLAN_SETTINGS -> "edit.planSettings";
            case RULE_EDITOR -> "edit.addIncome";
            case ONE_TIME_EDITOR -> "edit.addOneTime";
            case ADJUSTMENT_EDITOR -> "edit.adjust";
            case GOAL_CALCULATOR -> "tools.goal";
            case CSV_EXPORT -> "file.exportCsv";
            default -> "";
        };
        if (id.isEmpty()) fail(c.type().name()); menu(id);
    }
    private void context(String target) {
        if (target.startsWith("preview:")) {
            String[] parts = target.split(":", 3); var h = window(parts[1]); int index = Integer.parseInt(parts[2]);
            if (index < 0 || index >= h.view.preview().size() || !h.view.preview().get(index).selectable()) fail(target);
            h.form.previewSelected(index, false);
            // JavaFX: ContextMenu → Swing: JPopupMenu → Web: menu
            port.context = intents.contextMenu(new ContextTarget.Preview(h.id, index));
            port.contextTarget = "preview:" + h.id + ":" + index; return;
        }
        mainAvailable(); String[] t = target.split(":", 2);
        ContextTarget object = switch (t[0]) {
            case "row" -> { intents.selectRow(row(t[1])); yield new ContextTarget.Row(t[1]); }
            case "total" -> new ContextTarget.Total(row(t[1]));
            case "pastHeader" -> new ContextTarget.PastHeader(ru.cashprediction.core.ui.view.table.LazyTableModel.PAST_HEADER_ROW_ID);
            case "card" -> new ContextTarget.Card(t[1]);
            case "chart" -> { var xy = t[1].split(","); yield new ContextTarget.Chart(Double.parseDouble(xy[0]), Double.parseDouble(xy[1]), port.geometry.bounds().width(), 500); }
            default -> throw new IllegalArgumentException("context");
        };
        // JavaFX: ContextMenu → Swing: JPopupMenu → Web: menu
        port.context = intents.contextMenu(object); port.contextTarget = target;
    }
    private void hover(String target) {
        mainAvailable(); popups.clear();
        if (target.startsWith("menu:")) intents.menuHover(target.substring(5));
        else if (target.startsWith("card:")) {
            SparklineModel s = intents.sparkline(target.substring(5));
            popups.add(new UiDump.Popup("sparkline", List.of(s.header(), s.explanation(), s.minText(), s.maxText(), s.noDataText()), null));
        } else if (target.startsWith("chart:")) {
            var xy = target.substring(6).split(",");
            intents.chartHover(port.screen.chart().revision(), Double.parseDouble(xy[0]), Double.parseDouble(xy[1]),
                    port.geometry.bounds().width(), 500).ifPresent(h -> {
                        var c = h.card(); var lines = new java.util.ArrayList<String>(); lines.add(c.header()); lines.add(c.balanceLine());
                        c.lines().forEach(l -> lines.add(l.text())); if (!c.moreText().isEmpty()) lines.add(c.moreText());
                        if (!c.noneText().isEmpty()) lines.add(c.noneText()); popups.add(new UiDump.Popup("dayCard", lines, null));
                    });
        } else fail(target);
    }
}
