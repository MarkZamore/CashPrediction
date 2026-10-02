package ru.cashprediction.fx.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.ui.text.UiText;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет устранённые расхождения S3 по свежим дампам настоящего клиента. */
@EnabledIfSystemProperty(named = "fx.s3Dump", matches = ".+")
class FxS3RenderedRegressionTest {
    /** Две строки успешного расчёта и пустой ошибочный расчёт сохраняют один настоящий резерв. */
    @Test void emptyAndValidResultsKeepTheSameActualMinimum() throws Exception {
        var valid = window("s05-forms-plan", "goal"); var invalid = window("s05-forms-plan", "invalid-target");
        assertEquals(2, ((List<?>) valid.get("results")).size()); assertTrue(((List<?>) invalid.get("results")).isEmpty());
        var first = (Map<?, ?>) valid.get("bounds"); var second = (Map<?, ?>) invalid.get("bounds");
        assertEquals(((Number) first.get("height")).doubleValue(), ((Number) second.get("height")).doubleValue(), 1);
        assertEquals(((Number) first.get("width")).doubleValue(), ((Number) second.get("width")).doubleValue(), 1);
        double reservedHeight = -1;
        for (String step : List.of("goal", "invalid-target")) {
            String metric;
            try (var paths = Files.walk(Path.of(System.getProperty("fx.s3Dump")))) {
                metric = paths.filter(p -> p.getFileName().toString().endsWith("stdout.log"))
                        .flatMap(p -> { try { return Files.readAllLines(p).stream(); } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); } })
                        .filter(s -> s.startsWith("FORM_METRICS " + step + " ") && s.contains("GOAL_CALCULATOR")).findFirst().orElseThrow();
            }
            var values = java.util.Arrays.stream(metric.split(" ")).filter(s -> s.contains("="))
                    .map(s -> s.split("=", 2)).collect(java.util.stream.Collectors.toMap(s -> s[0], s -> s[1]));
            assertEquals(3 * FxResultMetrics.lineHeight(), Double.parseDouble(values.get("resultsMin")), 0.000001);
            double measuredHeight = Double.parseDouble(values.get("results"));
            assertTrue(measuredHeight >= Double.parseDouble(values.get("resultsMin")));
            if (reservedHeight >= 0) assertEquals(reservedHeight, measuredHeight);
            reservedHeight = measuredHeight;
        }
    }
    /** Свежие тела центрируются относительно настоящего содержимого владельца, включая вложенный диалог. */
    @Test void freshFormsCenterOnActualOwnerContent() throws Exception {
        for (String step : List.of("settings", "goal")) checkCenter(dump("s05-forms-plan", step));
        for (String step : List.of("income-create", "adjustment", "nested-adjustment")) checkCenter(dump("s06-forms-ops", step));
    }
    private void checkCenter(Map<?, ?> tree) {
        var frame = (Map<?, ?>) tree.get("frame"); var windows = (List<?>) tree.get("windows");
        var child = (Map<?, ?>) windows.getLast(); var box = (Map<?, ?>) child.get("bounds");
        double x = 0, y = 0, width = ((Number) frame.get("contentWidth")).doubleValue(), height = ((Number) frame.get("contentHeight")).doubleValue();
        if (!"main".equals(child.get("ownerId"))) {
            var owner = windows.stream().map(w -> (Map<?, ?>) w).filter(w -> child.get("ownerId").equals(w.get("id"))).findFirst().orElseThrow();
            var parent = (Map<?, ?>) owner.get("bounds");
            x = ((Number) parent.get("x")).doubleValue(); y = ((Number) parent.get("y")).doubleValue();
            width = ((Number) parent.get("width")).doubleValue(); height = ((Number) parent.get("height")).doubleValue();
        }
        assertEquals(x + (width - ((Number) box.get("width")).doubleValue()) / 2, ((Number) box.get("x")).doubleValue(), 4);
        assertEquals(y + (height - ((Number) box.get("height")).doubleValue()) / 2, ((Number) box.get("y")).doubleValue(), 4);
    }
    /** Скрытый владелец стартового Alert не выдаёт вымышленную рамку главного окна. */
    @Test void startupAlertsHaveNoVisibleMainFrame() throws Exception {
        assertNull(dump("s17-recovery-dialog", "recovery-before-main").get("frame"));
        assertNull(dump("s18-already-running", "already-running").get("frame"));
    }
    /** Ctrl+I открывает настоящий диалог и закрывает прежде открытое контекстное меню. */
    @Test void openingIncomeClosesTheNativeContextMenu() throws Exception {
        assertFalse(((List<?>) dump("s15-keyboard", "keyboard-context").get("contextMenus")).isEmpty());
        var income = dump("s15-keyboard", "keyboard-income");
        assertFalse(((List<?>) income.get("windows")).isEmpty());
        assertTrue(((List<?>) income.get("contextMenus")).isEmpty());
    }
    /** Ширина дампа окна относится к живому содержимому, без добавки рамки ОС. */
    @Test void formBoundsExcludeNativeChrome() throws Exception {
        for (var entry : Map.of("settings", 560, "goal", 640).entrySet()) checkContentWidth(window("s05-forms-plan", entry.getKey()), entry.getValue());
        checkContentWidth(window("s06-forms-ops", "income-create"), 880);
        checkContentWidth(window("s06-forms-ops", "adjustment"), 560);
        checkContentWidth(window("s06-forms-ops", "nested-adjustment"), 560);
    }
    private void checkContentWidth(Map<?, ?> window, int width) {
        var bounds = (Map<?, ?>) window.get("bounds");
        assertEquals(width, ((Number) bounds.get("width")).doubleValue(), 4);
        assertTrue(((Number) bounds.get("height")).doubleValue() > 0);
        assertTrue(Math.abs(((Number) bounds.get("x")).doubleValue()) < 1200);
    }
    /** Нативные размеры сверяются с актуальным общим desktop/Web-контрактом, а не со старым Web JAR. */
    @Test void geometryMatchesTheCurrentSharedContract() throws Exception {
        var tree = dump("s13-undo-redo", "undo-skip");
        var regions = (Map<?, ?>) ((Map<?, ?>) tree.get("frame")).get("regions");
        assertEquals(84, ((Number) ((Map<?, ?>) regions.get("summary")).get("height")).doubleValue(), 4);
        assertEquals(1184, ((Number) ((Map<?, ?>) regions.get("table.header")).get("width")).doubleValue(), 4);
        assertEquals(456, ((Number) ((Map<?, ?>) regions.get("table.column.title")).get("width")).doubleValue(), 4);
        for (var card : (List<?>) ((Map<?, ?>) tree.get("summary")).get("cards")) {
            assertEquals(72, ((Number) ((Map<?, ?>) ((Map<?, ?>) card).get("bounds")).get("height")).doubleValue(), 4);
        }
    }
    /** Фон реально выбранной строки проходит общий путь заливки живых и виртуальных ячеек. */
    @Test void selectedRowsExposeTheActualSelectionPaint() throws Exception {
        for (var entry : Map.of("s13-undo-redo", "undo-skip", "s15-keyboard", "keyboard-context").entrySet()) {
            var table = (Map<?, ?>) dump(entry.getKey(), entry.getValue()).get("table");
            String selected = table.get("selectedRowId").toString(); assertFalse(selected.isEmpty());
            var row = ((List<?>) table.get("rows")).stream().map(value -> (Map<?, ?>) value).filter(value -> selected.equals(value.get("rowId"))).findFirst().orElseThrow();
            assertEquals("accent.weak", row.get("background"), entry.getKey());
        }
    }
    /** Idle дожидается записи настоящего первого сеанса, включая стартовый мастер. */
    @Test void firstRunSettlesTheRealRecorder() throws Exception {
        for (String step : List.of("wizard", "created")) {
            var status = (List<?>) dump("s01-first-run", step).get("status");
            var session = status.stream().map(value -> (Map<?, ?>) value).filter(value -> "session".equals(value.get("id"))).findFirst().orElseThrow();
            assertFalse(session.get("tooltip").toString().isEmpty(), step);
        }
    }
    /** Ввод спиннера применяется реальным таймером, а не остаётся лишь текстом редактора. */
    @Test void whatIfSpinnerChangesActualProjection() throws Exception {
        var tree = dump("s09-whatif", "whatif-table");
        assertEquals(123, ((Number) ((Map<?, ?>) tree.get("table")).get("rowCount")).intValue());
        var summary = (Map<?, ?>) tree.get("summary");
        var month = ((List<?>) summary.get("cards")).stream().map(value -> (Map<?, ?>) value).filter(value -> "m1".equals(value.get("id"))).findFirst().orElseThrow();
        assertEquals("196 020 ₽", month.get("value"));
    }
    private Map<?, ?> dump(String scenario, String step) throws Exception {
        Path root = Path.of(System.getProperty("fx.s3Dump"));
        Path path = root.resolve(scenario).resolve("out").resolve(scenario).resolve(step + ".json");
        if (!Files.isRegularFile(path)) try (var paths = Files.walk(root.resolve("fx").resolve(scenario))) {
            path = paths.filter(p -> p.getFileName().toString().equals(step + ".json") && p.getParent().getFileName().toString().equals(scenario)).findFirst().orElseThrow();
        }
        return (Map<?, ?>) JsonParser.parse(Files.readString(path));
    }
    private Map<?, ?> window(String scenario, String step) throws Exception {
        return (Map<?, ?>) ((List<?>) dump(scenario, step).get("windows")).getLast();
    }
    private List<?> fields(Map<?, ?> window) { return (List<?>) window.get("fields"); }
    private Map<?, ?> field(Map<?, ?> window, String id) {
        return fields(window).stream().map(value -> (Map<?, ?>) value).filter(value -> id.equals(value.get("id"))).findFirst().orElseThrow();
    }
    /** Самотест завершает денежный ввод настоящей потерей фокуса. */
    @Test void committedMoneyUsesCoreFormatting() throws Exception {
        var goal = window("s05-forms-plan", "goal-extra");
        assertEquals("5 000,00", field(goal, "extraSaving").get("text"));
        assertEquals("400 000,00", field(goal, "target").get("text"));
        assertEquals("25 000,00", field(window("s06-forms-ops", "income-valid"), "amount").get("text"));
    }
    /** Невидимые условные поля отсутствуют в реальном дампе редактора. */
    @Test void hiddenFieldsAreExcluded() throws Exception {
        var income = window("s06-forms-ops", "income-create");
        assertTrue(fields(income).stream().map(value -> (Map<?, ?>) value).allMatch(value -> Boolean.TRUE.equals(value.get("visible"))));
        assertFalse(fields(income).stream().map(value -> (Map<?, ?>) value).anyMatch(value -> "dayOfWeek".equals(value.get("id")) || "dayOfYear".equals(value.get("id"))));
    }
    /** Подсказка многострочного поля приходит из общего каталога. */
    @Test void multilinePromptIsRendered() throws Exception {
        assertEquals(UiText.get("adjustment.note.prompt"), field(window("s06-forms-ops", "adjustment"), "note").get("prompt"));
    }
    /** Длинные поля не увеличивают ширину живого содержимого сверх строгого допуска спецификации. */
    @Test void dialogWidthsFollowTheSpec() throws Exception {
        for (var entry : Map.of("settings", 560, "goal", 640).entrySet()) checkWidth(window("s05-forms-plan", entry.getKey()), entry.getValue());
        checkWidth(window("s06-forms-ops", "income-create"), 880);
        checkWidth(window("s06-forms-ops", "nested-adjustment"), 560);
    }
    private void checkWidth(Map<?, ?> window, int contentWidth) {
        double width = ((Number) ((Map<?, ?>) window.get("bounds")).get("width")).doubleValue();
        assertEquals(contentWidth, width, 4, window.get("type") + ": " + width);
    }
    /** Кнопки LEFT прижаты к началу панели, а отмена остаётся справа и последней. */
    @Test void goalButtonsHaveAnActualGrowingGap() throws Exception {
        var goal = window("s05-forms-plan", "goal"); var buttons = (List<?>) goal.get("buttons");
        assertEquals(List.of("saveGoal", "showExtra", "close"), buttons.stream().map(b -> ((Map<?, ?>) b).get("id")).toList());
        double left = ((Number) ((Map<?, ?>) buttons.getFirst()).get("x")).doubleValue();
        double right = ((Number) ((Map<?, ?>) buttons.getLast()).get("x")).doubleValue();
        assertTrue(left <= 16, "LEFT: " + left); assertTrue(right > 500, "CANCEL: " + right);
        assertEquals(false, ((Map<?, ?>) buttons.getFirst()).get("isDefault"));
    }
    /** Вложенная модальная корректировка принадлежит реальному редактору, а не главному окну. */
    @Test void nestedAdjustmentKeepsItsActualOwner() throws Exception {
        var windows = (List<?>) dump("s06-forms-ops", "nested-adjustment").get("windows");
        assertEquals(2, windows.size());
        var parent = (Map<?, ?>) windows.getFirst(); var child = (Map<?, ?>) windows.getLast();
        assertEquals(parent.get("id"), child.get("ownerId")); assertEquals(true, child.get("modal"));
        assertTrue(((Number) ((Map<?, ?>) dump("s06-forms-ops", "nested-adjustment").get("classCensus")).get("ContextMenuEvent")).intValue() > 0);
    }
    /** Наведение после обновления статуса использует ревизию графика и показывает настоящую карточку дня. */
    @Test void chartHoverSurvivesScreenRevisionChanges() throws Exception {
        var popups = (List<?>) dump("s03-chart", "day-card").get("popups");
        assertTrue(popups.stream().map(value -> (Map<?, ?>) value).anyMatch(value -> "dayCard".equals(value.get("kind"))));
    }
    /** Enter и Esc направляются настоящему Popup, не открывая редактор правила в главном окне. */
    @Test void quickEditKeysReachThePopup() throws Exception {
        var quick = window("s12-quick-edit", "invalid-quick");
        assertEquals("QUICK_EDIT_POPUP", quick.get("type")); assertFalse(quick.get("problem").toString().isEmpty());
        assertTrue(((List<?>) dump("s12-quick-edit", "quick-applied").get("windows")).isEmpty());
        assertTrue(((List<?>) dump("s12-quick-edit", "quick-cancelled").get("windows")).isEmpty());
    }
    /** Alert до главного окна показывает восстановление без ошибки пустой сцены владельца. */
    @Test void recoveryCanOpenBeforeMain() throws Exception {
        var recovery = dump("s17-recovery-dialog", "recovery-before-main");
        assertTrue(((List<?>) recovery.get("windows")).isEmpty());
        assertEquals("crashRecovery", ((Map<?, ?>) ((List<?>) recovery.get("alerts")).getFirst()).get("purpose"));
    }
    /** Применённая разовая операция сохраняет введённую дату вместо прежнего значения. */
    @Test void committedDateReachesTheTable() throws Exception {
        var table = (Map<?, ?>) dump("s06-forms-ops", "adjusted").get("table");
        var row = ((List<?>) table.get("rows")).stream().map(value -> (Map<?, ?>) value).filter(value -> "t2".equals(value.get("rowId"))).findFirst().orElseThrow();
        assertEquals("15.10.2026", ((List<?>) row.get("cells")).getFirst());
    }
    /** Стартовый Esc закрывает модальный мастер даже до получения фокуса от Windows. */
    @Test void startupEscapeReleasesMain() throws Exception {
        assertTrue(((List<?>) dump("s02-sample-table", "table").get("windows")).isEmpty());
        var table = (Map<?, ?>) dump("s02-sample-table", "table").get("table");
        assertTrue(((Number) table.get("rowCount")).intValue() > 1);
    }
    /** Наведение показывает спарклайн, а команда меню скрывает его по §5.1. */
    @Test void menuCommandsDismissCardPopup() throws Exception {
        assertTrue(((List<?>) dump("s03-chart", "sparkline").get("popups")).stream().map(value -> (Map<?, ?>) value).anyMatch(value -> "sparkline".equals(value.get("kind"))));
        for (String step : List.of("no-markers", "month-bars", "whole-horizon")) {
            var popups = (List<?>) dump("s03-chart", step).get("popups");
            assertFalse(popups.stream().map(value -> (Map<?, ?>) value).anyMatch(value -> "sparkline".equals(value.get("kind"))), step);
        }
    }
    /** Геометрия визуальных проверок берётся из настоящих заголовков и текстовых узлов. */
    @Test void visualMeasurementsAreInsideContent() throws Exception {
        var tree = dump("s02-sample-table", "table");
        var frame = (Map<?, ?>) tree.get("frame"); var regions = (Map<?, ?>) frame.get("regions");
        for (String id : List.of("toolbar.baseline", "status.baseline")) {
            var baseline = (Map<?, ?>) regions.get(id); assertNotNull(baseline, id);
            var owner = (Map<?, ?>) regions.get(id.substring(0, id.indexOf('.')));
            double y = ((Number) baseline.get("y")).doubleValue();
            // Нормализатор округляет координаты до двух пикселей; исходную толщину проверяет FxAdapterTest.
            double thickness = ((Number) baseline.get("height")).doubleValue(); assertTrue(thickness > 0 && thickness <= 2);
            assertTrue(y >= ((Number) owner.get("y")).doubleValue());
            assertTrue(y < ((Number) owner.get("y")).doubleValue() + ((Number) owner.get("height")).doubleValue());
            assertTrue(((Number) baseline.get("width")).doubleValue() > 0);
        }
        long measured = regions.keySet().stream().map(Object::toString).filter(id -> id.startsWith("table.column.")).count();
        assertEquals(((List<?>) ((Map<?, ?>) tree.get("table")).get("columns")).size(), measured);
        for (Object key : regions.keySet()) if (key.toString().startsWith("table.column.")) {
            var box = (Map<?, ?>) regions.get(key);
            assertTrue(((Number) box.get("width")).doubleValue() > 0);
            assertTrue(((Number) box.get("x")).doubleValue() + ((Number) box.get("width")).doubleValue() <= ((Number) frame.get("contentWidth")).doubleValue());
        }
    }
}
