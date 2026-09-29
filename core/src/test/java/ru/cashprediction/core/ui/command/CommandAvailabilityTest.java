package ru.cashprediction.core.ui.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.ui.view.table.ViewStates;

/** Проверяет матрицу доступности команд, зависящих от строки, прогноза и режима «что-если». */
class CommandAvailabilityTest {

    @Test
    void rowCommandsReportTheDocumentedHintForEveryRowCategory() {
        AppState state = ViewStates.sample();
        assertAvailability(CommandId.EDIT_EDIT, "start", true, "");
        assertAvailability(CommandId.EDIT_DELETE, "start", false, CommandAvailability.HINT_NO_OPERATION);
        assertAvailability(CommandId.EDIT_ADJUST, "start", false, CommandAvailability.HINT_NO_RULE_EVENT);
        assertAvailability(CommandId.EDIT_EDIT, "missing", false, CommandAvailability.HINT_NO_OPERATION);
        assertAvailability(CommandId.EDIT_EDIT, "total@2026-10", false, CommandAvailability.HINT_NO_OPERATION);
    }

    @Test
    void forecastAndDateDependentCommandsDoNotSilentlyEnable() {
        AppState failed = ViewStates.failed(ViewStates.sample().document().plan(), "bad forecast",
                ViewStates.sample().view());
        assertAvailability(failed, CommandId.FILE_EXPORT_CSV, CommandArgs.NONE, false, CommandAvailability.HINT_NO_FORECAST);
        assertAvailability(failed, CommandId.CHART_SHOW_IN_TABLE, CommandArgs.date(LocalDate.now()), false,
                CommandAvailability.HINT_NO_FORECAST);
        assertAvailability(ViewStates.sample(), CommandId.CARD_SHOW_IN_TABLE, CommandArgs.card("m1", null), false, "");
        assertAvailability(ViewStates.sample(), CommandId.CHART_ADD_ONE_TIME, CommandArgs.date(null), false, "");
    }

    @Test
    void undoRedoWhatIfAndContextMenuHaveIndependentHints() {
        AppState state = ViewStates.sample();
        assertAvailability(state, CommandId.EDIT_UNDO, CommandArgs.NONE, false, CommandAvailability.HINT_NOTHING_TO_UNDO);
        assertAvailability(state, CommandId.EDIT_REDO, CommandArgs.NONE, false, CommandAvailability.HINT_NOTHING_TO_REDO);
        assertAvailability(state, CommandId.WHAT_IF_APPLY, CommandArgs.NONE, false, CommandAvailability.HINT_WHAT_IF_OFF);
        assertAvailability(state, CommandId.UI_CONTEXT_MENU, CommandArgs.NONE, false, CommandAvailability.HINT_NO_ROW);

        AppState active = withWhatIf(state, WhatIf.ofPercent(-10, 0, Money.ZERO));
        assertAvailability(active, CommandId.WHAT_IF_APPLY, CommandArgs.NONE, true, "");
        assertAvailability(active, CommandId.UI_CONTEXT_MENU, CommandArgs.card("m1", null), true, "");
    }

    private static AppState withWhatIf(AppState state, WhatIf whatIf) {
        return new AppState(state.revision(), state.profile(), state.today(), state.cashMemory(), state.plansFolder(),
                state.document(), state.view().withWhatIf(whatIf), state.selectedRowId(), state.pastExpanded(),
                state.settings(), state.recorder(), state.stores(), state.windows(), state.status(), state.autosaveProblem());
    }

    private static void assertAvailability(CommandId command, String rowId, boolean enabled, String hint) {
        assertAvailability(ViewStates.sample(), command, CommandArgs.row(rowId), enabled, hint);
    }

    private static void assertAvailability(AppState state, CommandId command, CommandArgs args, boolean enabled, String hint) {
        Availability actual = CommandAvailability.of(command, args, state);
        assertEquals(enabled, actual.enabled(), command.name());
        assertEquals(hint, actual.hintKey(), command.name());
    }
}
