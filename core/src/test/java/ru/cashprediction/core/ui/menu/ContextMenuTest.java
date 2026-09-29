package ru.cashprediction.core.ui.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.app.ClientKind;
import ru.cashprediction.core.ui.command.CommandId;
import ru.cashprediction.core.ui.view.table.ViewStates;

/** Проверяет наборы контекстных меню всех целей S1. */
class ContextMenuTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 5);
    private static final ContextFacts DATES = new ContextFacts() {
        @Override public Optional<LocalDate> cardDate(AppState state, String cardId) {
            return cardId.equals("dated") ? Optional.of(DATE) : Optional.empty();
        }
        @Override public Optional<LocalDate> chartDate(AppState state, ContextTarget.Chart chart) {
            return chart.x() >= 0 ? Optional.of(DATE) : Optional.empty();
        }
    };

    @Test
    void rowTotalAndPastHeaderUseStableCommands() {
        AppState state = ViewStates.sample();
        String rule = state.document().forecast().rows().stream()
                .filter(row -> row.rowId().contains("@")).findFirst().orElseThrow().rowId();
        assertCommands(menu(state, new ContextTarget.Row(rule)), CommandId.ROW_EDIT, CommandId.ROW_QUICK_EDIT,
                CommandId.ROW_ADJUST, CommandId.ROW_SKIP, CommandId.ROW_RESET, CommandId.ROW_ADD_ONE_TIME,
                CommandId.ROW_GO_TO_RULE, CommandId.ROW_DISABLE_RULE, CommandId.ROW_COPY, CommandId.ROW_DELETE);
        assertCommands(menu(state, new ContextTarget.Total("total@2026-10")), CommandId.TOTAL_COPY,
                CommandId.VIEW_FLAG_MONTH_TOTALS);
        assertCommands(menu(state, new ContextTarget.PastHeader("past@group")), CommandId.PAST_TOGGLE);
        assertTrue(menu(state, new ContextTarget.Row("not-a-row")).isEmpty());
    }

    @Test
    void cardsAndChartDisableDateActionsWithoutDate() {
        List<MenuNode> dated = menu(ViewStates.sample(), new ContextTarget.Card("dated"));
        MenuNode.Action show = action(dated, "ctx.card.showInTable");
        assertTrue(show.enabled());
        assertEquals(DATE, show.args().date());

        MenuNode.Action undated = action(menu(ViewStates.sample(), new ContextTarget.Card("missing")), "ctx.card.showInTable");
        assertFalse(undated.enabled());
        assertEquals(null, undated.args().date());

        List<MenuNode> inside = menu(ViewStates.sample(), new ContextTarget.Chart(1, 1, 100, 100));
        assertTrue(action(inside, "ctx.chart.showInTable").enabled());
        assertTrue(action(inside, "ctx.chart.addOneTime").enabled());
        List<MenuNode> outside = menu(ViewStates.sample(), new ContextTarget.Chart(-1, 1, 100, 100));
        assertFalse(action(outside, "ctx.chart.showInTable").enabled());
        assertFalse(action(outside, "ctx.chart.addOneTime").enabled());
        assertCommands(inside, CommandId.CHART_SHOW_IN_TABLE, CommandId.CHART_ADD_ONE_TIME,
                CommandId.VIEW_FLAG_CHART_MARKERS, CommandId.VIEW_FLAG_CHART_BARS, CommandId.FILE_SAVE_PNG);
    }

    @Test
    void previewContainsOnlyAdjustmentForTheSelectedIndex() {
        MenuNode.Action item = action(menu(ViewStates.sample(), new ContextTarget.Preview("rule-window", 4)),
                "ctx.preview.adjust");
        assertEquals(CommandId.PREVIEW_ADJUST, item.command());
        assertEquals("rule-window", item.args().key());
        assertEquals("4", item.args().value());
    }

    private static List<MenuNode> menu(AppState state, ContextTarget target) {
        return MenuModels.contextMenu(state, target, ClientKind.SWING, DATES);
    }

    private static MenuNode.Action action(List<MenuNode> nodes, String id) {
        return (MenuNode.Action) nodes.stream().filter(node -> node.id().equals(id)).findFirst().orElseThrow();
    }

    private static void assertCommands(List<MenuNode> nodes, CommandId... expected) {
        assertEquals(List.of(expected), nodes.stream().map(ContextMenuTest::command).filter(java.util.Optional::isPresent)
                .map(java.util.Optional::orElseThrow).toList());
    }

    private static java.util.Optional<CommandId> command(MenuNode node) {
        if (node instanceof MenuNode.Action action) {
            return java.util.Optional.of(action.command());
        }
        if (node instanceof MenuNode.Check check) {
            return java.util.Optional.of(check.command());
        }
        return java.util.Optional.empty();
    }
}
