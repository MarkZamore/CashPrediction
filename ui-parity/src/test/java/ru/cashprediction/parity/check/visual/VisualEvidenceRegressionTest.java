package ru.cashprediction.parity.check.visual;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.parity.pipeline.DumpTrees;
import static org.junit.jupiter.api.Assertions.*;

/** Перепроверяет ровно две сохранённые actual-пары без запуска клиентов и изменения исходных файлов. */
@EnabledIfSystemProperty(named = "parity.visual.evidence.directory", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class VisualEvidenceRegressionTest {
    private final List<Evidence> evidence = new ArrayList<>();

    /** Попарный успех не игнорирует реальный table.header при неизменённых колонках. */
    @Test void actualHeaderPairwiseMismatchAndAbsenceNeverPass() {
        for (var actual : evidence) {
            var regions = new LinkedHashMap<>(actual.dump().frame().regions());
            var header = regions.get("table.header");
            regions.put("table.header", new UiDump.Box(header.x(), header.y() + 5, header.width(), header.height()));
            assertTrue(VisualImages.compare(actual.dump(), withRegions(actual.dump(), regions)).stream()
                    .anyMatch(f -> f.startsWith("region/table.header")));
            regions.remove("table.header");
            assertTrue(VisualImages.compare(actual.dump(), withRegions(actual.dump(), regions)).stream()
                    .anyMatch(f -> f.startsWith("region/table.header")));
        }
    }

    /** Дубликат actual-карточки с теми же пикселями и рамкой не теряется при индексации. */
    @Test void duplicateActualCardNeverPassesSingleOrPairwiseCheck() {
        for (var actual : evidence) {
            var d = actual.dump();
            var cards = new ArrayList<>(d.summary().cards()); cards.add(cards.getFirst());
            var changed = new UiDump(d.schema(), d.client(), d.scenario(), d.step(), d.frame(), d.menuBar(), d.toolbar(),
                    new UiDump.Summary(d.summary().visible(), cards, d.summary().unavailableText()), d.table(), d.chart(),
                    d.status(), d.contextMenus(), d.windows(), d.alerts(), d.popups(), d.screens(), d.chooserRequests(),
                    d.classCensus(), d.counters());
            assertTrue(VisualImages.check(changed, actual.png()).stream().anyMatch(f -> f.contains("Duplicate visual identity")));
            assertTrue(VisualImages.compare(d, changed).stream().anyMatch(f -> f.contains("Duplicate visual identity")));
        }
    }

    /** Удаление одновременно текста и рамки колонки не уменьшает обязательный состав таблицы. */
    @Test void missingColumnInBothTextsAndMeasurementsNeverPasses() {
        for (var actual : evidence) {
            var d = actual.dump();
            var t = d.table();
            assertEquals(8, t.columns().size());
            var regions = new LinkedHashMap<>(d.frame().regions());
            assertNotNull(regions.remove("table.column.date"));
            var reduced = withRegions(d, regions);
            var changed = new UiDump(d.schema(), d.client(), d.scenario(), d.step(), reduced.frame(), d.menuBar(), d.toolbar(),
                    d.summary(), new UiDump.Table(t.columns().subList(1, t.columns().size()), t.rowCount(), t.rows(),
                    t.rowsDigest(), t.placeholder(), t.placeholderButtons(), t.selectedRowId()), d.chart(), d.status(),
                    d.contextMenus(), d.windows(), d.alerts(), d.popups(), d.screens(), d.chooserRequests(), d.classCensus(), d.counters());
            assertTrue(VisualImages.check(changed, actual.png()).stream().anyMatch(f -> f.startsWith("measurement/table.columns")));
        }
    }

    /** Одинаково пропущенная карточка у клиентов не может считаться полной наблюдаемой сводкой. */
    @Test void missingOneRequiredActualCardNeverPasses() {
        for (var actual : evidence) {
            var d = actual.dump();
            assertEquals(9, d.summary().cards().size());
            var cards = new ArrayList<>(d.summary().cards());
            cards.removeFirst();
            var changed = new UiDump(d.schema(), d.client(), d.scenario(), d.step(), d.frame(), d.menuBar(), d.toolbar(),
                    new UiDump.Summary(d.summary().visible(), cards, d.summary().unavailableText()), d.table(), d.chart(),
                    d.status(), d.contextMenus(), d.windows(), d.alerts(), d.popups(), d.screens(), d.chooserRequests(),
                    d.classCensus(), d.counters());
            assertTrue(VisualImages.check(changed, actual.png()).stream().anyMatch(f -> f.startsWith("measurement/summary.cards")));
        }
    }

    /** Хранит исходные свидетельства только для ограниченных отрицательных проб в памяти. */
    private record Evidence(UiDump dump, byte[] png) { }

    /** Требует две полные реальные пары и успешную исходную проверку, иначе регрессия падает. */
    @BeforeAll void readActualEvidence() throws Exception {
        Path root = Path.of(System.getProperty("parity.visual.evidence.directory"));
        List<Path> images;
        try (var paths = Files.walk(root, 6)) {
            images = paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".png"))
                    .sorted().limit(3).toList();
        }
        assertEquals(2, images.size(), "Supply exactly two actual PNG/JSON pairs");
        for (Path image : images) {
            String name = image.getFileName().toString();
            var dump = DumpTrees.read(Files.readString(image.resolveSibling(name.substring(0, name.length() - 4) + ".json")));
            assertEquals("web", dump.client());
            assertTrue(Set.of("settings", "delete-rule").contains(dump.step()));
            byte[] png = Files.readAllBytes(image);
            assertEquals(List.of(), VisualImages.check(dump, png), image.toString());
            evidence.add(new Evidence(dump, png));
        }
        assertEquals(Set.of("settings", "delete-rule"),
                new HashSet<>(evidence.stream().map(e -> e.dump().step()).toList()));
    }

    /** Каждый обязательный регион проверяется даже без попарного сравнения и при неизменённом реальном PNG. */
    @Test void missingRegionNeverPassesWithActualScreenshot() {
        for (var actual : evidence) for (String id : List.of("menuBar", "toolbar", "summary", "center", "status",
                "toolbar.baseline", "status.baseline", "table.header")) {
            var regions = new LinkedHashMap<>(actual.dump().frame().regions());
            assertNotNull(regions.remove(id));
            String prefix = id.contains("baseline") ? "measurement/" + id
                    : id.equals("table.header") ? "measurement/table.columns" : "region/" + id;
            assertTrue(VisualImages.check(withRegions(actual.dump(), regions), actual.png()).stream()
                    .anyMatch(f -> f.startsWith(prefix)), id);
        }
    }

    /** Отсутствие снимка, одноцветная копия и один полупрозрачный крайний пиксель не подтверждают отрисовку. */
    @Test void missingBlankAndSingleTransparentPixelNeverPass() throws Exception {
        for (var actual : evidence) {
            assertTrue(VisualImages.check(actual.dump(), null).stream().anyMatch(f -> f.startsWith("PNG/viewport")));
            BufferedImage decoded = VisualImages.png(actual.png(), actual.dump());
            var changed = new BufferedImage(decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_INT_ARGB);
            changed.setRGB(0, 0, decoded.getWidth(), decoded.getHeight(),
                    decoded.getRGB(0, 0, decoded.getWidth(), decoded.getHeight(), null, 0, decoded.getWidth()), 0, decoded.getWidth());
            int x = decoded.getWidth() - 1, y = decoded.getHeight() - 1;
            changed.setRGB(x, y, (changed.getRGB(x, y) & 0xffffff) | 0x7f000000);
            assertTrue(VisualImages.check(actual.dump(), encode(changed)).stream()
                    .anyMatch(f -> f.startsWith("PNG/viewport: Transparent screenshot pixel: " + x + "," + y)));
            var blank = new BufferedImage(decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_INT_RGB);
            assertTrue(VisualImages.check(actual.dump(), encode(blank)).stream()
                    .anyMatch(f -> f.startsWith("PNG/viewport: Blank/solid")));
        }
    }

    /** Реальная карточка не может выходить из измеренной сводки, даже если её пиксель и рамка PNG правильны. */
    @Test void cardOutsideMeasuredSummaryNeverPasses() {
        for (var actual : evidence) {
            var regions = new LinkedHashMap<>(actual.dump().frame().regions());
            var summary = regions.get("summary");
            double bottom = actual.dump().summary().cards().stream()
                    .mapToDouble(c -> c.bounds().y() + c.bounds().height()).max().orElseThrow();
            // Отрицательная мутация одной измеренной границы, не модельная геометрия клиента.
            regions.put("summary", new UiDump.Box(summary.x(), summary.y(), summary.width(), bottom - summary.y() - 5));
            assertTrue(VisualImages.check(withRegions(actual.dump(), regions), actual.png()).stream()
                    .anyMatch(f -> f.startsWith("card/") && f.contains("Measurement outside its region")));
        }
    }

    /** Имя modal-точки не позволяет скрыть все измерения таблицы, когда настоящий переключатель выбран. */
    @Test void missingAllColumnsOnVisibleActualTableNeverPasses() {
        for (var actual : evidence) {
            assertTrue(actual.dump().toolbar().items().stream().anyMatch(i -> i.id().equals("tb.table") && i.selected()));
            var regions = new LinkedHashMap<>(actual.dump().frame().regions());
            assertTrue(regions.keySet().removeIf(id -> id.startsWith("table.column.")));
            regions.remove("table.header");
            assertTrue(VisualImages.check(withRegions(actual.dump(), regions), actual.png()).stream()
                    .anyMatch(f -> f.startsWith("measurement/table.columns")));
        }
    }

    /** Положение настоящей baseline сравнивается с пределом 3 px независимо от допустимой высоты линии. */
    @Test void baselineToleranceIsThreePixelsOnActualMeasurements() {
        for (var actual : evidence) for (int shift : List.of(3, 4)) {
            var regions = new LinkedHashMap<>(actual.dump().frame().regions());
            var line = regions.get("toolbar.baseline");
            regions.put("toolbar.baseline", new UiDump.Box(line.x(), line.y() + shift, line.width(), line.height()));
            var failures = VisualImages.compare(actual.dump(), withRegions(actual.dump(), regions));
            if (shift == 3) assertTrue(failures.isEmpty(), failures.toString());
            else assertTrue(failures.stream().anyMatch(f -> f.startsWith("toolbar.baseline")));
        }
    }

    /** Меняет только карту измерений отрицательной копии, сохраняя остальные actual-поля. */
    private static UiDump withRegions(UiDump d, Map<String, UiDump.Box> regions) {
        var f = d.frame();
        return new UiDump(d.schema(), d.client(), d.scenario(), d.step(),
                new UiDump.Frame(f.title(), f.titleBar(), f.minSize(), f.contentWidth(), f.contentHeight(), regions),
                d.menuBar(), d.toolbar(), d.summary(), d.table(), d.chart(), d.status(), d.contextMenus(), d.windows(),
                d.alerts(), d.popups(), d.screens(), d.chooserRequests(), d.classCensus(), d.counters());
    }

    /** Кодирует только отрицательную копию в памяти; actual-файл не перезаписывается. */
    private static byte[] encode(BufferedImage image) throws Exception {
        var bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", bytes));
        return bytes.toByteArray();
    }
}
