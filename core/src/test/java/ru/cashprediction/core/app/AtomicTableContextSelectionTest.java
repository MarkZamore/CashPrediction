package ru.cashprediction.core.app;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.fake.FakeUiPort;
import ru.cashprediction.core.forecast.Origin;
import ru.cashprediction.core.ui.command.*;
import ru.cashprediction.core.ui.menu.*;
import ru.cashprediction.core.ui.view.MainScreenModel;
import ru.cashprediction.core.ui.view.table.RowKind;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет настоящий контроллер: выбор цели меню и единую публикацию модели во всех профилях клиентов. */
final class AtomicTableContextSelectionTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 13);
    @TempDir Path home;

    /** Стенд без реестра и настоящего интерфейса: порт записывает опубликованные кадры. */
    private record Rig(AppController app, FakeUiPort port) { }

    /** Итог, группа прошедших и обычная строка выбираются ядром до возврата меню и публикации доступности. */
    @Test void tableTargetsPublishSelectionAndAvailabilityAcrossProfiles() {
        for (var profile : profiles()) {
            var rig = rig(profile);
            String rule = row(rig, RowKind.RULE);
            String total = row(rig, RowKind.MONTH_TOTAL);
            String past = row(rig, RowKind.PAST_HEADER);
            rig.app().selectRow(rule);
            for (var target : List.of(new ContextTarget.Total(total), new ContextTarget.PastHeader(past),
                    new ContextTarget.Row("start"), new ContextTarget.Row(rule))) {
                int before = rig.port().calls("render").size();
                // JavaFX: ContextMenu → Swing: JPopupMenu → Web: contextmenu.
                assertFalse(rig.app().contextMenu(target).isEmpty());
                String id = switch (target) {
                    case ContextTarget.Row r -> r.rowId();
                    case ContextTarget.Total t -> t.rowId();
                    case ContextTarget.PastHeader p -> p.rowId();
                    default -> throw new AssertionError(target);
                };
                assertEquals(id, rig.app().state().selectedRowId(), profile.kind().name());
                assertEquals(before + 1, rig.port().calls("render").size(), "Publish one complete selection frame");
                var frame = screen(rig);
                assertEquals(id, frame.table().selectedRowId());
                assertTrue(frame.table().indexOf(id) >= 0, "Selected row must be present in the published table");
                // JavaFX: MenuItem → Swing: JMenuItem → Web: role=menuitem.
                var edit = (MenuNode.Action) frame.menuBar().find("edit.edit").orElseThrow();
                assertEquals(target instanceof ContextTarget.Row, edit.enabled());
            }
        }
    }

    /** Раскрытие скрытого прошлого и его выбор не публикуют промежуточный кадр со старым выделением. */
    @Test void hiddenPastRowExpansionAndSelectionAreAtomic() {
        for (var profile : profiles()) {
            var rig = rig(profile);
            rig.app().selectRow("start");
            assertFalse(rig.app().state().pastExpanded());
            String pastRule = rig.app().document().forecast().rows().stream()
                    .filter(r -> r.origin() == Origin.RULE && r.date().isBefore(TODAY))
                    .findFirst().orElseThrow().rowId();
            int before = rig.port().calls("render").size();
            // JavaFX: ContextMenu → Swing: JPopupMenu → Web: contextmenu.
            assertFalse(rig.app().contextMenu(new ContextTarget.Row(pastRule)).isEmpty());
            assertTrue(rig.app().state().pastExpanded());
            assertEquals(pastRule, rig.app().state().selectedRowId());
            var frames = rig.port().calls("render").subList(before, rig.port().calls("render").size());
            assertEquals(1, frames.size());
            assertEquals(pastRule, frames.getFirst().arg(0, MainScreenModel.class).table().selectedRowId());
        }
    }

    /** Карточка, график, чужой предпросмотр и повтор меню уже выбранной строки не меняют выбор таблицы. */
    @Test void nonTableAndRepeatedPopupQueriesDoNotRepublishSelection() {
        for (var profile : profiles()) {
            var rig = rig(profile);
            rig.app().selectRow("start");
            int before = rig.port().calls("render").size();
            for (var target : List.of(new ContextTarget.Card("now"), new ContextTarget.Chart(300, 200, 1200, 700),
                    new ContextTarget.Preview("absent", 0), new ContextTarget.Row("start"), new ContextTarget.Row("absent"))) {
                // JavaFX: ContextMenu → Swing: JPopupMenu → Web: contextmenu.
                rig.app().contextMenu(target);
                assertEquals("start", rig.app().state().selectedRowId());
                assertEquals(before, rig.port().calls("render").size());
            }
        }
    }

    /** Модальная форма сохраняет блокировку всех главных контекстов без побочного выбора строки. */
    @Test void modalFormBlocksContextSelection() {
        for (var profile : profiles()) {
            var rig = rig(profile);
            String total = row(rig, RowKind.MONTH_TOTAL), past = row(rig, RowKind.PAST_HEADER);
            rig.app().selectRow("start");
            rig.app().command(CommandId.EDIT_PLAN_SETTINGS, CommandArgs.NONE, InvokeSource.MENU);
            assertTrue(rig.app().state().windows().modalOpen());
            int before = rig.port().calls("render").size();
            for (var target : List.of(new ContextTarget.Total(total), new ContextTarget.PastHeader(past),
                    new ContextTarget.Row(row(rig, RowKind.RULE)))) {
                // JavaFX: ContextMenu → Swing: JPopupMenu → Web: contextmenu.
                assertTrue(rig.app().contextMenu(target).isEmpty());
                assertEquals("start", rig.app().state().selectedRowId());
                assertEquals(before, rig.port().calls("render").size());
            }
        }
    }

    /** Меню настоящего предпросмотра открытого редактора не выбирает его событие в главной таблице. */
    @Test void validEditorPreviewKeepsMainTableSelection() {
        for (var profile : profiles()) {
            var rig = rig(profile);
            String rule = row(rig, RowKind.RULE), total = row(rig, RowKind.MONTH_TOTAL);
            rig.app().selectRow(total);
            rig.app().command(CommandId.EDIT_EDIT, CommandArgs.row(rule), InvokeSource.MENU);
            var form = rig.port().forms().getLast().session();
            int before = rig.port().calls("render").size();
            // JavaFX: ContextMenu → Swing: JPopupMenu → Web: contextmenu.
            assertFalse(rig.app().contextMenu(new ContextTarget.Preview(form.windowId(), 0)).isEmpty());
            assertEquals(total, rig.app().state().selectedRowId());
            assertEquals(before, rig.port().calls("render").size());
        }
    }

    /** Профили различаются только возможностями клиента, а не правилами выделения. */
    private static List<ClientProfile> profiles() {
        return List.of(ClientProfile.fx("25"), ClientProfile.swing(), ClientProfile.web());
    }

    /** Создаёт план с прошлыми событиями и будущими итогами, затем показывает модель в записывающем порту. */
    private Rig rig(ClientProfile profile) {
        var port = new FakeUiPort(profile);
        var directory = home.resolve(profile.kind().name());
        var app = new AppController(port, new AppEnvironment(LaunchOptions.parse("--registry", "memory"),
                directory, directory.resolve("CashMemory"), AppClock.fixedToday(TODAY)));
        app.document().replace(SamplePlan.create(TODAY), null, false, List.of());
        app.showMain(null);
        return new Rig(app, port);
    }

    /** Возвращает именно последнюю опубликованную модель, а не модель, собранную тестом. */
    private static MainScreenModel screen(Rig rig) {
        var renders = rig.port().calls("render");
        return (renders.isEmpty() ? rig.port().calls("showMain").getLast() : renders.getLast()).arg(0, MainScreenModel.class);
    }

    /** Находит существующую видимую строку по виду без выдуманного id. */
    private static String row(Rig rig, RowKind kind) {
        var table = screen(rig).table();
        for (int i = 0; i < table.rowCount(); i++) if (table.row(i).kind() == kind) return table.row(i).rowId();
        throw new AssertionError("Missing row kind: " + kind);
    }
}
