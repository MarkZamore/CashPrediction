package ru.cashprediction.core.app.controller.extra;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.app.fake.FakeUiPort;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.Origin;
import ru.cashprediction.core.ui.command.*;
import ru.cashprediction.core.ui.form.FormSession;
import ru.cashprediction.core.ui.view.MainScreenModel;
import ru.cashprediction.core.ui.view.table.RowKind;
import ru.cashprediction.core.ui.view.table.TableRowView;

/** Общий стенд: настоящий контроллер, виртуальные таймеры, память вместо реестра и временный дом. */
final class ControllerFixture {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    final FakeUiPort port;
    final AppController app;

    ControllerFixture(Path home, ClientProfile profile, boolean sample) {
        port = new FakeUiPort(profile);
        app = new AppController(port, new AppEnvironment(LaunchOptions.parse("--registry", "memory"),
                home, home.resolve("CashMemory"), AppClock.fixedToday(TODAY)));
        if (sample) app.document().replace(SamplePlan.create(TODAY), null, false, List.of());
    }

    void show() { app.showMain(null); }

    void command(CommandId command) { command(command, CommandArgs.NONE); }

    void command(CommandId command, CommandArgs args) { app.command(command, args, InvokeSource.MENU); }

    MainScreenModel screen() {
        var renders = port.calls("render");
        return (renders.isEmpty() ? port.calls("showMain").getLast() : renders.getLast())
                .arg(0, MainScreenModel.class);
    }

    FormSession form() { return port.forms().getLast().session(); }

    void field(FormSession form, String id, String value) {
        form.fieldChanged(id, value, true, form.view().revision() + 1);
    }

    ForecastRow rule() {
        return app.document().forecast().rows().stream()
                .filter(row -> row.origin() == Origin.RULE && !row.date().isBefore(TODAY))
                .findFirst().orElseThrow();
    }

    TableRowView row(RowKind kind) {
        var table = screen().table();
        for (int index = 0; index < table.rowCount(); index++) {
            if (table.row(index).kind() == kind) return table.row(index);
        }
        throw new AssertionError("Missing table row: " + kind);
    }

    FakeUiPort.PendingAlert alert() { return port.pendingAlerts().getLast(); }

    void dismiss() {
        var alert = alert();
        alert.press(alert.spec().defaultButtonId());
        assertFalse(app.state().windows().modalOpen());
    }
}
