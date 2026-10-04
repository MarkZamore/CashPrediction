package ru.cashprediction.core.app;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.fake.FakeUiPort;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.core.ui.menu.MenuModels;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.view.MainScreenModel;
import ru.cashprediction.core.ui.view.chart.ChartLayout;
import ru.cashprediction.core.ui.view.status.StatusBuilder;
import ru.cashprediction.core.ui.view.summary.SummaryBuilder;
import ru.cashprediction.core.ui.view.table.LazyTableModel;

/** Сверяет перенесённый bootstrap с прежней Web-композицией без GUI и старта приложения. */
class BootstrapModelOwnershipTest {
    @TempDir Path home;

    /** Пустой и непустой планы сохраняют JSON, строки, сцены, время и ревизию placeholder. */
    @Test void placeholderMatchesLegacyWebModelForEmptyAndSamplePlans() {
        for (boolean sample : List.of(false, true)) {
            var port = new FakeUiPort(ClientProfile.web());
            var clock = AppClock.of(Clock.fixed(Instant.parse("2026-10-04T12:00:00Z"), ZoneOffset.UTC), null);
            var environment = new AppEnvironment(LaunchOptions.parse("--registry", "memory"),
                    home, home.resolve("CashMemory"), clock);
            var controller = new AppController(port, environment);
            if (sample) controller.document().replace(SamplePlan.create(clock.today()), null, false, List.of());
            var before = controller.state();
            var calls = List.copyOf(port.calls());
            MainScreenModel expected = legacyWebPlaceholder(before, clock);
            MainScreenModel actual = controller.bootstrapPlaceholder();
            assertEquals(UiJson.write(expected), UiJson.write(actual));
            assertEquals(0, actual.revision());
            assertEquals(0, actual.table().revision());
            assertEquals(0, actual.chart().revision());
            for (int index = 0; index < expected.table().rowCount(); index++) {
                assertEquals(expected.table().row(index), actual.table().row(index));
            }
            assertEquals(UiJson.write(expected.chart().layout(1200, 628)), UiJson.write(actual.chart().layout(1200, 628)));
            assertEquals(before, controller.state());
            assertEquals(calls, port.calls());
            assertFalse(Files.exists(environment.cashMemory()));
        }
    }

    /** Повторное чтение не публикует экран, не повышает ревизию и не создаёт хранилище. */
    @Test void repeatedPlaceholderDoesNotPublishOrAdvanceState() {
        var port = new FakeUiPort(ClientProfile.web());
        var clock = AppClock.of(Clock.fixed(Instant.parse("2026-10-04T12:00:00Z"), ZoneOffset.UTC), null);
        var controller = new AppController(port, new AppEnvironment(LaunchOptions.parse("--registry", "memory"),
                home, home.resolve("CashMemory"), clock));
        var before = controller.state();
        String first = UiJson.write(controller.bootstrapPlaceholder());
        assertEquals(first, UiJson.write(controller.bootstrapPlaceholder()));
        assertEquals(before, controller.state());
        assertTrue(port.calls().isEmpty());
        assertFalse(Files.exists(home.resolve("CashMemory")));
    }

    /** Независимый reference сохраняет точную композицию, удаляемую из WebUiPort. */
    private static MainScreenModel legacyWebPlaceholder(AppState state, AppClock clock) {
        return new MainScreenModel(0, UiText.get("alert.info.title"), MenuModels.menuBar(state, ClientKind.WEB),
                MenuModels.toolbar(state, ClientKind.WEB), SummaryBuilder.build(state), LazyTableModel.build(state, 0),
                ChartLayout.model(state, 0), StatusBuilder.build(state, clock.now()), state.view().mode());
    }
}
