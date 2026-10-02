package ru.cashprediction.parity.check.visual;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.dump.DumpNormalizer;
import ru.cashprediction.core.ui.selftest.*;
import ru.cashprediction.core.ui.token.ColorToken;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.view.summary.SummaryBuilder;
import ru.cashprediction.parity.launch.ClientTarget;
import static org.junit.jupiter.api.Assertions.*;

/** Синтетические данные проверяют только измеритель и отчёт; ни один такой PNG не используется как реальный. */
final class VisualRegressionTest {
    @TempDir Path temporary;

    /** Явная полная матрица охватывает каждый dump всех 18 исходных сценариев, не только первые четыре. */
    @Test void fullVisualPlanIncludesEveryActualScenarioDump() {
        var checkpoints = VisualPlan.checkpoints("all", "all");
        assertEquals(new HashSet<>(SelfTestScript.SCENARIOS),
                new HashSet<>(checkpoints.stream().map(VisualPlan.Checkpoint::scenario).toList()));
        int expected = 0;
        for (String scenario : SelfTestScript.SCENARIOS) {
            var source = SelfTestScript.load(scenario);
            var steps = source.lines().stream().filter(l -> l.command() instanceof SelfTestCommand.Dump)
                    .map(l -> ((SelfTestCommand.Dump) l.command()).step()).toList();
            expected += steps.size();
            assertEquals(steps, checkpoints.stream().filter(c -> c.scenario().equals(scenario))
                    .map(VisualPlan.Checkpoint::step).toList());
            for (var checkpoint : checkpoints.stream().filter(c -> c.scenario().equals(scenario)).toList()) {
                int end = 0;
                while (!(source.lines().get(end).command() instanceof SelfTestCommand.Dump d
                        && d.step().equals(checkpoint.step()))) end++;
                assertEquals(source.lines().subList(0, end + 1).stream().map(SelfTestScript.Line::command).toList(),
                        checkpoint.script().lines().subList(1, end + 2).stream().map(SelfTestScript.Line::command).toList());
                assertEquals(new SelfTestCommand.Shot(checkpoint.step()), checkpoint.script().lines().getLast().command());
                assertEquals(end + 3, checkpoint.script().lines().size());
            }
        }
        assertEquals(expected, checkpoints.size());
        assertEquals(18, VisualPlan.checkpoints("all", "first").size());
        assertEquals("s06-forms-ops", VisualPlan.checkpoints("s06", "first").getFirst().scenario());
    }

    /** Дополнительная измеренная область не игнорируется ни отдельно, ни в парном сравнении. */
    @Test void rejectsUnverifiedAdditionalRegions() throws Exception {
        var source = observation("fx", 0, true);
        var regions = new LinkedHashMap<>(source.frame().regions());
        regions.put("observed.extra", new UiDump.Box(Double.NaN, 0, 10, 10));
        assertTrue(VisualImages.check(withRegions(source, regions), png(fixture())).stream()
                .anyMatch(f -> f.startsWith("region/observed.extra")));
        regions.put("observed.extra", new UiDump.Box(20, 200, 10, 10));
        var left = withRegions(source, regions);
        assertTrue(VisualImages.compare(left, source).stream().anyMatch(f -> f.startsWith("region/observed.extra")));
        regions.put("observed.extra", new UiDump.Box(25, 200, 10, 10));
        assertTrue(VisualImages.compare(left, withRegions(source, regions)).stream()
                .anyMatch(f -> f.startsWith("region/observed.extra")));
    }

    /** Правильный заголовок и колонки внутри PNG не могут быть расположены вне центральной области. */
    @Test void rejectsTableHeaderAndColumnsOutsideCenter() throws Exception {
        var source = observation("fx", 0, true);
        var regions = new LinkedHashMap<>(source.frame().regions());
        regions.put("table.header", new UiDump.Box(0, 0, 1200, 28));
        regions.put("table.column.title", new UiDump.Box(0, 0, 1200, 28));
        assertTrue(VisualImages.check(withRegions(source, regions), png(fixture())).stream()
                .anyMatch(f -> f.startsWith("measurement/table.columns") && f.contains("outside its region")));
    }

    /** Повторный id карточки не схлопывается в Map, создавая ложный успех обоих клиентов. */
    @Test void rejectsDuplicateCardIdentityEvenWithMatchingBoundsAndPixels() throws Exception {
        var source = observation("fx", 0, true);
        var card = source.summary().cards().getFirst();
        var changed = new UiDump(source.schema(), source.client(), source.scenario(), source.step(), source.frame(),
                source.menuBar(), source.toolbar(), new UiDump.Summary(true, List.of(card, card), ""), source.table(),
                source.chart(), source.status(), source.contextMenus(), source.windows(), source.alerts(), source.popups(),
                source.screens(), source.chooserRequests(), source.classCensus(), source.counters());
        assertTrue(VisualImages.check(changed, png(fixture())).stream().anyMatch(f -> f.contains("Duplicate visual identity")));
        assertTrue(VisualImages.compare(source, changed).stream().anyMatch(f -> f.contains("Duplicate visual identity")));
    }

    /** Запрошенная visual-проверка не может молча пропуститься из-за отсутствующего второго флага. */
    @Test void doubleGateDoesNotSilentlySkipRequestedVisuals() {
        var properties = new Properties(); assertFalse(VisualPlan.enabled(properties));
        properties.setProperty("parity.realClients", "true"); assertFalse(VisualPlan.enabled(properties));
        properties.setProperty("parity.visual", "true"); assertTrue(VisualPlan.enabled(properties));
        properties.setProperty("parity.realClients", "false");
        assertThrows(IllegalArgumentException.class, () -> VisualPlan.enabled(properties));
    }

    /** Префикс сохраняет каждую исходную команду; только size и shot добавлены стендом. */
    @Test void extendsActualScenarioWithoutSubstitutingActions() {
        for (var checkpoint : VisualPlan.checkpoints("", "first")) {
            var source = SelfTestScript.load(checkpoint.scenario());
            var actual = checkpoint.script().lines();
            assertEquals(new SelfTestCommand.Size(1200, 800), actual.getFirst().command());
            int index = 0;
            for (var line : source.lines()) {
                assertEquals(line.command(), actual.get(++index).command());
                if (line.command() instanceof SelfTestCommand.Dump d && d.step().equals(checkpoint.step())) break;
            }
            assertEquals(new SelfTestCommand.Shot(checkpoint.step()), actual.getLast().command());
            assertEquals(index + 2, actual.size());
        }
        assertEquals(4, VisualPlan.checkpoints("", "first").size());
        assertTrue(VisualPlan.checkpoints("", "all").size() > 4);
        assertThrows(IllegalArgumentException.class, () -> VisualPlan.checkpoints("s99", "first"));
        assertThrows(IllegalArgumentException.class, () -> VisualPlan.checkpoints("s02", "missing"));
    }

    /** Повреждение цвета в фиксированной пробе обнаруживается без изменения дампа или золотого файла. */
    @Test void rejectsBoundedPixelMutations() throws Exception {
        UiDump dump = observation("fx", 0, true);
        BufferedImage image = fixture();
        assertTrue(VisualImages.check(dump, png(image)).isEmpty());
        image.setRGB(2, 42, ColorToken.EXPENSE.argb());
        assertTrue(VisualImages.check(dump, png(image)).stream().anyMatch(e -> e.startsWith("color/toolbar")));
        image = fixture(); image.setRGB(21, 81, ColorToken.EXPENSE.argb());
        assertTrue(VisualImages.check(dump, png(image)).stream().anyMatch(e -> e.startsWith("card/")));
    }

    /** Проверка карточки требует принадлежности сводке и сохраняет существующий допуск 4 px. */
    @Test void rejectsCardOutsideSummaryEvenWhenItsPixelMatches() throws Exception {
        var original = observation("fx", 0, true);
        for (int overflow : List.of(4, 5)) {
            var regions = new LinkedHashMap<>(original.frame().regions());
            var summary = regions.get("summary");
            var card = original.summary().cards().getFirst().bounds();
            regions.put("summary", new UiDump.Box(summary.x(), summary.y(), summary.width(),
                    card.y() + card.height() - summary.y() - overflow));
            var failures = VisualImages.check(withRegions(original, regions), png(fixture()));
            if (overflow == 4) assertTrue(failures.isEmpty(), failures.toString());
            else assertTrue(failures.stream().anyMatch(f -> f.startsWith("card/now: Measurement outside its region")));
        }
    }

    /** Полное отсутствие колонок в произвольном dump не скрывается именем шага; скрытая таблица не угадывается. */
    @Test void requiresColumnsForObservedSelectedTableBeyondTableStep() throws Exception {
        var source = observation("web", 0, true);
        var regions = new LinkedHashMap<>(source.frame().regions());
        regions.keySet().removeIf(id -> id.startsWith("table."));
        for (boolean selected : List.of(false, true)) {
            var toolbar = new UiDump.Toolbar(false, List.of(new UiDump.ToolbarItem("tb.table", "Toggle", "", "", "",
                    true, selected, "", false, 0, new UiDump.Box(20, 28, 80, 24), List.of())));
            var d = withRegions(source, regions);
            var changed = new UiDump(d.schema(), d.client(), d.scenario(), "settings", d.frame(), d.menuBar(), toolbar,
                    d.summary(), d.table(), d.chart(), d.status(), d.contextMenus(), d.windows(), d.alerts(), d.popups(),
                    d.screens(), d.chooserRequests(), d.classCensus(), d.counters());
            var failures = VisualImages.check(changed, png(fixture()));
            if (selected) assertTrue(failures.stream().anyMatch(f -> f.startsWith("measurement/table.columns")));
            else assertTrue(failures.isEmpty(), failures.toString());
        }
    }

    /** Геометрия 4 px принимается, 5 px отклоняется; baseline проверяется независимо с пределом 3 px. */
    @Test void rejectsGeometryAndBaselineMutations() {
        UiDump reference = observation("fx", 0, true);
        assertTrue(VisualImages.compare(reference, observation("swing", 0, true)).isEmpty());
        assertTrue(VisualImages.compare(reference, observation("web", 4, true)).isEmpty());
        assertFalse(VisualImages.compare(reference, observation("web", 5, true)).isEmpty());
        var regions = new LinkedHashMap<>(reference.frame().regions());
        regions.put("status.baseline", new UiDump.Box(0, 796, 1200, 1));
        var changed = withRegions(reference, regions);
        assertTrue(VisualImages.compare(reference, changed).stream().anyMatch(e -> e.startsWith("status.baseline")));
        assertThrows(AssertionError.class, () -> VisualImages.inside(new UiDump.Box(-1, 0, 10, 10)));
        assertThrows(AssertionError.class, () -> VisualImages.inside(new UiDump.Box(0, 0, 1201, 800)));
        assertThrows(AssertionError.class, () -> VisualImages.inside(new UiDump.Box(Double.NaN, 0, 10, 10)));
    }

    /** Недостающие реальные измерения остаются ошибками и при одинаковых пустых данных двух клиентов. */
    @Test void rejectsMissingMeasurementAndMismatchedIdentity() throws Exception {
        UiDump missing = observation("fx", 0, false);
        var failures = VisualImages.check(missing, png(fixture()));
        assertTrue(failures.stream().anyMatch(e -> e.contains("toolbar.baseline")));
        assertTrue(failures.stream().anyMatch(e -> e.contains("table.column")));
        assertFalse(VisualImages.compare(missing, missing).isEmpty());
    }

    /** Одинаковое число рамок не скрывает чужой id, выход за PNG или отсутствие общего заголовка. */
    @Test void rejectsWrongColumnIdentityAndClippedHeader() throws Exception {
        UiDump original = observation("fx", 0, true);
        var regions = new LinkedHashMap<>(original.frame().regions());
        regions.put("table.column.fake", regions.remove("table.column.title"));
        assertTrue(VisualImages.check(withRegions(original, regions), png(fixture())).stream()
                .anyMatch(e -> e.startsWith("measurement/table.columns")));
        regions = new LinkedHashMap<>(original.frame().regions());
        regions.put("table.column.title", new UiDump.Box(1190, 180, 20, 28));
        assertTrue(VisualImages.check(withRegions(original, regions), png(fixture())).stream()
                .anyMatch(e -> e.startsWith("measurement/table.columns")));
        regions = new LinkedHashMap<>(original.frame().regions());
        regions.remove("table.header");
        assertTrue(VisualImages.check(withRegions(original, regions), png(fixture())).stream()
                .anyMatch(e -> e.startsWith("measurement/table.columns")));
    }

    /** Одинаково неверная baseline двух клиентов не подтверждает измерение настоящей строки текста. */
    @Test void rejectsBaselineOutsideItsOwnerOrWithWrongHeight() throws Exception {
        UiDump original = observation("fx", 0, true);
        for (var bad : List.of(new UiDump.Box(0, 700, 1200, 1), new UiDump.Box(0, 48, 1200, 3), new UiDump.Box(0, 48, 1200, 5))) {
            var regions = new LinkedHashMap<>(original.frame().regions()); regions.put("toolbar.baseline", bad);
            assertTrue(VisualImages.check(withRegions(original, regions), png(fixture())).stream()
                    .anyMatch(e -> e.startsWith("measurement/toolbar.baseline")));
        }
    }

    /** Нормализованный дамп сохраняет измеренную линию; округление 1 в 2 не создаёт ложную ошибку. */
    @Test void acceptsNormalizedBaselineWithoutRelaxingItsPosition() throws Exception {
        UiDump original = observation("fx", 0, true);
        var normalized = DumpNormalizer.normalize(original, temporary, "ru/cashprediction/selftest/visual");
        assertEquals(2, normalized.frame().regions().get("toolbar.baseline").height());
        assertTrue(VisualImages.check(normalized, png(fixture())).isEmpty());
        var regions = new LinkedHashMap<>(normalized.frame().regions());
        regions.put("toolbar.baseline", new UiDump.Box(0, 700, 1200, 2));
        assertTrue(VisualImages.check(withRegions(normalized, regions), png(fixture())).stream()
                .anyMatch(error -> error.startsWith("measurement/toolbar.baseline")));
    }

    /** Один выбранный клиент также обязан иметь конечные координаты кнопок и настоящие рамки тулбара. */
    @Test void rejectsInvalidControlsWithoutPairwiseComparison() throws Exception {
        UiDump original = observation("fx", 0, true);
        var badToolbar = new UiDump.Toolbar(false, List.of(new UiDump.ToolbarItem("filter", "FilterField", "", "", "",
                true, false, "", false, 0, new UiDump.Box(20, 700, 180, 28), List.of())));
        var badAlert = new UiDump.Alert("a1", "deleteRule", "CONFIRMATION", "", "", 460, "", "", "", "", false,
                List.of(new UiDump.Button("delete", "", "", true, true, Double.NaN)));
        var bad = new UiDump(1, original.client(), original.scenario(), original.step(), original.frame(), original.menuBar(),
                badToolbar, original.summary(), original.table(), original.chart(), original.status(), original.contextMenus(),
                original.windows(), List.of(badAlert), original.popups(), original.screens(), original.chooserRequests(), Map.of(), Map.of());
        var failures = VisualImages.check(bad, png(fixture()));
        assertTrue(failures.stream().anyMatch(e -> e.startsWith("toolbar/filter")));
        assertTrue(failures.stream().anyMatch(e -> e.startsWith("measurement/buttons")));
    }

    /** Координата кнопки Web-сообщения сравнивается наравне с desktop, без исключения по имени клиента. */
    @Test void comparesAlertButtonsInOwnContentCoordinates() {
        UiDump desktop = withAlert(observation("fx", 0, true), 90);
        assertTrue(VisualImages.compare(desktop, withAlert(observation("web", 0, true), 94)).isEmpty());
        assertTrue(VisualImages.compare(desktop, withAlert(observation("web", 0, true), 95)).stream()
                .anyMatch(e -> e.startsWith("alert/deleteRule/delete")));
        assertTrue(VisualImages.compare(desktop, observation("web", 0, true)).stream()
                .anyMatch(e -> e.contains("button identities")));
    }

    /** Добавляет синтетическое сообщение только для отрицательной проверки измерений. */
    private static UiDump withAlert(UiDump source, double x) {
        var alert = new UiDump.Alert("a1", "deleteRule", "CONFIRMATION", "", "", 460, "", "", "", "", false,
                List.of(new UiDump.Button("delete", "", "", true, true, x)));
        return new UiDump(source.schema(), source.client(), source.scenario(), source.step(), source.frame(), source.menuBar(),
                source.toolbar(), source.summary(), source.table(), source.chart(), source.status(), source.contextMenus(),
                source.windows(), List.of(alert), source.popups(), source.screens(), source.chooserRequests(), source.classCensus(), source.counters());
    }

    /** Готовый content-local x формы не зависит от её положения на экране и не вычитает origin повторно. */
    @Test void comparesFormButtonsWithoutSubtractingOriginTwice() {
        UiDump desktop = withForm(observation("fx", 0, true), 1100, 359);
        assertTrue(VisualImages.compare(desktop, withForm(observation("web", 0, true), 320, 363)).isEmpty());
        assertTrue(VisualImages.compare(desktop, withForm(observation("web", 0, true), 320, 364)).stream()
                .anyMatch(e -> e.startsWith("window/w2/PLAN_SETTINGS//ok")));
    }

    /** Создаёт только unit-форму с независимыми координатами окна и кнопки внутри него. */
    private static UiDump withForm(UiDump source, double origin, double x) {
        var window = new UiDump.Window("w2", "PLAN_SETTINGS", "", "", "", "", true, "main", 0,
                new UiDump.Box(origin, 8, 560, 700), List.of(), List.of(), List.of(), List.of(), -1,
                List.of(), "", List.of(new UiDump.Button("ok", "", "", true, true, x)), "", "", false);
        return new UiDump(source.schema(), source.client(), source.scenario(), source.step(), source.frame(), source.menuBar(),
                source.toolbar(), source.summary(), source.table(), source.chart(), source.status(), source.contextMenus(),
                List.of(window), source.alerts(), source.popups(), source.screens(), source.chooserRequests(), source.classCensus(), source.counters());
    }

    /** Пустой PNG, неверный viewport, прозрачность и файл другого формата не считаются настоящим снимком. */
    @Test void rejectsInvalidViewportAndBlankImages() throws Exception {
        UiDump dump = observation("fx", 0, true);
        assertThrows(AssertionError.class, () -> VisualImages.png(png(new BufferedImage(1200, 700, BufferedImage.TYPE_INT_RGB)), dump));
        assertThrows(AssertionError.class, () -> VisualImages.png(png(new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB)), dump));
        assertThrows(AssertionError.class, () -> VisualImages.png(png(new BufferedImage(1200, 800, BufferedImage.TYPE_INT_ARGB)), dump));
        assertThrows(Exception.class, () -> VisualImages.png(new byte[]{1, 2}, dump));
        UiDump wrong = new UiDump(1, "fx", "unit", "table", new UiDump.Frame("", "os", null, 1200, 799, Map.of()),
                List.of(), null, null, null, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of());
        assertThrows(AssertionError.class, () -> VisualImages.png(png(fixture()), wrong));
    }

    /** DONE не маскирует неподдерживаемый Shot, пропуск, неправильный номер и дублирование шага. */
    @Test void validatesEveryStepIncludingShot() {
        var script = SelfTestScript.parse("unit", "sample\ndump table\nshot table\n");
        String log = "SELFTEST 1 OK sample\nSELFTEST 2 OK dump table\nSELFTEST 3 OK shot table\nSELFTEST DONE\n";
        assertTrue(VisualRun.logFailures(script, log).isEmpty());
        for (String mutation : List.of(log.replace("3 OK shot", "3 FAIL shot"),
                log.replace("SELFTEST 3 OK shot table\n", ""), log.replace("3 OK", "2 OK"),
                log + "SELFTEST DONE\n")) assertFalse(VisualRun.logFailures(script, mutation).isEmpty());
    }

    /** Отчёт не создаёт PNG вместо отсутствующего и экранирует диагностический текст. */
    @Test void missingScreenshotReportCannotBecomeFakePass() throws Exception {
        var checkpoint = VisualPlan.checkpoints("s02", "first").getFirst();
        var capture = new VisualRun.Capture("web", checkpoint, temporary.resolve("run"), null, null, List.of("missing shot"));
        Path report = VisualSuite.writeReport(temporary, List.of(capture), List.of("<script>bad</script>"), false);
        String html = Files.readString(report);
        assertTrue(html.contains("PNG отсутствует")); assertTrue(html.contains("&lt;script&gt;bad&lt;/script&gt;"));
        assertFalse(html.contains("<img"));
        assertThrows(AssertionError.class, () -> new VisualSuite.Result(List.of("missing shot"), report).requireSuccess());
    }

    /** Запуск использует копии module path: исходные файлы после копирования можно менять без удерживаемой JVM блокировки. */
    @Test void snapshotsResolvedJarPathsBeforeLaunch() throws Exception {
        Path original = temporary.resolve("client.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(original))) {
            zip.putNextEntry(new ZipEntry("module-info.class")); zip.write(new byte[]{1, 2, 3}); zip.closeEntry();
        }
        var target = new ClientTarget("fx", List.of(original), "module", "Main", List.of(), List.of("-Dtest=true"), List.of("--test-api"));
        var copied = VisualTargets.snapshot(target, temporary.resolve("snapshot"));
        assertNotEquals(target.modulePath(), copied.modulePath()); assertEquals(target.arguments(), copied.arguments());
        assertEquals(-1, Files.mismatch(original, copied.modulePath().getFirst()));
        Files.writeString(original, "replaced");
        assertThrows(Exception.class, () -> VisualTargets.snapshot(target, temporary.resolve("broken")));
        assertTrue(Files.size(copied.modulePath().getFirst()) > 3);
    }

    /** Создаёт исключительно синтетическую unit-фикстуру с объявленными реальными измерениями. */
    private static UiDump observation(String client, double cardShift, boolean measurements) {
        var regions = new LinkedHashMap<String, UiDump.Box>();
        regions.put("menuBar", new UiDump.Box(0, 0, 1200, 24)); regions.put("toolbar", new UiDump.Box(0, 24, 1200, 36));
        regions.put("summary", new UiDump.Box(0, 60, 1200, 120)); regions.put("center", new UiDump.Box(0, 180, 1200, 596));
        regions.put("status", new UiDump.Box(0, 776, 1200, 24));
        if (measurements) {
            regions.put("toolbar.baseline", new UiDump.Box(0, 48, 1200, 1)); regions.put("status.baseline", new UiDump.Box(0, 792, 1200, 1));
            int index = 0;
            for (String id : List.of("date", "day", "title", "category", "income", "expense", "balance", "marks"))
                regions.put("table.column." + id, new UiDump.Box(150 * index++, 180, 150, 28));
            regions.put("table.header", new UiDump.Box(0, 180, 1200, 28));
        }
        // Все id присутствуют; одна повторяемая unit-рамка сохраняет прежнюю геометрию отрицательных проб.
        var cards = SummaryBuilder.CARD_IDS.stream().map(id -> new UiDump.Card(id, "", "", "", "", "", "",
                new UiDump.Box(12 + cardShift, 72, 120, 80))).toList();
        return new UiDump(1, client, "unit", "table", new UiDump.Frame("", "", null, 1200, 800, regions), List.of(),
                new UiDump.Toolbar(false, List.of()), new UiDump.Summary(true, cards, ""),
                new UiDump.Table(List.of("date", "day", "title", "category", "income", "expense", "balance", "marks")
                        .stream().map(id -> UiText.get("table.column." + id)).toList(), 0, List.of(), "", "", List.of(), ""), null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of());
    }

    private static UiDump withRegions(UiDump original, Map<String, UiDump.Box> regions) {
        return new UiDump(1, original.client(), original.scenario(), original.step(), new UiDump.Frame("", "", null, 1200, 800, regions),
                original.menuBar(), original.toolbar(), original.summary(), original.table(), original.chart(), original.status(),
                original.contextMenus(), original.windows(), original.alerts(), original.popups(), original.screens(), original.chooserRequests(), Map.of(), Map.of());
    }

    private static BufferedImage fixture() {
        var image = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); graphics.setColor(new java.awt.Color(ColorToken.BG_WINDOW.argb())); graphics.fillRect(0, 0, 1200, 800);
        graphics.setColor(java.awt.Color.WHITE); graphics.fillRect(12, 72, 120, 80); graphics.dispose();
        for (int i = 0; i < 12; i++) image.setRGB(500 + i, 500, 0x101010 * i);
        return image;
    }

    private static byte[] png(BufferedImage image) throws Exception {
        var output = new ByteArrayOutputStream(); ImageIO.write(image, "png", output); return output.toByteArray();
    }
}
