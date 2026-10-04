package ru.cashprediction.web.js;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import ru.cashprediction.core.json.JsonWriter;

/** Исследует настоящий старт jar в отдельном home с реестром в памяти, не исполняя шаги сборщика. */
public final class BrowserStartupProbe {
    private BrowserStartupProbe() { }

    /** Копирует заданные jar и сохраняет фактическую очередь, журнал HTTP и изображение Edge. */
    public static void main(String[] args) throws Exception {
        if (args[0].equals("--compare-collected")) {
            compareCollected(Path.of(args[1]), Path.of(args[2]), args.length > 3
                    ? java.util.Arrays.asList(args).subList(3, args.length) : List.of("s07-forms-misc", "s14-save-conflicts"));
            return;
        }
        if (args[0].equals("--verify-measurements")) {
            Path folder = Path.of(args[1]);
            for (var check : java.util.Map.of("settings", "final-s05-forms-plan/out/modal-step-5.geometry.json",
                    "goal", "final-s05-forms-plan/out/modal-step-13.geometry.json",
                    "income-create", "final-s06-forms-ops/out/modal-step-5.geometry.json",
                    "adjustment", "final-s06-forms-ops/out/modal-step-26.geometry.json").entrySet()) {
                verifyNativeBodySize(ru.cashprediction.core.json.JsonParser.parse(Files.readString(folder.resolve(check.getValue()))), check.getKey());
            }
            System.out.println("ACTUAL MODAL SIZES OK 4"); return;
        }
        Path output = Path.of(args[2]).toAbsolutePath(); Files.createDirectories(output);
        Path web = Files.copy(Path.of(args[0]), output.resolve("cashprediction-web-1.0.0.jar"));
        Path core = Files.copy(Path.of(args[1]), output.resolve("cashprediction-core-1.0.0.jar"));
        Path home = output.resolve("home"); Files.createDirectories(home);
        var process = new ProcessBuilder(List.of(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dcashprediction.web.port=0",
                "--module-path", web + ";" + core, "--module", "ru.cashprediction.web/ru.cashprediction.web.WebMain",
                "--home", home.toString(), "--registry", "memory", "--today", "2026-09-13",
                "--selftest", args.length > 4 ? args[4] : "s02-sample-table", "--selftest-out", output.resolve("out").toString(),
                "--no-browser", "--no-window", "--test-api"))
                .redirectError(output.resolve("stderr.log").toFile()).start();
        var url = new CompletableFuture<String>();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var input = process.inputReader(StandardCharsets.UTF_8);
                 var log = Files.newBufferedWriter(output.resolve("stdout.log"), StandardCharsets.UTF_8)) {
                String line;
                while ((line = input.readLine()) != null) {
                    log.write(line); log.newLine(); log.flush();
                    if (line.startsWith("PARITY_URL ")) url.complete(line.substring(11));
                }
            } catch (Exception error) { url.completeExceptionally(error); }
        });
        try (var fixture = prepareExternalChange(args.length > 4 ? args[4] : "s02-sample-table", home, output.resolve("out"));
             var browser = BrowserFixtureProbe.BrowserBridge.start(output.resolve("edge-" + UUID.randomUUID()), 1200, url.get(15, TimeUnit.SECONDS));
             var cdp = browser.connect()) {
            cdp.waitFor("!!window.cpParityTestApi", Duration.ofSeconds(20));
            if (args.length > 3 && args[3].equals("collect")) {
                collect(cdp, java.net.URI.create(url.get()), output.resolve("out/selftest.log"));
                if (fixture != null) fixture.getClass().getMethod("requireComplete").invoke(fixture);
                System.out.println("ACTUAL SCENARIO " + output);
                return;
            }
            Thread.sleep(2000);
            cdp.evaluate("(async()=>{const {Application}=await import('/app/main.js');const original=Application.prototype.send;Application.prototype.send=function(intent){window.startupApp=this;return original.call(this,intent)};window.dispatchEvent(new Event('resize'));await new Promise(resolve=>setTimeout(resolve,250));})()");
            Object diagnostics = cdp.evaluate("(async()=>{ const token=sessionStorage.getItem('cashprediction.token'); const events=await fetch('/api/ui/events?tab='+crypto.randomUUID()+'&after=0',{headers:{'X-Token':token}}).then(response=>response.json()); return {step:window.cpParityTestApi.takeStep({}),events,resources:performance.getEntriesByType('resource').filter(entry=>entry.name.includes('/api/')).map(entry=>({name:entry.name,start:entry.startTime,duration:entry.duration})),screen:document.getElementById('screens').innerText}; })()");
            Files.writeString(output.resolve("startup.json"), JsonWriter.write(diagnostics), StandardCharsets.UTF_8);
            Files.writeString(output.resolve("state.json"), JsonWriter.write(cdp.evaluate("({seq:startupApp.transport.seq,pending:startupApp.transport.pending,resyncing:!!startupApp.resyncing,testApi:startupApp.testApi,driver:!!startupApp.testDriver,stopped:startupApp.transport.stopped,generation:startupApp.transport.generation,error:String(startupApp.lastTransportError),windows:[...startupApp.windows.keys()]})")), StandardCharsets.UTF_8);
            Files.write(output.resolve("startup.png"), cdp.captureScreenshot());
            System.out.println("ACTUAL STARTUP DIAGNOSTICS " + output);
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS); reader.join(1000);
        }
    }

    /** Подключает штатную внешнюю фикстуру s14, не переопределяя signal и алгоритм изменения файла. */
    private static AutoCloseable prepareExternalChange(String scenario, Path home, Path output) throws Exception {
        if (!scenario.equals("s14-save-conflicts")) return null;
        Class<?> requestType = Class.forName("ru.cashprediction.parity.launch.LaunchRequest");
        Object request = requestType.getConstructor(String.class, String.class, Path.class, String.class,
                java.time.LocalDate.class, String.class, Path.class, String.class, List.class, List.class)
                .newInstance("web", scenario, home, "ru/cashprediction/selftest/" + UUID.randomUUID(),
                        java.time.LocalDate.of(2026, 9, 13), scenario, output, "core", List.of(), List.of());
        return (AutoCloseable) Class.forName("ru.cashprediction.parity.pipeline.ScenarioFixtures")
                .getMethod("prepare", requestType, Duration.class).invoke(null, request, Duration.ofSeconds(60));
    }

    /** Сравнивает сохранённые реальные шаги штатным конвейером, не запуская клиентов повторно. */
    private static void compareCollected(Path root, Path evidence, List<String> scenarios) throws Exception {
        Class<?> pipeline = Class.forName("ru.cashprediction.parity.pipeline.ParityPipeline");
        Class<?> collector = Class.forName("ru.cashprediction.parity.pipeline.ParityPipeline$Collector");
        Class<?> collection = Class.forName("ru.cashprediction.parity.pipeline.ParityPipeline$Collection");
        Object saved = java.lang.reflect.Proxy.newProxyInstance(collector.getClassLoader(), new Class<?>[] {collector},
                (proxy, method, arguments) -> {
                    String scenario = (String) arguments[1];
                    Path completed = evidence.resolve("final-" + scenario);
                    Path run = Files.isDirectory(completed) ? completed : evidence.resolve(scenario);
                    return collection.getConstructor(Path.class, Path.class, String.class)
                            .newInstance(run.resolve("out").resolve(scenario), run.resolve("home/CashMemory"), "");
                });
        var allowed = ru.cashprediction.core.ui.dump.AllowedDiffs.parse(Files.readString(root.resolve("core/src/test/resources/ui-golden/allowed-diffs.json")));
        Object result = pipeline.getMethod("run", Path.class, Path.class, List.class, List.class,
                ru.cashprediction.core.ui.dump.AllowedDiffs.class, boolean.class, collector)
                .invoke(null, root.resolve("core/src/test/resources/ui-golden"), evidence.resolve("comparison"),
                        List.of("web"), scenarios, allowed, false, saved);
        Object failures = result.getClass().getMethod("failures").invoke(result);
        System.out.println("SAVED ACTUAL COMPARISON " + failures);
        System.out.println("REPORT " + result.getClass().getMethod("report").invoke(result));
    }

    /** Использует штатный единственный сборщик через отражение без зависимости Maven-модуля web. */
    private static void collect(BrowserFixtureProbe.CdpBridge cdp, java.net.URI url, Path journal) throws Exception {
        Class<?> apiType = Class.forName("ru.cashprediction.parity.driver.CdpTestApi");
        Object api = apiType.getConstructor(Class.forName("ru.cashprediction.parity.browser.CdpClient")).newInstance(cdp.delegate());
        Class<?> driverType = Class.forName("ru.cashprediction.parity.driver.UiTestDriver");
        Object driver = driverType.getConstructor(ru.cashprediction.core.app.ClientKind.class,
                Class.forName("ru.cashprediction.parity.driver.UiTestDriver$TestApi"))
                .newInstance(ru.cashprediction.core.app.ClientKind.WEB, api);
        Class<?> bridgeType = Class.forName("ru.cashprediction.parity.driver.TestApiBridge");
        Object sender = bridgeType.getMethod("http", java.net.URI.class).invoke(null, url);
        Object bridge = bridgeType.getConstructor(driverType, Class.forName("ru.cashprediction.parity.driver.TestApiBridge$Sender"))
                .newInstance(driver, sender);
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (System.nanoTime() < deadline) {
            String text = Files.exists(journal) ? Files.readString(journal, StandardCharsets.UTF_8) : "";
            if (text.contains(" FAIL ")) throw new AssertionError(text);
            if (text.contains("SELFTEST DONE")) return;
            Object step = apiType.getMethod("takeStep").invoke(api);
            if (step != null) {
                var pending = (java.util.Map<?, ?>) step;
                String action = String.valueOf(pending.get("command"));
                String anchorRow = action.startsWith("dblclick ") ? action.split("\\s+")[1] : null;
                if (anchorRow != null) Files.writeString(journal.getParent().resolve("anchor-step-" + pending.get("n") + "-before.json"), JsonWriter.write(tableAnchor(cdp, anchorRow)), StandardCharsets.UTF_8);
                if (action.startsWith("dump ")) {
                    // Сам dumper закрывает меню после чтения; якорь измеряется до этого штатного закрытия.
                    Object menu = menuGeometry(cdp);
                    Files.writeString(journal.getParent().resolve("menu-step-" + pending.get("n") + "-before.geometry.json"), JsonWriter.write(menu), StandardCharsets.UTF_8);
                    if (!((java.util.List<?>) ((java.util.Map<?, ?>) menu).get("panels")).isEmpty())
                        Files.write(journal.getParent().resolve("menu-step-" + pending.get("n") + "-before.png"), cdp.captureScreenshot());
                }
                bridgeType.getMethod("accept", java.util.Map.class).invoke(bridge, step);
                if (anchorRow != null) Files.writeString(journal.getParent().resolve("anchor-step-" + pending.get("n") + "-after.json"), JsonWriter.write(tableAnchor(cdp, anchorRow)), StandardCharsets.UTF_8);
                var command = (java.util.Map<?, ?>) step;
                if (String.valueOf(command.get("command")).startsWith("dump ")) {
                    String name = "modal-step-" + command.get("n");
                    Object measured = cdp.evaluate("(() => {const box=node=>node.getBoundingClientRect().toJSON(); return [...document.querySelectorAll('dialog[open]:not(#screens),.quick-edit')].map(root=>({id:root.dataset.cpId,raw:box(root),main:box(document.getElementById('main')),parts:[...root.children].filter(node=>!node.classList.contains('window-title')&&!node.hidden&&node.getClientRects().length).map(box),grid:root.querySelector('.form-grid')?box(root.querySelector('.form-grid')):null,side:root.querySelector('.side-column')?box(root.querySelector('.side-column')):null,fields:[...root.querySelectorAll('.field-wrap')].filter(node=>!node.closest('[hidden]')).map(node=>({id:node.dataset.cpId,bounds:box(node),controls:[...node.querySelectorAll('input,select,textarea')].map(box)}))}));})()");
                    Files.writeString(journal.getParent().resolve(name + ".geometry.json"), JsonWriter.write(measured), StandardCharsets.UTF_8);
                    verifyRenderGeometry(cdp);
                    Object spacing = cdp.evaluate("(() => {const metrics=node=>{if(!node)return null;const css=getComputedStyle(node);const range=document.createRange();range.selectNodeContents(node);return {className:node.className,bounds:node.getBoundingClientRect().toJSON(),height:node.getBoundingClientRect().height,hidden:node.hidden,fontFamily:css.fontFamily,fontSize:css.fontSize,fontWeight:css.fontWeight,lineHeight:css.lineHeight,paddingTop:css.paddingTop,paddingBottom:css.paddingBottom,paddingLeft:css.paddingLeft,paddingRight:css.paddingRight,borderTop:css.borderTopWidth,borderBottom:css.borderBottomWidth,marginTop:css.marginTop,rowGap:css.rowGap,columnGap:css.columnGap,textBoxes:[...range.getClientRects()].map(rect=>rect.toJSON())};};return [...document.querySelectorAll('dialog[open]:not(#screens),.quick-edit')].map(root=>({id:root.dataset.cpId,parts:[...root.querySelectorAll(':scope > .window-header,:scope > .form-body,:scope > .window-buttons')].map(metrics),grid:metrics(root.querySelector('.form-grid')),problem:metrics(root.querySelector('.problem')),hints:[...root.querySelectorAll('.hint')].map(metrics),labels:[...root.querySelectorAll('.field-label')].map(metrics),buttons:[...root.querySelectorAll('.window-buttons button:not([hidden])')].map(metrics),sections:[...root.querySelectorAll('.section')].map(metrics),radios:[...root.querySelectorAll('.radio-group')].map(metrics)}));})()");
                    Files.writeString(journal.getParent().resolve(name + ".spacing.json"), JsonWriter.write(spacing), StandardCharsets.UTF_8);
                    Files.writeString(journal.getParent().resolve("menu-step-" + command.get("n") + ".geometry.json"), JsonWriter.write(menuGeometry(cdp)), StandardCharsets.UTF_8);
                    Files.write(journal.getParent().resolve(name + ".png"), cdp.captureScreenshot());
                    // Диагностика сохраняет все реальные размеры; сравнение с текущими клиентами остаётся в parity.
                    // Старые контрольные размеры не являются нормативными высотами FormSpec.
                    if (!Boolean.getBoolean("web.probe.layoutDiagnostics"))
                        verifyNativeBodySize(measured, String.valueOf(command.get("command")).substring(5).strip());
                }
            }
            else Thread.sleep(25);
        }
        throw new AssertionError("Actual scenario timeout: " + journal);
    }

    /** Измеряет настоящие строки раскрытого меню, separators и виджет-якорь видимой подсказки. */
    private static Object menuGeometry(BrowserFixtureProbe.CdpBridge cdp) throws Exception {
        return cdp.evaluate("""
                (()=>{const measure=node=>{if(!node)return null;const css=getComputedStyle(node);return {id:node.dataset.cpId,kind:node.dataset.kind,
                  tag:node.tagName,role:node.getAttribute('role'),box:node.getBoundingClientRect().toJSON(),display:css.display,font:css.font,
                  lineHeight:css.lineHeight,minHeight:css.minHeight,paddingTop:css.paddingTop,paddingBottom:css.paddingBottom,
                  paddingLeft:css.paddingLeft,paddingRight:css.paddingRight,marginTop:css.marginTop,marginBottom:css.marginBottom,
                  borderTop:css.borderTopWidth,borderBottom:css.borderBottomWidth,gap:css.gap};};
                const tooltip=document.querySelector('.tooltip');const anchors=tooltip?[...document.querySelectorAll('[data-tooltip]')]
                  .filter(node=>node.dataset.tooltip===tooltip.textContent&&node.getClientRects().length):[];
                return {menuBar:measure(document.getElementById('menuBar')),tooltip:measure(tooltip),anchors:anchors.map(measure),
                  panels:[...document.querySelectorAll('.menu-panel')].filter(node=>!node.hidden&&node.getClientRects().length)
                    .map(panel=>({panel:measure(panel),owner:measure(panel.parentElement),trigger:measure(panel.parentElement.querySelector(':scope > button')),rows:[...panel.children].map((row,index)=>({index,
                      wrapper:measure(row),item:measure(row.querySelector(':scope > button')),label:measure(row.querySelector(':scope > button > .menu-label')),
                      mark:measure(row.querySelector(':scope > button > .menu-mark')),accel:measure(row.querySelector(':scope > button > .menu-accel')),
                      separator:measure(row.querySelector(':scope > hr'))}))}))};})()
                """);
    }

    /** Измеряет общий viewport, заголовок и настоящую ячейку до и после действия драйвера. */
    private static Object tableAnchor(BrowserFixtureProbe.CdpBridge cdp, String rowId) throws Exception {
        return cdp.evaluate("(()=>{const table=document.getElementById('table');const scroll=table.querySelector('.table-scroll');const row=[...table.querySelectorAll('.table-row')].find(node=>node.dataset.cpId===" + JsonWriter.write(rowId) + ");const box=node=>node?.getBoundingClientRect().toJSON()||null;return {rowId:" + JsonWriter.write(rowId) + ",scrollTop:scroll.scrollTop,scrollLeft:scroll.scrollLeft,viewport:box(scroll),clientWidth:scroll.clientWidth,clientHeight:scroll.clientHeight,offsetWidth:scroll.offsetWidth,canvas:box(scroll.querySelector('.table-canvas')),header:box(table.querySelector('.table-header')),row:box(row),index:row?.dataset.index,columns:[...table.querySelector('.table-header').children].map(header=>({id:header.dataset.cpId,header:box(header),cell:box(row&&[...row.children].find(cell=>cell.dataset.cpId===header.dataset.cpId))})),popup:box(document.querySelector('.quick-edit'))};})()");
    }

    /** Проверяет реальные размеры фильтра, popup-поля и горизонтальный порядок кнопок пустого состояния. */
    private static void verifyRenderGeometry(BrowserFixtureProbe.CdpBridge cdp) throws Exception {
        Object result = cdp.evaluate("""
                (()=>{const errors=[];const filter=document.querySelector('#toolbar .FilterField');
                if(filter){const input=filter.querySelector('input');
                  if(Math.abs(filter.getBoundingClientRect().width-input.getBoundingClientRect().width)>0.1)errors.push('filter-clear-adds-width');
                  const clear=filter.querySelector('button:not([hidden])');
                  if(clear&&clear.getBoundingClientRect().right>filter.getBoundingClientRect().right+0.1)errors.push('filter-clear-outside');}
                for(const popup of document.querySelectorAll('.quick-edit')){
                  const input=popup.querySelector('input');const field=input.getBoundingClientRect();const raw=popup.getBoundingClientRect();
                  if(Math.abs(field.width-parseFloat(input.style.width))>0.1)errors.push('popup-field-width');
                  if(Math.abs(raw.width-parseFloat(popup.style.width))>0.1)errors.push('popup-spec-width');
                  if(popup.scrollWidth>popup.clientWidth)errors.push('popup-text-overflow');
                  const problem=popup.querySelector('.problem');const hint=popup.querySelector('.hint');
                  if(hint){const previous=problem.hidden?field:problem.getBoundingClientRect();
                    if(Math.abs(hint.getBoundingClientRect().top-previous.bottom-6)>0.1)errors.push('popup-hint-gap');}
                  if(!problem.hidden&&Math.abs(problem.getBoundingClientRect().top-field.bottom-6)>0.1)errors.push('popup-problem-order');}
                const buttons=[...document.querySelectorAll('.placeholder-buttons button')].map(node=>node.getBoundingClientRect());
                for(let i=1;i<buttons.length;i++){
                  if(buttons[i].left<buttons[i-1].right||Math.abs(buttons[i].top-buttons[0].top)>0.1)errors.push('placeholder-button-layout');}
                return errors;})()
                """);
        if (!((List<?>) result).isEmpty()) throw new AssertionError("Actual renderer geometry: " + JsonWriter.write(result));
    }

    /** Сравнивает только фактически измеренное содержимое с сохранёнными native-размерами, строго в пределах 4 px. */
    private static void verifyNativeBodySize(Object measured, String checkpoint) {
        var expected = java.util.Map.of("settings", new double[] {560, 690}, "income-create", new double[] {880, 624},
                "adjustment", new double[] {560, 415}, "goal", new double[] {640, 333}).get(checkpoint);
        if (expected == null) return;
        var windows = (java.util.List<?>) measured;
        if (windows.size() != 1) throw new AssertionError("Expected one measured form: " + checkpoint);
        var parts = (java.util.List<?>) ((java.util.Map<?, ?>) windows.getFirst()).get("parts");
        double left = Double.POSITIVE_INFINITY, top = Double.POSITIVE_INFINITY;
        double right = Double.NEGATIVE_INFINITY, bottom = Double.NEGATIVE_INFINITY;
        for (Object value : parts) {
            var box = (java.util.Map<?, ?>) value;
            left = Math.min(left, ((Number) box.get("x")).doubleValue()); top = Math.min(top, ((Number) box.get("y")).doubleValue());
            right = Math.max(right, ((Number) box.get("right")).doubleValue()); bottom = Math.max(bottom, ((Number) box.get("bottom")).doubleValue());
        }
        if (Math.abs(right - left - expected[0]) > 4 || Math.abs(bottom - top - expected[1]) > 4)
            throw new AssertionError(checkpoint + " actual body=" + (right - left) + "x" + (bottom - top) + " native=" + expected[0] + "x" + expected[1]);
    }
}
