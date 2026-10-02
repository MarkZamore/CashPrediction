package ru.cashprediction.parity.check.performance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.parity.browser.CdpClient;
import ru.cashprediction.parity.browser.EdgeLauncher;
import static org.junit.jupiter.api.Assertions.*;

/** Исполняемый S3 stress-gate production ES-модулей в отдельном headless Chromium. */
class WebVirtualTablePerformanceTest {
    /** Проверяет 200000 строк и 400px на frozen jar, сохраняя даже красные фактические измерения. */
    @Test @Timeout(180)
    @SuppressWarnings("unchecked")
    void actualColdPagesAndNarrowViewport() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("parity.performance"), "Enable parity.performance=true");
        Path root = Path.of(System.getProperty("parity.reactor.root"));
        Path output = Files.createTempDirectory(Path.of(System.getProperty("parity.performance.output", System.getProperty("java.io.tmpdir"))), "web-virtual-200k-");
        Path webJar = Path.of(System.getProperty("parity.performance.webJar"));
        Path coreJar = Path.of(System.getProperty("parity.performance.coreJar"));
        Path browserPath = EdgeLauncher.findBrowser().orElseThrow(() -> new AssertionError("Chromium required when performance explicitly enabled"));
        List<Map<String, Object>> samples = new ArrayList<>();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scope", "production virtual-table with synthetic paged rows, not domain forecaster performance");
        report.put("budgetMs", 50); report.put("samples", samples);
        report.put("browser", browserPath.toString()); report.put("java", System.getProperty("java.version"));
        report.put("measuredAt", java.time.Instant.now().toString());
        report.put("hardware", Map.of("os", System.getProperty("os.name"), "arch", System.getProperty("os.arch"),
                "availableProcessors", Runtime.getRuntime().availableProcessors(),
                "processorIdentifier", Objects.toString(System.getenv("PROCESSOR_IDENTIFIER"), "unavailable")));
        report.put("measurement", "Runtime.evaluate performance.now around production update/paint plus forced layout; real HTTP fetch included; all cold revisions retained, no discarded warmup");
        report.put("narrowScope", "400px same-origin iframe with production table/CSS, matching BrowserFixtureProbe approach; not a full-app toolbar/dialog audit");
        try (var fixture = new VirtualTableFixture(webJar, coreJar, root.resolve("core/src/test/resources/ui-json/bootstrap.json"))) {
            report.put("provenance", fixture.provenance); report.put("httpRequests", fixture.requests);
            try (var browser = EdgeLauncher.startWithViewport(browserPath, output.resolve("profile"), 1200, 800, fixture.url(), Duration.ofSeconds(25));
                 var cdp = CdpClient.connectToFirstPage(browser.port(), Duration.ofSeconds(15))) {
                cdp.waitFor("!!window.stress", Duration.ofSeconds(15));
                report.put("userAgent", cdp.evaluate("navigator.userAgent"));
                for (int width : new int[]{1200, 400}) {
                    String access = "window";
                    if (width == 400) {
                        // Та же реальная 400px iframe-проба, что в BrowserFixtureProbe; не CSS zoom.
                        cdp.evaluate("(()=>{const frame=document.createElement('iframe');frame.id='narrow-probe';frame.style.cssText='position:fixed;left:0;top:0;width:400px;height:800px;border:0';frame.src='/';document.body.append(frame);})()");
                        cdp.waitFor("!!document.getElementById('narrow-probe').contentWindow.stress", Duration.ofSeconds(15));
                        access = "document.getElementById('narrow-probe').contentWindow";
                    }
                    for (int offset : new int[]{0, 60_000, 199_980, 0, 60_000, 199_980, 0, 60_000, 199_980}) {
                        var sample = (Map<String, Object>) cdp.evaluate(access + ".stress.sample(" + offset + ")", Duration.ofSeconds(15));
                        samples.add(sample);
                    }
                }
                for (String mutation : List.of("missingRowMutation", "wrongTextMutation")) {
                    var broken = (Map<String, Object>) cdp.evaluate("window.stress." + mutation + "()", Duration.ofSeconds(15));
                    report.put(mutation, broken);
                    assertThrows(AssertionError.class, () -> VirtualTablePerformanceGate.verifyContents(broken, fixture.columnIds, fixture.cellText));
                }
                Map<String, Object> eager = (Map<String, Object>) cdp.evaluate("window.stress.eagerMutation()", Duration.ofSeconds(15));
                report.put("eagerMutation", eager);
                Map<String, Object> slow = (Map<String, Object>) cdp.evaluate("window.stress.slowMutation()", Duration.ofSeconds(15));
                report.put("slowMutation", slow);
                assertThrows(AssertionError.class, () -> VirtualTablePerformanceGate.verifyDom(eager));
                assertEquals(VirtualTableFixture.TOTAL, ((Number) eager.get("plannedRows")).intValue());
                assertTrue(((Number) eager.get("attempted")).intValue() < 100, "eager mutation must stop before unbounded allocation");
                VirtualTablePerformanceGate.verifyDom(slow);
                VirtualTablePerformanceGate.verifyContents(slow, fixture.columnIds, fixture.cellText);
                assertTrue(((Number) slow.get("elapsedMs")).doubleValue() >= 50, "real injected delay must be observed");
                assertThrows(AssertionError.class, () -> VirtualTablePerformanceGate.verify(slow, 1200));
                report.put("mutationGuards", "PASS actual missing-row, wrong-text, bounded eager and slow-render rejection");
            }
            // Все холодные выборки обязательны; медленная первая выборка не отбрасывается как warmup.
            assertEquals(18, samples.size());
            report.put("coldRepeatsPerViewport", 3);
            for (int i = 0; i < samples.size(); i++) {
                Map<String, Object> sample = samples.get(i);
                VirtualTablePerformanceGate.verify(sample, i < 9 ? 1200 : 400);
                VirtualTablePerformanceGate.verifyContents(sample, fixture.columnIds, fixture.cellText);
                List<Map<String, Object>> queries = (List<Map<String, Object>>) sample.get("queries");
                assertFalse(queries.isEmpty(), "each measured revision must perform real cold fetch");
                assertTrue(queries.size() <= 4, "bounded fetched pages per sample");
                for (var entry : queries) {
                    var query = (Map<String, Object>) entry.get("query");
                    assertEquals(((Number) sample.get("revision")).longValue(), ((Number) query.get("rev")).longValue());
                    assertTrue(fixture.requests.stream().anyMatch(server -> server.get("query").equals(query)
                            && ((Number) server.get("returned")).intValue() == ((Number) entry.get("returned")).intValue()),
                            "request must reach real HTTP server");
                    int from = ((Number) query.get("from")).intValue();
                    assertEquals(0, from % 300);
                    assertEquals(Math.min(300, VirtualTableFixture.TOTAL - from), ((Number) query.get("count")).intValue());
                    assertEquals(query.get("count"), entry.get("returned"));
                }
                var indices = (List<Number>) sample.get("indices");
                assertTrue(indices.stream().anyMatch(n -> Math.abs(n.intValue() - ((Number) sample.get("offset")).intValue()) <= 3));
                if (i % 3 == 2) assertTrue(indices.stream().anyMatch(n -> n.intValue() == 199_999));
            }
            report.put("result", "PASS");
        } catch (Exception | AssertionError failure) {
            report.put("result", "FAIL"); report.put("failure", failure.toString());
            throw new AssertionError("Virtual table performance; evidence=" + output, failure);
        } finally {
            Files.writeString(output.resolve("evidence.json"), UiJson.write(report));
            System.out.println("Virtual table performance evidence: " + output.resolve("evidence.json"));
        }
    }

    /** Даже быстрые фиктивные 200000 DOM-строк отвергаются независимым структурным gate. */
    @Test void eagerCountCannotPassEvenWithZeroReportedTime() {
        var evidence = baseline(); evidence.put("rows", 200_000); evidence.put("cells", 1_600_000);
        assertThrows(AssertionError.class, () -> VirtualTablePerformanceGate.verify(evidence, 400));
    }

    /** Пограничные 50ms, NaN и бесконечность не дают ложный успех. */
    @Test void slowOrInvalidMeasurementCannotPass() {
        for (double elapsed : new double[]{50, 65, Double.NaN, Double.POSITIVE_INFINITY}) {
            var evidence = baseline(); evidence.put("elapsedMs", elapsed);
            assertThrows(AssertionError.class, () -> VirtualTablePerformanceGate.verify(evidence, 400));
        }
    }

    /** Допустимое наблюдение проходит gate, а горизонтальное переполнение страницы не проходит. */
    @Test void boundedObservationPassesButHorizontalPageScrollFails() {
        var evidence = baseline();
        VirtualTablePerformanceGate.verify(evidence, 400);
        evidence.put("documentWidth", 401);
        assertThrows(AssertionError.class, () -> VirtualTablePerformanceGate.verify(evidence, 400));
    }

    /** Отбрасывает неполную область, перестановку строк и неверную идентичность при быстром render. */
    @Test void missingOrWrongRowCannotPass() {
        var missing = baseline(); missing.put("rows", 35); missing.put("cells", 280);
        assertThrows(AssertionError.class, () -> VirtualTablePerformanceGate.verify(missing, 400));
        var wrong = baseline();
        wrong.put("rowIds", java.util.stream.IntStream.range(1, 37).mapToObj(i -> "stress@" + i).toList());
        assertThrows(AssertionError.class, () -> VirtualTablePerformanceGate.verify(wrong, 400));
        var wrongIndices = baseline(); wrongIndices.put("indices", java.util.stream.IntStream.range(1, 37).boxed().toList());
        assertThrows(AssertionError.class, () -> VirtualTablePerformanceGate.verify(wrongIndices, 400));
    }

    /** Проверяет отрицательные случаи текста, скрытых ячеек, нулевой геометрии и площади. */
    @Test void wrongTextOrInvisibleCellsCannotPass() {
        var ids = java.util.stream.IntStream.range(0, 8).mapToObj(i -> "column" + i).toList();
        var texts = java.util.stream.IntStream.range(0, 8).mapToObj(i -> "text" + i).toList();
        var evidence = baseline();
        var rows = new ArrayList<List<Map<String, Object>>>();
        for (int row = 0; row < 36; row++) {
            var cells = new ArrayList<Map<String, Object>>();
            for (int col = 0; col < 8; col++) cells.add(new HashMap<>(Map.of("id", ids.get(col), "text", texts.get(col),
                    "width", 40, "height", 26, "visible", true, "visibleArea", 1040)));
            rows.add(cells);
        }
        evidence.put("rowCells", rows);
        VirtualTablePerformanceGate.verifyContents(evidence, ids, texts);
        var cell = rows.getFirst().getFirst();
        for (var mutation : Map.of("text", "", "visible", false, "width", 0, "height", 0).entrySet()) {
            Object previous = cell.put(mutation.getKey(), mutation.getValue());
            assertThrows(AssertionError.class, () -> VirtualTablePerformanceGate.verifyContents(evidence, ids, texts));
            cell.put(mutation.getKey(), previous);
        }
        rows.forEach(cells -> cells.forEach(c -> c.put("visibleArea", 0)));
        assertThrows(AssertionError.class, () -> VirtualTablePerformanceGate.verifyContents(evidence, ids, texts));
    }

    /** Данные только unit-gate; не подаются реальному браузеру и не считаются измерением S3. */
    private static Map<String, Object> baseline() {
        var evidence = new HashMap<String, Object>(Map.of("total", 200_000, "rows", 36, "columns", 8, "cells", 288,
                "elapsedMs", 0, "viewportHeight", 772, "width", 400, "documentWidth", 400, "bodyWidth", 400,
                "pages", 1));
        evidence.put("errors", List.of());
        evidence.put("scrollTop", 0);
        evidence.put("indices", java.util.stream.IntStream.range(0, 36).boxed().toList());
        evidence.put("rowIds", java.util.stream.IntStream.range(0, 36).mapToObj(i -> "stress@" + i).toList());
        return evidence;
    }
}
