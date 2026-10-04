package ru.cashprediction.core.ui.view.table;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.SamplePlan;
import ru.cashprediction.core.forecast.Flags;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.Origin;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.core.ui.text.UiText;

/** Проверяет происхождение значков, смещения UTF-16 и совместимость строкового контракта. */
class DecoratedTooltipTest {
    private static final LocalDate TODAY = ViewStates.TODAY;
    private static final List<String> SERVICE_KEYS = List.of("table.tip.amountChanged", "table.tip.moved",
            "table.tip.shifted", "table.tip.skipped", "table.tip.whatIfAmount");
    private static final List<String> ICON_KEYS = List.of("\u270e", "\u2192", "\u21c4", "\u2715", "\u0394");
    private final TableRows rows = new TableRows(SamplePlan.create(TODAY), TODAY);

    @Test
    void matchingMultilineTitleAndNoteNeverCreateIcons() {
        String prose = "\ud83d\ude00\n" + serviceText();
        ForecastRow row = event(prose, prose, Flags.NONE, Origin.ONE_TIME);
        DecoratedTooltip tooltip = rows.decoratedEventTooltip(row, "title");
        assertTrue(tooltip.iconPositions().isEmpty());
        assertEquals(expected(row, "title"), tooltip.text());
        assertEquals(tooltip.text(), rows.eventTooltip(row, "title"));
    }

    @Test
    void allRealFlagsAreMarkedAfterEmojiAndMultilineTitleOnly() {
        String title = "\ud83d\ude00\n" + serviceText();
        ForecastRow row = event(title, serviceText(), new Flags(true, true, true, true, false, true), Origin.RULE);
        DecoratedTooltip tooltip = rows.decoratedEventTooltip(row, "income");
        String prefix = title + "\n" + UiText.get("table.tip.rule", "") + "\n";
        int offset = prefix.length();
        List<DecoratedTooltip.IconPosition> expectedPositions = new ArrayList<>();
        for (int i = 0; i < SERVICE_KEYS.size(); i++) {
            expectedPositions.add(new DecoratedTooltip.IconPosition(offset, ICON_KEYS.get(i)));
            offset += UiText.get(SERVICE_KEYS.get(i)).length() + 1;
        }
        assertEquals(expectedPositions, tooltip.iconPositions());
        assertEquals(prefix.codePointCount(0, prefix.length()) + 1, tooltip.iconPositions().get(0).offset());
        assertEquals(expected(row, "income"), tooltip.text());
        assertEquals(tooltip.text(), rows.eventTooltip(row, "income"));
        assertTrue(tooltip.iconPositions().stream().allMatch(p -> p.offset() >= prefix.length()
                && p.offset() < tooltip.text().indexOf(UiText.get("table.tip.note", ""), prefix.length())));
        assertEquals(Map.of("text", tooltip.text(), "iconPositions", expectedPositions.stream()
                .map(p -> Map.of("offset", p.offset(), "key", p.key())).toList()), UiJson.toTree(tooltip));
    }

    @Test
    void quickEditSuffixAndWhatIfOriginKeepPlaintextCompatibility() {
        ForecastRow rule = event("title", serviceText(), new Flags(false, true, false, false, false, true), Origin.RULE);
        for (String column : List.of("income", "expense", "title")) {
            assertEquals(expected(rule, column), rows.decoratedEventTooltip(rule, column).text());
        }
        ForecastRow whatIf = event(serviceText(), serviceText(), Flags.NONE.withWhatIf(true), Origin.WHAT_IF);
        DecoratedTooltip tooltip = rows.decoratedEventTooltip(whatIf, "income");
        assertTrue(tooltip.iconPositions().isEmpty());
        assertEquals(expected(whatIf, "income"), tooltip.text());
    }

    @Test
    void lazyModelPreservesEveryColumnAndLeavesNonEventRowsPlain() {
        TableModel model = LazyTableModel.build(ViewStates.sample(), 7);
        boolean sawFlag = false;
        for (int index = 0; index < model.rowCount(); index++) {
            for (ColumnSpec column : model.columns()) {
                DecoratedTooltip tooltip = model.decoratedTooltip(index, column.id());
                assertEquals(model.tooltip(index, column.id()), tooltip.text());
                if (model.row(index).kind() == RowKind.MONTH_TOTAL || model.row(index).kind() == RowKind.PAST_HEADER
                        || model.row(index).kind() == RowKind.START) assertTrue(tooltip.iconPositions().isEmpty());
                sawFlag |= !tooltip.iconPositions().isEmpty();
            }
        }
        assertTrue(sawFlag);
        assertThrows(IndexOutOfBoundsException.class, () -> model.decoratedTooltip(-1, "title"));
        assertThrows(IndexOutOfBoundsException.class, () -> model.decoratedTooltip(model.rowCount(), "title"));
    }

    /** Создаёт строку без правила, чтобы текст повтора был детерминированным. */
    private static ForecastRow event(String title, String note, Flags flags, Origin origin) {
        return new ForecastRow(TODAY, TODAY, title, Kind.INCOME, "", Money.ofMajor(10), Money.ofMajor(100_000),
                origin, null, null, flags, note);
    }

    /** Возвращает пользовательский текст, дословно совпадающий со всеми служебными строками. */
    private static String serviceText() {
        return String.join("\n", SERVICE_KEYS.stream().map(UiText::get).toList());
    }

    /** Независимо фиксирует прежнюю последовательность строк, включая пустую строку быстрой правки. */
    private static String expected(ForecastRow row, String column) {
        List<String> lines = new ArrayList<>();
        lines.add(row.origin() == Origin.WHAT_IF ? UiText.get("table.whatIfTitle") : row.title());
        lines.add(switch (row.origin()) {
            case RULE -> UiText.get("table.tip.rule", "");
            case ONE_TIME -> UiText.get("table.tip.oneTime");
            case WHAT_IF -> UiText.get("table.tip.whatIf");
            case START -> UiText.get("table.tip.start");
        });
        if (row.flags().amountChanged()) lines.add(UiText.get(SERVICE_KEYS.get(0)));
        if (row.flags().moved()) lines.add(UiText.get(SERVICE_KEYS.get(1)));
        if (row.flags().shifted()) lines.add(UiText.get(SERVICE_KEYS.get(2)));
        if (row.flags().skipped()) lines.add(UiText.get(SERVICE_KEYS.get(3)));
        if (row.flags().whatIf() && row.origin() != Origin.WHAT_IF) lines.add(UiText.get(SERVICE_KEYS.get(4)));
        lines.add(UiText.get("table.tip.amount", row.amount().formatSigned() + " " + SamplePlan.create(TODAY).currency()));
        lines.add(UiText.get("table.tip.balance", row.balanceAfter().format(SamplePlan.create(TODAY).currency())));
        lines.add(UiText.get("table.tip.note", row.note()));
        String text = String.join("\n", lines);
        return row.origin() == Origin.RULE && !row.flags().skipped() && column.equals("income")
                ? text + "\n\n" + UiText.get("table.tip.quickEdit") : text;
    }
}
