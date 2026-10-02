package ru.cashprediction.parity.check.performance;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.jar.JarFile;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.core.ui.token.TokenCss;

/** HTTP-фикстура 200000 строк: настоящие ресурсы jar и ленивый API, без расчёта прогноза. */
final class VirtualTableFixture implements AutoCloseable {
    static final int TOTAL = 200_000;
    final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
    final Map<String, Object> provenance = new LinkedHashMap<>();
    private final Map<String, byte[]> resources = new HashMap<>();
    private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpServer server;
    private final Map<String, Object> model;
    final List<String> cellText;
    final List<String> columnIds;

    /** Читает неизменные jar и модель один раз; запрос не материализует все строки. */
    @SuppressWarnings("unchecked")
    VirtualTableFixture(Path webJar, Path coreJar, Path bootstrap) throws Exception {
        String before = hash(webJar);
        try (var jar = new JarFile(webJar.toFile())) {
            for (String name : List.of("render-table.js", "dom.js", "layout.css", "render-popups.js", "render-chart.js")) {
                byte[] bytes = jar.getInputStream(jar.getJarEntry("web/app/" + name)).readAllBytes();
                resources.put("/app/" + name, bytes);
                provenance.put(name, digest(bytes));
            }
        }
        if (!before.equals(hash(webJar))) throw new IllegalStateException("Web jar changed while reading");
        Path runtimeCore = Path.of(TokenCss.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!Files.isRegularFile(runtimeCore) || !hash(runtimeCore).equals(hash(coreJar)))
            throw new IllegalStateException("Run with the frozen core jar on classpath");
        provenance.put("webJar", Map.of("path", webJar.toString(), "sha256", before));
        provenance.put("coreJar", Map.of("path", coreJar.toString(), "sha256", hash(coreJar)));
        byte[] bootstrapBytes = Files.readAllBytes(bootstrap);
        provenance.put("bootstrap", Map.of("path", bootstrap.toString(), "sha256", digest(bootstrapBytes)));
        var tree = (Map<String, Object>) JsonParser.parse(new String(bootstrapBytes, StandardCharsets.UTF_8));
        model = new LinkedHashMap<>((Map<String, Object>) ((Map<?, ?>) tree.get("screen")).get("table"));
        model.put("rowCount", TOTAL); model.put("scrollToRowId", ""); model.put("selectedRowId", "");
        model.put("placeholder", null);
        cellText = ((List<Map<String, Object>>) model.get("columns")).stream().map(c -> (String) c.get("title")).toList();
        columnIds = ((List<Map<String, Object>>) model.get("columns")).stream().map(c -> (String) c.get("id")).toList();
        resources.put("/app/tokens.css", TokenCss.webCss().getBytes(StandardCharsets.UTF_8));
        resources.put("/harness.js", HARNESS.getBytes(StandardCharsets.UTF_8));
        provenance.put("harnessSha256", digest(resources.get("/harness.js")));
        provenance.put("tokensSha256", digest(resources.get("/app/tokens.css")));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor); server.createContext("/", this::serve); server.start();
    }

    /** Возвращает адрес изолированной страницы. */
    String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/"; }

    /** Отдаёт неизменные ES-модули и страницы не больше 300 строк для реального fetch. */
    @SuppressWarnings("unchecked")
    private void serve(HttpExchange exchange) throws java.io.IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            byte[] body;
            String type;
            if (path.equals("/rows")) {
                var query = (Map<String, Object>) JsonParser.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                int from = ((Number) query.get("from")).intValue(), count = ((Number) query.get("count")).intValue();
                long rev = ((Number) query.get("rev")).longValue();
                if (!"POST".equals(exchange.getRequestMethod()) || !"rows".equals(query.get("type"))
                        || rev <= 0 || from < 0 || from >= TOTAL || from % 300 != 0
                        || count != Math.min(300, TOTAL - from)) {
                    exchange.sendResponseHeaders(400, -1); return;
                }
                List<Map<String, Object>> rows = new ArrayList<>();
                for (int index = from; index < from + count; index++) rows.add(Map.of(
                        "rowId", "stress@" + index, "kind", "EVENT", "cells", cellText, "leadingSpan", 1,
                        "rowStyle", Map.of("text", "TEXT_PRIMARY"), "cellStyles", Map.of()));
                requests.add(Map.of("query", query, "returned", rows.size()));
                body = UiJson.write(Map.of("result", rows, "stale", false)).getBytes(StandardCharsets.UTF_8); type = "application/json";
            } else if (path.equals("/model")) {
                body = UiJson.write(model).getBytes(StandardCharsets.UTF_8); type = "application/json";
            } else if (path.equals("/")) {
                body = HTML.getBytes(StandardCharsets.UTF_8); type = "text/html";
            } else {
                body = resources.get(path);
                if (body == null) { exchange.sendResponseHeaders(404, -1); return; }
                type = path.endsWith(".css") ? "text/css" : "text/javascript";
            }
            exchange.getResponseHeaders().set("Content-Type", type + "; charset=UTF-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
        }
    }

    /** Останавливает только собственный сервер и исполнитель. */
    @Override public void close() { server.stop(0); executor.close(); }

    /** Хеширует реальный артефакт, не имя файла. */
    static String hash(Path path) throws Exception { return digest(Files.readAllBytes(path)); }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static final String HTML = """
            <!doctype html><meta charset="utf-8">
            <link rel="stylesheet" href="/app/tokens.css"><link rel="stylesheet" href="/app/layout.css">
            <main id="main"><section id="center"><div id="table"></div></section></main>
            <script type="module" src="/harness.js"></script>
            """;

    private static final String HARNESS = """
            /** @file Измерение настоящей таблицы на ленивом API; не модельный паритет и не прогноз. */
            import {Table} from '/app/render-table.js';
            import {Popups} from '/app/render-popups.js';
            const queries = [], errors = [];
            window.addEventListener('error', event => errors.push(event.message));
            window.addEventListener('unhandledrejection', event => errors.push(String(event.reason)));
            const model = await (await fetch('/model')).json();
            const app = {
              transport: {
                /** Передаёт настоящий запрос и сохраняет его результат, не подменяя страницу. */
                async query(query) {
                  const response = await fetch('/rows', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(query)});
                  if (!response.ok) throw new Error('Rows HTTP ' + response.status);
                  const value = await response.json(); queries.push({query, returned:value.result.length}); return value;
                }
              },
              /** Любое неожиданное действие не должно превращаться в тихий успех. */
              send() { throw new Error('Unexpected intent'); },
              /** Пересинхронизация означала бы устаревший ответ тестового API. */
              resync() { throw new Error('Unexpected stale rows'); }
            };
            // JavaFX: Tooltip → Swing: JToolTip → Web: Popups.tooltip.
            app.popups = new Popups(app);
            const table = new Table(app);
            const nativePaint = table.paint;
            const paintSamples = [];
            /** Измеряет настоящий paint, включая fetch и принудительный layout, без замены DOM. */
            table.paint = async function(...args) {
              const start = performance.now();
              await nativePaint.apply(this, args);
              this.canvas.getBoundingClientRect();
              paintSamples.push(performance.now() - start);
            };
            let revision = 100;
            /** Возвращает только наблюдения живого DOM и настоящего монотонного таймера. */
            function observe(elapsedMs, offset, queryStart, paintStart) {
              const nodes = [...table.canvas.querySelectorAll('.table-row')];
              const viewport = table.scroll.getBoundingClientRect();
              /** Учитывает скрывающие стили предков, а не только наличие узла в дереве. */
              function visible(node) {
                for (let parent=node; parent; parent=parent.parentElement) {
                  const style=getComputedStyle(parent);
                  if (style.display==='none' || style.visibility!=='visible' || Number(style.opacity)===0) return false;
                }
                return true;
              }
              /** Сохраняет размеры всех ячеек и площадь пересечения с реальной областью прокрутки. */
              function cellEvidence(cell) {
                const rect=cell.getBoundingClientRect();
                const area=Math.max(0,Math.min(rect.right,viewport.right)-Math.max(rect.left,viewport.left))
                  *Math.max(0,Math.min(rect.bottom,viewport.bottom)-Math.max(rect.top,viewport.top));
                return {id:cell.dataset.cpId,text:cell.textContent,width:rect.width,height:rect.height,
                  visible:visible(cell),visibleArea:area};
              }
              return {elapsedMs, offset, revision:table.model.revision, total:table.model.rowCount,
                scrollTop:table.scroll.scrollTop,
                width:innerWidth, documentWidth:document.documentElement.scrollWidth,
                bodyWidth:document.body.scrollWidth, viewportHeight:table.scroll.clientHeight,
                rows:nodes.length, indices:nodes.map(node=>Number(node.dataset.index)),
                rowIds:nodes.map(node=>node.dataset.cpId), columns:table.model.columns.length,
                rowCells:nodes.map(node=>[...node.querySelectorAll('.table-cell')].map(cellEvidence)),
                cells:table.canvas.querySelectorAll('.table-cell').length, pages:table.pages.size,
                queries:queries.slice(queryStart), paintMs:paintSamples.slice(paintStart), errors:[...errors]};
            }
            /** Удаляет настоящую строку после штатного render, не подменяя возвращаемое наблюдение. */
            async function missingRowMutation() {
              const sampleResult=await sample(0); table.canvas.firstElementChild.remove();
              return observe(sampleResult.elapsedMs,0,queries.length,paintSamples.length);
            }
            /** Портит текст настоящей ячейки после штатного render. */
            async function wrongTextMutation() {
              const sampleResult=await sample(0); table.canvas.querySelector('.table-cell').textContent='';
              return observe(sampleResult.elapsedMs,0,queries.length,paintSamples.length);
            }
            /** Каждая выборка холодная по данным: новая ревизия очищает cache штатным update. */
            async function sample(offset) {
              const queryStart = queries.length, paintStart = paintSamples.length, start = performance.now();
              await table.update({...model, revision:++revision});
              table.scroll.scrollTop = offset * 26;
              await table.paint(); table.canvas.getBoundingClientRect();
              return observe(performance.now()-start, offset, queryStart, paintStart);
            }
            /** Мутация требует все 200000 виджетов, но прекращается сразу после превышения DOM-границы. */
            async function eagerMutation() {
              await sample(0);
              const start = performance.now();
              const limit = Math.ceil(table.scroll.clientHeight/26)+6;
              table.canvas.replaceChildren(); let attempted = 0;
              for (let index=0; index<table.model.rowCount; index++) {
                const row = await table.row(index); table.canvas.append(table.widget(row,index)); attempted++;
                if (table.canvas.childElementCount > limit) break;
              }
              return {...observe(performance.now()-start,0,queries.length,paintSamples.length), plannedRows:model.rowCount, attempted};
            }
            /** Реальная задержка главного потока должна нарушить бюджет, таймер не подменяется. */
            async function slowMutation() {
              const measured = table.paint;
              table.paint = async function(...args) {
                const until=performance.now()+65; while(performance.now()<until) {}
                return measured.apply(this,args);
              };
              try { return await sample(0); } finally { table.paint=measured; }
            }
            window.stress = {sample,eagerMutation,slowMutation,missingRowMutation,wrongTextMutation};
            """;
}
