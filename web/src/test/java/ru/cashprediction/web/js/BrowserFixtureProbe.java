package ru.cashprediction.web.js;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.json.JsonWriter;
import ru.cashprediction.core.ui.token.TokenCss;
import ru.cashprediction.core.ui.token.FontToken;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.core.ui.form.FieldView;
import ru.cashprediction.core.ui.form.FieldKind;
import ru.cashprediction.core.ui.form.FieldSpec;
import ru.cashprediction.core.ui.form.FieldCodec;
import ru.cashprediction.core.ui.form.FormRow;
import ru.cashprediction.core.ui.form.Option;
import ru.cashprediction.core.ui.form.Orientation;
import ru.cashprediction.core.ui.forms.plan.HorizonFields;

/** Изолированная проверка ES-модулей в настоящем Edge на JSON-фикстурах, не проверка паритета. */
public final class BrowserFixtureProbe {
    private final Path root;
    private final Map<String, Object> bootstrap;
    private final List<Map<String, Object>> fixtures;
    private final List<Map<String, Object>> effects = new ArrayList<>();
    private final List<Map<String, Object>> intents = new CopyOnWriteArrayList<>();
    private long seq = 42;
    private boolean queueStep;
    private boolean slowBootstrapRows;
    private boolean bootstrapRowsLoading;
    private boolean earlyStep;
    private boolean resyncOnce;
    private int bootstrapCount;
    private boolean chooserHistoryMode;
    private ru.cashprediction.core.app.AppState fontState;
    private volatile ru.cashprediction.core.ui.view.popup.SparklineModel sparklineFixture;

    private BrowserFixtureProbe(Path root) throws Exception {
        this.root = root;
        bootstrap = Json.asObject(JsonParser.parse(Files.readString(root.resolve("core/src/test/resources/ui-json/bootstrap.json"))), "bootstrap");
        fixtures = ((List<?>) JsonParser.parse(Files.readString(root.resolve("core/src/test/resources/ui-json/effects.json"))))
                .stream().map(value -> Json.asObject(value, "effect")).toList();
        bootstrap.put("testApi", true);
        var screen = Json.object(bootstrap, "screen"); var table = Json.object(screen, "table");
        table.put("scrollToRowId", ""); table.put("rowCount", 311);
        bootstrap.put("windows", List.of());
        var window = Json.object(fixtures.get(1), "window");
        var spec = Json.object(window, "spec");
        var page = Json.asObject(((List<?>) spec.get("pages")).getFirst(), "page");
        var rows = new ArrayList<Object>((List<?>) page.get("rows"));
        rows.addAll((List<?>) UiJson.toTree(HorizonFields.rows())); page.put("rows", rows);
        rows.add(UiJson.toTree(new FormRow.Field(new FieldSpec("money", FieldKind.MONEY, "fixture money", "", "",
                List.of(), 0, 0, 0, 0, 0, false, "", Orientation.HORIZONTAL, false))));
        rows.add(UiJson.toTree(new FormRow.Field(new FieldSpec("choice", FieldKind.CHOICE, "fixture choice", "", "",
                List.of(new Option("stored", "visible caption", "", false)), 0, 0, 0, 0, 0, false, "", Orientation.HORIZONTAL, false))));
        rows.add(UiJson.toTree(new FormRow.Field(new FieldSpec("preview", FieldKind.PREVIEW, "fixture preview", "", "",
                List.of(), 0, 0, 0, 0, 0, false, "", Orientation.HORIZONTAL, false))));
        var fields = Json.object(Json.object(window, "view"), "fields");
        fields.put("horizonKind", UiJson.toTree(FieldView.of("MONTHS")));
        fields.put("horizonValue", UiJson.toTree(FieldView.of("12")));
        fields.put("horizonUntil", UiJson.toTree(FieldView.of("")));
        fields.put("money", UiJson.toTree(FieldView.of("0,00")));
        fields.put("choice", UiJson.toTree(FieldView.of("stored")));
        fields.put("preview", UiJson.toTree(FieldView.of("")));
        var view = Json.object(window, "view");
        view.put("preview", List.of(Map.of("text", "preview caption", "selectable", false, "color", "TEXT_PRIMARY")));
        view.put("problem", Map.of("severity", "WARNING", "text", "fixture warning"));
    }

    /** Запускает проверку с отдельным профилем Edge и гарантированным закрытием дерева процессов. */
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = Path.of(args[1]).toAbsolutePath().normalize(); Files.createDirectories(output);
        var probe = new BrowserFixtureProbe(root);
        var pool = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(pool); server.createContext("/", probe::handle); server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/app.html?t=fixture";
        try {
            if (args.length > 2 && "chooser-only".equals(args[2])) {
                probe.verifyChooserHistory(output, url);
                return;
            }
            if (args.length > 2 && "results-only".equals(args[2])) {
                probe.verifyResultReserve(output, url);
                return;
            }
            if (args.length > 2 && "multiline-only".equals(args[2])) {
                probe.verifyMultilineMetrics(output, url);
                return;
            }
            if (args.length > 2 && "menu-only".equals(args[2])) {
                probe.verifyMenuMetrics(output, url);
                return;
            }
            if (args.length > 2 && "spark-only".equals(args[2])) {
                probe.verifySparkMetrics(output, url);
                return;
            }
            if (args.length > 2 && "width-only".equals(args[2])) {
                probe.verifyFieldWidths(output, url);
                return;
            }
            if (args.length > 2 && "popup-only".equals(args[2])) {
                probe.verifyPopupOrder(output, url);
                return;
            }
            if (args.length > 2 && "wizard-only".equals(args[2])) {
                probe.verifyWizardMetrics(output, url);
                return;
            }
            if (args.length > 2 && "glyph-only".equals(args[2])) {
                probe.verifyAlertGlyphs(output, url);
                return;
            }
            if (args.length > 2 && "anchors-only".equals(args[2])) {
                probe.verifyTableAnchors(output, url);
                return;
            }
            if (args.length > 2 && "fonts-only".equals(args[2])) {
                probe.verifyComputedFonts(output, url, false);
                return;
            }
            if (args.length > 2 && "fonts-extra".equals(args[2])) {
                probe.prepareFontModels();
                probe.verifyComputedFonts(output, url, true);
                return;
            }
            for (int width : new int[] {1200, 500, 1920}) {
                try (var browser = BrowserBridge.start(output.resolve("edge-" + width), width, url);
                     var cdp = browser.connect()) {
                    cdp.waitFor("!!window.cpParityTestApi", Duration.ofSeconds(20));
                    cdp.evaluate("window.dispatchEvent(new Event('resize'))");
                    cdp.evaluate("window.cpParityTestApi.awaitIdle({timeoutMs:5000})");
                    check(probe.earlyStep, "test.step arrived during bootstrap row rendering");
                    check(cdp.evaluate("document.documentElement.scrollWidth <= innerWidth"), "page width " + width);
                    Files.writeString(output.resolve("alignment-" + width + ".json"), JsonWriter.write(cdp.evaluate("(() => {const rect=node=>node?.getBoundingClientRect().toJSON(); const table=document.getElementById('table'); const scroll=table.querySelector('.table-scroll'); return {cards:[...document.querySelectorAll('#summary .card')].map(rect),header:rect(table.querySelector('.table-header')),scrollWidth:scroll.offsetWidth,clientWidth:scroll.clientWidth,columns:[...table.querySelector('.table-header').children].map(node=>({id:node.dataset.cpId,header:rect(node),cell:rect(table.querySelector('.table-row [data-cp-id='+node.dataset.cpId+']'))}))};})()")), StandardCharsets.UTF_8);
                    check(cdp.evaluate("(() => {const table=document.getElementById('table'); const header=table.querySelector('.table-header'); const scroll=table.querySelector('.table-scroll'); const cards=[...document.querySelectorAll('#summary .card')]; return cards.every(card=>card.getBoundingClientRect().height===72) && header.getBoundingClientRect().width===scroll.clientWidth && scroll.offsetWidth-scroll.clientWidth===16 && [...header.children].every(column=>{const cell=table.querySelector('.table-row [data-cp-id='+column.dataset.cpId+']'); return cell && Math.abs(cell.getBoundingClientRect().x-column.getBoundingClientRect().x)<0.1 && Math.abs(cell.getBoundingClientRect().width-column.getBoundingClientRect().width)<0.1;});})()"), "actual card height and scrollbar-aware header/body alignment");
                    if (width == 1200) {
                        check(cdp.evaluate("document.getElementById('summary').getBoundingClientRect().height===84 && document.querySelector('.table-header').getBoundingClientRect().width===1184 && document.querySelector('.table-header [data-cp-id=title]').getBoundingClientRect().width===456"), "desktop shared summary and title geometry at 1200px");
                        probe.tableSelection("fixture-0");
                        cdp.waitFor("document.querySelector('.table-row[aria-selected=true]')?.dataset.cpId==='fixture-0'", Duration.ofSeconds(5));
                        check(cdp.evaluate("window.cpParityTestApi.dump({step:'physical-selection'}).then(reply=>reply.value.table.selectedRowId==='fixture-0')"), "present physical selection is dumped");
                        probe.tableSelection("hidden-logical-row");
                        cdp.waitFor("document.getElementById('table').dataset.selectedRowId==='hidden-logical-row' && !document.querySelector('.table-row[aria-selected=true]')", Duration.ofSeconds(5));
                        check(cdp.evaluate("window.cpParityTestApi.dump({step:'hidden-logical-selection'}).then(reply=>reply.value.table.selectedRowId==='' && document.getElementById('table').dataset.selectedRowId==='hidden-logical-row')"), "hidden logical selection is not a physical DOM selection and remains preserved");
                        probe.tableSelection("");
                        cdp.waitFor("document.getElementById('table').dataset.selectedRowId===''", Duration.ofSeconds(5));
                        probe.tableRows(0);
                        cdp.waitFor("document.getElementById('table').getAttribute('aria-rowcount')==='0' && document.querySelector('.table-header').getBoundingClientRect().width===1200", Duration.ofSeconds(5));
                        check(cdp.evaluate("document.querySelector('.table-scroll').offsetWidth===document.querySelector('.table-scroll').clientWidth"), "no unnecessary scrollbar gutter without overflowing rows");
                        probe.tableRows(311);
                        cdp.waitFor("document.getElementById('table').getAttribute('aria-rowcount')==='311' && document.querySelector('.table-header').getBoundingClientRect().width===1184", Duration.ofSeconds(5));
                    }
                    Files.writeString(output.resolve("geometry-" + width + ".json"), JsonWriter.write(cdp.evaluate("(() => {const rect=node=>node.getBoundingClientRect().toJSON(); const table=document.getElementById('table'); return {summary:rect(document.getElementById('summary')),cards:[...document.querySelectorAll('#summary .card')].map(rect),header:rect(table.querySelector('.table-header')),scrollbar:table.querySelector('.table-scroll').offsetWidth-table.querySelector('.table-scroll').clientWidth,columns:[...table.querySelector('.table-header').children].map(node=>({id:node.dataset.cpId,bounds:rect(node)}))};})()")), StandardCharsets.UTF_8);
                    Files.writeString(output.resolve("toolbar-" + width + ".json"), JsonWriter.write(cdp.evaluate("[...document.querySelectorAll('#toolbar > .toolbar-node > button, #toolbar .toolbar-arrow')].map(node=>({id:node.dataset.cpId, width:node.getBoundingClientRect().width,height:node.getBoundingClientRect().height,font:getComputedStyle(node).font,padding:getComputedStyle(node).padding}))")), StandardCharsets.UTF_8);
                    check(cdp.evaluate("(() => { const toolbar=document.getElementById('toolbar'); const buttons=[...toolbar.querySelectorAll(':scope > .toolbar-node > button')].filter(button=>!button.hidden); return getComputedStyle(toolbar).columnGap==='4px' && getComputedStyle(toolbar).paddingLeft==='0px' && buttons.every(button=>button.getBoundingClientRect().height===28 && getComputedStyle(button).fontSize==='13px' && getComputedStyle(button).fontFamily.includes('Segoe UI')) && [...toolbar.querySelectorAll('.separator')].every(node=>node.getBoundingClientRect().height===28) && ['tb.undo','tb.redo'].every(id=>[...toolbar.children].find(node=>node.dataset.cpId===id).getBoundingClientRect().width===28) && [...toolbar.querySelectorAll('.toolbar-arrow')].every(node=>node.getBoundingClientRect().width===20); })()"), "shared toolbar tokens");
                    check(cdp.evaluate("(() => { const toolbar=document.getElementById('toolbar'); const canvas=document.createElement('canvas'); const context=canvas.getContext('2d'); return [...toolbar.querySelectorAll(':scope > .toolbar-node > button:not(.glyph-only):not(.toolbar-arrow)')].every(button=>{ const css=getComputedStyle(button); context.font=css.font; const label=button.querySelector('.toolbar-label'); const arrow=button.querySelector('.toolbar-arrow'); return css.paddingTop==='4px' && css.paddingLeft==='10px' && Math.abs(button.getBoundingClientRect().width-context.measureText(label.textContent).width-20-(arrow?20:0))<1; }); })()"), "toolbar measured text widths");
                    if (width >= 1200) check(cdp.evaluate("document.getElementById('toolbar').getBoundingClientRect().height===36 && document.querySelector('#toolbar > .toolbar-node > button').getBoundingClientRect().x===0 && document.querySelector('#toolbar > .toolbar-node > button').getBoundingClientRect().y===32"), "toolbar content origin and vertical centering");
                    check(cdp.evaluate("document.querySelectorAll('.table-row').length > 0 && document.querySelectorAll('.table-row').length < 60"), "virtual table");
                    check(cdp.evaluate("[...document.querySelectorAll('.table-row [data-cp-id=balance]')].every(node=>Number(getComputedStyle(node).fontWeight)>=600)"), "balance column retains bold over plain row style");
                    check(cdp.evaluate("window.cpParityTestApi.dump({step:'slider-text'}).then(reply=>{ const visit=items=>items.every(item=>(item.kind!=='Slider'||item.text==='')&&visit(item.children)); return visit(reply.value.menuBar)&&reply.value.toolbar.items.every(item=>visit(item.items)); })"), "slider current caption stays separate from menu text");
                    cdp.waitFor("(() => { window.fixtureStep ||= window.cpParityTestApi.takeStep({}).value; return !!window.fixtureStep; })()", Duration.ofSeconds(5));
                    check(cdp.evaluate("window.fixtureStep.command === 'sample'"), "queue API");
                    check(cdp.evaluate("window.cpParityTestApi.takeStep({}).value === null"), "sole queue consumer");
                    cdp.evaluate("document.activeElement.blur(); window.cpParityTestApi.execute({kind:'Key',args:{chord:{key:'ALT'}}})");
                    check(cdp.evaluate("!!document.activeElement.closest('#menuBar')"), "standalone Alt release");
                    check(probe.intents.stream().anyMatch(intent -> "key".equals(intent.get("type")) && "ALT".equals(Json.object(intent, "chord").get("key"))), "standalone Alt ordinary key intent");
                    cdp.evaluate("document.activeElement.dispatchEvent(new KeyboardEvent('keydown',{code:'KeyN',key:String.fromCharCode(1090),altKey:true,shiftKey:true,bubbles:true,cancelable:true})); document.activeElement.dispatchEvent(new KeyboardEvent('keyup',{code:'KeyN',key:String.fromCharCode(1090),altKey:true,shiftKey:true,bubbles:true,cancelable:true})); window.cpParityTestApi.awaitIdle({timeoutMs:5000})");
                    check(probe.intents.stream().anyMatch(intent -> "key".equals(intent.get("type")) && "N".equals(Json.object(intent, "chord").get("key"))), "Russian physical key");
                    cdp.evaluate("window.cpParityTestApi.execute({kind:'Menu',args:{idOrPath:'file.rename'}})");
                    check(cdp.evaluate("document.querySelector('dialog[open]:not(#screens)').open && document.querySelector('dialog[open]:not(#screens)').matches(':modal')"), "actual modal");
                    check(probe.intents.stream().anyMatch(intent -> "formShown".equals(intent.get("type"))), "form acknowledgement");
                    check(cdp.evaluate("window.cpParityTestApi.dump({step:'dialog-content'}).then(reply=>{const root=document.querySelector('dialog[data-cp-id=w1]'); const parts=[...root.children].filter(node=>!node.classList.contains('window-title')&&!node.hidden&&node.getClientRects().length).map(node=>node.getBoundingClientRect()); const main=document.getElementById('main').getBoundingClientRect(); const actual=reply.value.windows[0].bounds; const x=Math.min(...parts.map(rect=>rect.x)),y=Math.min(...parts.map(rect=>rect.y)); return actual.x===x-main.x && actual.y===y-main.y && actual.width===Math.max(...parts.map(rect=>rect.right))-x && actual.height===Math.max(...parts.map(rect=>rect.bottom))-y && actual.y>root.getBoundingClientRect().y;})"), "dump bounds are actual dialog content without window title or border");
                    if (width == 1200) {
                        cdp.evaluate("window.fixtureRawBounds=document.querySelector('dialog[data-cp-id=w1]').getBoundingClientRect().toJSON(); document.getElementById('main').style.transform='translate(37px,23px)'");
                        check(cdp.evaluate("window.cpParityTestApi.dump({step:'translated-dialog-content'}).then(reply=>{const root=document.querySelector('dialog[data-cp-id=w1]');const main=document.getElementById('main').getBoundingClientRect();const header=root.querySelector('.window-header').getBoundingClientRect(); return reply.value.windows[0].bounds.x===header.x-main.x && reply.value.windows[0].bounds.y===header.y-main.y && JSON.stringify(root.getBoundingClientRect().toJSON())===JSON.stringify(window.fixtureRawBounds);})"), "main origin translation changes dump only and preserves raw snapshot bounds");
                        cdp.evaluate("document.getElementById('main').style.transform=''");
                    }
                    cdp.evaluate("window.cpParityTestApi.execute({kind:'Fill',args:{window:'w1',values:{value:'typed fixture'}}})");
                    check(cdp.evaluate("document.querySelector('dialog input').value === 'typed fixture'"), "real input");
                    cdp.evaluate("(() => { const input=document.querySelector('dialog input[data-cp-id=money]'); input.focus(); input.value='80000'; input.dispatchEvent(new Event('input',{bubbles:true})); })(); window.cpParityTestApi.awaitIdle({timeoutMs:5000})");
                    check(cdp.evaluate("document.querySelector('dialog input[data-cp-id=money]').value==='80000'"), "raw money typing remains raw");
                    check(probe.intents.stream().anyMatch(intent -> "money".equals(intent.get("fieldId")) && "80000".equals(intent.get("raw")) && Boolean.FALSE.equals(intent.get("committed"))), "raw money intent");
                    cdp.evaluate("window.cpParityTestApi.execute({kind:'Fill',args:{window:'w1',values:{money:'80000'}}})");
                    check(cdp.evaluate("document.querySelector('dialog input[data-cp-id=money]').value==='80 000,00'"), "committed Fill money uses core display");
                    cdp.evaluate("window.cpParityTestApi.execute({kind:'Field',args:{windowTitle:'w1',label:'money',text:'invalid'}})");
                    check(cdp.evaluate("document.querySelector('dialog input[data-cp-id=money]').value==='invalid'"), "invalid committed money remains raw");
                    synchronized (probe) { probe.bootstrap.put("windows", List.of(probe.fixtures.get(1))); }
                    cdp.evaluate("window.fixtureReloadMarker=true; location.reload(); 'reload'");
                    cdp.waitFor("!window.fixtureReloadMarker && !!window.cpParityTestApi && document.querySelector('dialog[open] input[data-cp-id=money]')?.value==='invalid'", Duration.ofSeconds(10));
                    cdp.evaluate("window.cpParityTestApi.awaitIdle({timeoutMs:5000})");
                    check(cdp.evaluate("document.querySelector('dialog[open] input[data-cp-id=money]').value==='invalid'"), "reload restores invalid raw money from server bootstrap");
                    synchronized (probe) { probe.bootstrap.put("windows", List.of()); }
                    check(cdp.evaluate("window.cpParityTestApi.dump({step:'captions'}).then(reply=>{ const window=reply.value.windows[0]; return window.fields.find(field=>field.id==='choice').text==='visible caption' && JSON.stringify(window.fields.find(field=>field.id==='preview').options)===JSON.stringify(['preview caption']) && window.problem===String.fromCharCode(9888)+' fixture warning'; })"), "visible choice, preview options and problem glyph");
                    check(cdp.evaluate("(() => { const groups=[...document.querySelectorAll('dialog[open] fieldset')]; return groups.length===2 && groups[0].getBoundingClientRect().y < groups[1].getBoundingClientRect().y && new Set(groups.flatMap(group=>[...group.querySelectorAll('input')].map(input=>input.name))).size===1; })()"), "two physical radio rows, one name");
                    for (String value : List.of("YEARS", "UNTIL", "MONTHS")) {
                        cdp.evaluate("window.cpParityTestApi.execute({kind:'Fill',args:{window:'w1',values:{horizonKind:'" + value + "'}}})");
                        check(cdp.evaluate("document.querySelectorAll('dialog[open] input[type=radio]:checked').length===1 && document.querySelector('dialog[open] input[type=radio]:checked').value==='" + value + "'"), "global radio selection " + value);
                        check(probe.intents.stream().anyMatch(intent -> "horizonKind".equals(intent.get("fieldId")) && value.equals(intent.get("raw"))), "real radio change " + value);
                    }
                    check(cdp.evaluate("window.cpParityTestApi.dump({step:'radio'}).then(reply => { const fields=reply.value.windows[0].fields; const group=fields.filter(field=>field.id==='horizonKind'); const labels=[...document.querySelectorAll('dialog[open] input[type=radio]')].map(input=>input.parentElement.textContent); return group.length===1 && group[0].text==='MONTHS' && JSON.stringify(group[0].options)===JSON.stringify(labels) && labels.length===3 && fields.findIndex(field=>field.id==='horizonKind')<fields.findIndex(field=>field.id==='horizonUntil'); })"), "logical radio dump in first occurrence order");
                    if (width == 1200) {
                        probe.preview(List.of(Map.of("text", "preview caption", "selectable", true, "color", "TEXT_PRIMARY")));
                        cdp.waitFor("document.querySelector('dialog[open] .preview-item')?.getAttribute('aria-disabled')==='false'", Duration.ofSeconds(5));
                        cdp.evaluate("document.querySelector('dialog[open] .preview-item').click(); window.cpParityTestApi.awaitIdle({timeoutMs:5000})");
                        check(cdp.evaluate("window.cpParityTestApi.dump({step:'preview-selected'}).then(reply=>reply.value.windows[0].previewSelected===0)"), "actual preview selection");
                        probe.preview(List.of(Map.of("text", "replacement caption", "selectable", true, "color", "TEXT_PRIMARY")));
                        cdp.waitFor("document.querySelector('dialog[open] .preview-item')?.textContent==='replacement caption'", Duration.ofSeconds(5));
                        check(cdp.evaluate("window.cpParityTestApi.dump({step:'preview-replaced'}).then(reply=>reply.value.windows[0].previewSelected===-1)"), "replacement preview clears stale selected index");
                        probe.preview(List.of());
                        cdp.waitFor("document.querySelectorAll('dialog[open] .preview-item').length===0", Duration.ofSeconds(5));
                        check(cdp.evaluate("window.cpParityTestApi.dump({step:'preview-empty'}).then(reply=>reply.value.windows[0].preview.length===0 && reply.value.windows[0].previewSelected===-1 && reply.value.windows[0].fields.find(field=>field.id==='preview').options.length===0)"), "empty preview removes old DOM rows and options");
                        probe.preview(List.of(Map.of("text", "preview caption", "selectable", false, "color", "TEXT_PRIMARY")));
                        cdp.waitFor("document.querySelector('dialog[open] .preview-item')?.textContent==='preview caption'", Duration.ofSeconds(5));
                    }
                    cdp.evaluate("window.cpParityTestApi.execute({kind:'Cancel',args:{window:'w1'}})");
                    check(cdp.evaluate("document.querySelectorAll('dialog[open]:not(#screens)').length === 0"), "form closed");
                    if (width == 1200) {
                        cdp.evaluate("window.cpParityTestApi.execute({kind:'Chooser',args:{path:null}})");
                        check(cdp.evaluate("window.cpParityTestApi.execute({kind:'Chooser',args:{path:'C:/second.md'}}).then(()=>false,error=>error.message==='Chooser answer already pending')"), "second pending chooser answer rejected");
                        long cancels = probe.intents.stream().filter(intent -> "chooser".equals(intent.get("windowId")) && "formButton".equals(intent.get("type"))).count();
                        probe.chooser();
                        cdp.evaluate("window.cpParityTestApi.awaitIdle({timeoutMs:5000})");
                        cdp.waitFor("window.cpParityTestApi.awaitIdle({timeoutMs:5000}).then(()=>fetch('/api/test/counters',{headers:{'X-Token':'fixture'}})).then(response=>response.json()).then(reply=>reply.counters['fixture.chooser.answers']===1)", Duration.ofSeconds(5));
                        check(probe.intents.stream().filter(intent -> "chooser".equals(intent.get("windowId")) && "formButton".equals(intent.get("type"))).count() == cancels + 1, "prequeued cancel consumed exactly once");
                        cdp.evaluate("window.cpParityTestApi.execute({kind:'Chooser',args:{path:'C:/fixture/queued.md'}})");
                        probe.chooser();
                        cdp.evaluate("window.cpParityTestApi.awaitIdle({timeoutMs:5000})");
                        cdp.waitFor("window.cpParityTestApi.awaitIdle({timeoutMs:5000}).then(()=>fetch('/api/test/counters',{headers:{'X-Token':'fixture'}})).then(response=>response.json()).then(reply=>reply.counters['fixture.chooser.answers']===2)", Duration.ofSeconds(5));
                        check(probe.intents.stream().anyMatch(intent -> "chooser".equals(intent.get("windowId")) && "name".equals(intent.get("fieldId")) && "queued.md".equals(intent.get("raw"))), "prequeued path enters real save name field");
                        check(probe.intents.stream().filter(intent -> "chooser".equals(intent.get("windowId")) && "formButton".equals(intent.get("type"))).count() == cancels + 2, "prequeued path confirmed exactly once");
                    }
                    cdp.evaluate("window.cpParityTestApi.execute({kind:'Menu',args:{idOrPath:'help.about'}})");
                    check(cdp.evaluate("document.querySelector('.alert-window').open && document.querySelector('.alert-window').matches(':modal')"), "actual alert modal");
                    check(cdp.evaluate("window.cpParityTestApi.dump({step:'native-alert-icon'}).then(reply=>document.querySelector('.alert-window .window-glyph').textContent==='?' && reply.value.alerts[0].glyph==='')"), "native visible confirmation icon encodes empty override");
                    if (width == 1200) {
                        cdp.evaluate("document.querySelector('.alert-window .window-glyph').style.visibility='hidden'");
                        check(cdp.evaluate("window.cpParityTestApi.dump({step:'hidden-alert-icon'}).then(reply=>reply.value.alerts[0].glyph==='missing-native-icon:CONFIRMATION')"), "invisible native icon remains detectable");
                        cdp.evaluate("document.querySelector('.alert-window .window-glyph').style.visibility='visible'");
                        cdp.evaluate("document.querySelector('.alert-window .window-glyph').textContent='wrong-icon'");
                        check(cdp.evaluate("window.cpParityTestApi.dump({step:'wrong-alert-icon'}).then(reply=>reply.value.alerts[0].glyph==='wrong-icon')"), "wrong native icon remains detectable");
                        cdp.evaluate("document.querySelector('.alert-window .window-glyph').remove()");
                        check(cdp.evaluate("window.cpParityTestApi.dump({step:'missing-alert-icon'}).then(reply=>reply.value.alerts[0].glyph==='missing-native-icon:CONFIRMATION')"), "missing native icon remains detectable");
                        cdp.evaluate("(() => {const icon=document.createElement('span'); icon.className='window-glyph'; icon.textContent=String.fromCharCode(8505); document.querySelector('.alert-window .window-header').prepend(icon);})()");
                        probe.alertGlyph("\u27f2");
                        cdp.waitFor("document.querySelector('.alert-window .window-glyph').textContent===String.fromCharCode(10226)", Duration.ofSeconds(5));
                        check(cdp.evaluate("window.cpParityTestApi.dump({step:'custom-alert-icon'}).then(reply=>reply.value.alerts[0].glyph===String.fromCharCode(10226))"), "custom restore glyph remains actual visible text");
                        probe.alertGlyph("");
                        cdp.waitFor("document.querySelector('.alert-window .window-glyph').textContent==='?'", Duration.ofSeconds(5));
                        probe.alertGlyph("?");
                        cdp.waitFor("document.querySelector('.alert-window').dataset.glyphMode==='override'", Duration.ofSeconds(5));
                        check(cdp.evaluate("window.cpParityTestApi.dump({step:'explicit-native-looking-icon'}).then(reply=>reply.value.alerts[0].glyph==='?')"), "explicit override matching fallback is not erased");
                        probe.alertGlyph("");
                        cdp.waitFor("document.querySelector('.alert-window').dataset.glyphMode==='native'", Duration.ofSeconds(5));
                    }
                    check(cdp.evaluate("(() => {const node=document.querySelector('.alert-window'); const filter=document.querySelector('#toolbar input'); filter.focus(); return document.getElementById('main').inert && document.activeElement!==filter && document.elementFromPoint(2,2)===node && getComputedStyle(node,'::backdrop').backgroundColor==='rgba(0, 0, 0, 0)';})()"), "transparent modal backdrop still blocks pointer hit and main focus");
                    byte[] modalImage = cdp.captureScreenshot();
                    var modalPixels = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(modalImage));
                    check(modalPixels.getRGB(2, 2) == ru.cashprediction.core.ui.token.ColorToken.BG_WINDOW.argb(), "real modal preserves shared background token");
                    Files.write(output.resolve("transparent-modal-" + width + ".png"), modalImage);
                    check(probe.intents.stream().anyMatch(intent -> "alertShown".equals(intent.get("type")) && intent.containsKey("windowId") && !intent.containsKey("alertId")), "strict alert acknowledgement");
                    check(cdp.evaluate("window.cpParityTestApi.dump({step:'alert-origin'}).then(reply=>{ const root=document.querySelector('.alert-window'); const origin=root.getBoundingClientRect().x; return reply.value.alerts[0].buttons.every(button=>{ const node=[...root.querySelectorAll('.window-buttons button')].find(node=>node.dataset.cpId===button.id); return button.x===node.getBoundingClientRect().x-origin; }); })"), "alert buttons use dialog content origin");
                    cdp.evaluate("window.cpParityTestApi.execute({kind:'Answer',args:{buttonText:document.querySelector('.alert-window .window-buttons button').textContent}})");
                    check(cdp.evaluate("document.querySelectorAll('dialog[open]:not(#screens)').length === 0"), "alert closed");
                    synchronized (probe) {
                        probe.fixtures.get(5).put("placement", Map.of("ownerId", "w1", "bounds", Map.of("x", 40, "y", 110, "width", 460, "height", 360), "anchor", Map.of("rowId", "fixture-0", "columnId", "income")));
                        probe.bootstrap.put("windows", List.of(probe.fixtures.get(1), probe.fixtures.get(5)));
                    }
                    cdp.evaluate("window.fixtureReloadMarker=true; location.reload(); 'reload'");
                    cdp.waitFor("!window.fixtureReloadMarker && document.querySelector('.alert-window')?.open && document.querySelector('dialog[data-cp-id=w1]')?.open", Duration.ofSeconds(10));
                    cdp.evaluate("window.cpParityTestApi.awaitIdle({timeoutMs:5000})");
                    check(cdp.evaluate("(() => {const node=document.querySelector('.alert-window'); const rect=node.getBoundingClientRect(); return node.dataset.ownerId==='w1' && node.matches(':modal') && rect.x===Math.max(0,Math.min(40,innerWidth-460)) && rect.y===110 && rect.width===460 && rect.height===360 && document.querySelector('dialog[data-cp-id=w1]').inert;})()"), "bootstrap alert restores actual owner and bounds");
                    var alertPlacement = cdp.evaluate("window.cpParityTestApi.dump({step:'alert-placement'}).then(reply=>{const root=document.querySelector('.alert-window'); const rect=root.getBoundingClientRect(); return {ownerId:root.dataset.ownerId,bounds:rect.toJSON(),buttons:reply.value.alerts[0].buttons,originMatches:reply.value.alerts[0].buttons.every(button=>{const node=[...root.querySelectorAll('.window-buttons button')].find(node=>node.dataset.cpId===button.id); return button.x===node.getBoundingClientRect().x-rect.x;})};})");
                    check(Json.asObject(alertPlacement, "placement").get("originMatches"), "restored alert dump retains measured button origin");
                    Files.writeString(output.resolve("alert-placement-" + width + ".json"), JsonWriter.write(alertPlacement), StandardCharsets.UTF_8);
                    synchronized (probe) { probe.bootstrap.put("windows", List.of()); probe.fixtures.get(5).remove("placement"); }
                    cdp.evaluate("window.cpParityTestApi.execute({kind:'Answer',args:{buttonText:document.querySelector('.alert-window .window-buttons button').textContent}})");
                    cdp.evaluate("window.cpParityTestApi.execute({kind:'Cancel',args:{window:'w1'}})");
                    if (width == 1200) {
                        synchronized (probe) { probe.bootstrap.put("overlay", "RECOVERY_PENDING"); probe.bootstrap.put("windows", List.of(probe.fixtures.get(5))); }
                        cdp.evaluate("window.fixtureReloadMarker=true; location.reload(); 'reload'");
                        cdp.waitFor("!window.fixtureReloadMarker && document.querySelector('.alert-window')?.open && !!window.cpParityTestApi", Duration.ofSeconds(10));
                        check(cdp.evaluate("window.cpParityTestApi.dump({step:'recovery-pending'}).then(reply=>{const value=reply.value; return document.getElementById('main').hidden && !document.querySelector('#menuBar button') && value.frame===null && value.menuBar.length===0 && value.toolbar===null && value.summary===null && value.table===null && value.chart===null && value.status.length===0 && value.alerts.length===1;})"), "recovery bootstrap shows alert without synthetic main content");
                        synchronized (probe) { probe.add(Map.of("type", "inert", "value", false)); probe.bootstrap.put("overlay", "NONE"); probe.bootstrap.put("windows", List.of()); }
                        cdp.waitFor("!document.getElementById('main').hidden && !!document.querySelector('#menuBar button')", Duration.ofSeconds(10));
                        check(cdp.evaluate("window.cpParityTestApi.dump({step:'recovery-main-shown'}).then(reply=>reply.value.frame.title!=='' && reply.value.table.rowCount===311)"), "actual inert release renders fresh main content");
                        cdp.evaluate("window.cpParityTestApi.execute({kind:'Answer',args:{buttonText:document.querySelector('.alert-window .window-buttons button').textContent}})");
                    }
                    var tree = cdp.evaluate("window.cpParityTestApi.dump({step:'fixture'})");
                    var dump = Json.object(Json.asObject(tree, "reply"), "value");
                    check(((Number) Json.object(dump, "counters").get("fixture.server.snapshot")).intValue() == 7, "protected server counter snapshot");
                    cdp.evaluate("document.getElementById('table').hidden=true; document.getElementById('chart').hidden=false");
                    check(cdp.evaluate("window.cpParityTestApi.dump({step:'hidden-table-header'}).then(reply=>!('table.header' in reply.value.frame.regions) && !Object.keys(reply.value.frame.regions).some(key=>key.startsWith('table.column.')))"), "hidden table header and columns are absent rather than zero rectangles");
                    cdp.evaluate("document.getElementById('table').hidden=false; document.getElementById('chart').hidden=true");
                    check(cdp.evaluate("window.cpParityTestApi.dump({step:'measurements'}).then(reply=>{ const regions=reply.value.frame.regions; const toolbar=document.getElementById('toolbar').getBoundingClientRect(); const status=document.getElementById('status').getBoundingClientRect(); return ['toolbar.baseline','status.baseline'].every((id,index)=>{ const parent=index?status:toolbar; return regions[id].height===1 && regions[id].y>=parent.top && regions[id].y<=parent.bottom; }) && [...document.querySelector('.table-header').children].every(column=>{ const actual=column.getBoundingClientRect(); const measured=regions['table.column.'+column.dataset.cpId]; return measured && measured.x===actual.x && measured.width===actual.width && measured.height===actual.height; }); })"), "real baseline and header geometry");
                    Files.writeString(output.resolve("dump-" + width + ".json"), JsonWriter.write(tree), StandardCharsets.UTF_8);
                    Files.write(output.resolve("fixture-" + width + ".png"), cdp.captureScreenshot());
                    if (width == 1200) {
                        cdp.evaluate("(() => { const frame=document.createElement('iframe'); frame.id='narrow-probe'; frame.style.cssText='position:fixed;left:0;top:0;width:400px;height:800px;border:0;z-index:1000'; frame.src='/app.html?t=fixture'; document.body.append(frame); })()");
                        cdp.waitFor("!!document.getElementById('narrow-probe').contentWindow.cpParityTestApi", Duration.ofSeconds(10));
                        cdp.evaluate("document.getElementById('narrow-probe').contentWindow.cpParityTestApi.awaitIdle({timeoutMs:5000})");
                        check(cdp.evaluate("(() => { const frame=document.getElementById('narrow-probe').contentWindow; return frame.innerWidth===400 && frame.document.documentElement.scrollWidth<=400 && frame.document.getElementById('toolbar').getBoundingClientRect().height>36; })()"), "real 400px iframe has wrapped toolbar and no page scroll");
                        Files.write(output.resolve("fixture-400-frame.png"), cdp.captureScreenshot());
                        cdp.evaluate("document.getElementById('narrow-probe').remove()");
                    }
                    System.out.println("FIXTURE BROWSER OK " + width);
                }
            }
        } finally { server.stop(0); pool.close(); }
    }

    private static void check(Object result, String message) {
        if (!Boolean.TRUE.equals(result)) throw new AssertionError(message);
    }

    /** Проверяет вычисленные шрифты живых виджетов по токенам ядра и намеренные искажения каждого свойства. */
    private void verifyComputedFonts(Path output, String url, boolean extended) throws Exception {
        var window = Json.object(fixtures.get(1), "window");
        var spec = Json.object(window, "spec");
        var page = Json.asObject(((List<?>) spec.get("pages")).getFirst(), "page");
        var rows = new ArrayList<Object>((List<?>) page.get("rows"));
        rows.add(UiJson.toTree(new FormRow.Results("font-results", 3))); page.put("rows", rows);
        Json.object(window, "view").put("results", List.of(Map.of("text", "fixture result", "color", "TEXT_PRIMARY")));
        var expected = new ArrayList<Map<String, Object>>();
        expected.add(fontExpectation("menubar", "#menuBar > .menu-node > button .menu-label", FontToken.BASE, null));
        var screen = Json.object(bootstrap, "screen");
        for (Object value : (List<?>) Json.object(screen, "toolbar").get("items")) {
            var item = Json.asObject(value, "toolbar item");
            String id = Json.requireString(item, "id");
            if (List.of("Spacer", "Separator").contains(item.get("kind"))) continue;
            if ("FilterField".equals(item.get("kind"))) {
                expected.add(fontExpectation("toolbar." + id, "#toolbar [data-cp-id='" + id + ".input']", FontToken.BASE, null));
            } else {
                boolean bold = List.of("ACCENT", "WHATIF").contains(String.valueOf(item.get("emphasis")));
                expected.add(fontExpectation("toolbar." + id, "#toolbar [data-cp-id='" + id + ".action'] .toolbar-label", FontToken.BASE, bold));
            }
        }
        expected.add(fontExpectation("summary.title", "#summary .card-title", FontToken.SMALL, null));
        expected.add(fontExpectation("summary.caption", "#summary .card-caption", FontToken.SMALL, null));
        expected.add(fontExpectation("summary.value", "#summary .card-value", FontToken.CARD, null));
        expected.add(fontExpectation("status", "#status .status-segment:not([hidden])", FontToken.SMALL, null));
        for (Object value : (List<?>) Json.object(screen, "table").get("columns")) {
            var column = Json.asObject(value, "column");
            String id = Json.requireString(column, "id");
            expected.add(fontExpectation("table.header." + id, ".table-header [data-cp-id='" + id + "']", FontToken.BASE, Boolean.TRUE.equals(column.get("bold"))));
        }
        expected.add(fontExpectation("dialog.header", "dialog[open] .window-header-text", FontToken.HEADER, null));
        expected.add(fontExpectation("dialog.label", "dialog[open] .field-label:not([hidden])", FontToken.BASE, null));
        expected.add(fontExpectation("dialog.field", "dialog[open] input[data-cp-id=money]", FontToken.BASE, null));
        expected.add(fontExpectation("dialog.results", "dialog[open] [data-cp-id=font-results] .result-line", FontToken.BASE, null));
        try (var browser = BrowserBridge.start(output.resolve("fonts-browser"), 1200, url);
             var cdp = browser.connect()) {
            cdp.waitFor("!!window.cpParityTestApi", Duration.ofSeconds(20));
            cdp.evaluate("window.cpParityTestApi.execute({kind:'Menu',args:{idOrPath:'file.rename'}})");
            cdp.evaluate("window.cpParityTestApi.awaitIdle({timeoutMs:5000})");
            cdp.evaluate("(() => {window.fontExpectations=" + JsonWriter.write(expected) + ";window.readFontCoverage=()=>{const normalize=value=>value.split(',').map(part=>part.trim().replace(/^[\"']|[\"']$/g,'').toLowerCase()).join(',');return window.fontExpectations.map(expectation=>{const node=document.querySelector(expectation.selector);if(!node||!node.isConnected||node.closest('[hidden]')||!node.getClientRects().length)return {...expectation,errors:['missing-visible-widget']};const css=getComputedStyle(node);const actual={family:css.fontFamily,size:parseFloat(css.fontSize),weight:Number(css.fontWeight),style:css.fontStyle};const errors=[];if(normalize(actual.family)!==normalize(expectation.family))errors.push('family');if(actual.size!==expectation.size)errors.push('size');if(actual.weight!==expectation.weight)errors.push('weight');if(actual.style!=='normal')errors.push('style');return {...expectation,actual,errors};});};})()");
            Object measured = cdp.evaluate("window.readFontCoverage()");
            Files.writeString(output.resolve("computed-fonts.json"), JsonWriter.write(measured), StandardCharsets.UTF_8);
            check(cdp.evaluate("window.readFontCoverage().every(row=>row.errors.length===0)"), "actual computed fonts match core token metadata: " + JsonWriter.write(measured));
            var negative = new ArrayList<Object>();
            for (String property : List.of("family", "size", "weight")) {
                String cssProperty = Map.of("family", "font-family", "size", "font-size", "weight", "font-weight").get(property);
                String wrong = Map.of("family", "monospace", "size", (FontToken.BASE.sizePx() + 3) + "px", "weight", "700").get(property);
                Object changed = cdp.evaluate("(() => {const expectation=window.fontExpectations.find(row=>row.id==='dialog.field');const node=document.querySelector(expectation.selector);const before=node.getAttribute('style');try{node.style.setProperty(" + JsonWriter.write(cssProperty) + "," + JsonWriter.write(wrong) + ",'important');return window.readFontCoverage().find(row=>row.id==='dialog.field');}finally{if(before===null)node.removeAttribute('style');else node.setAttribute('style',before);}})()");
                negative.add(changed);
                check(((List<?>) Json.asObject(changed, "negative font").get("errors")).contains(property), "wrong actual " + property + " detected");
            }
            Files.writeString(output.resolve("computed-fonts-negative.json"), JsonWriter.write(negative), StandardCharsets.UTF_8);
            check(cdp.evaluate("window.readFontCoverage().every(row=>row.errors.length===0)"), "font mutations restored without leaking into renderer");
            System.out.println("ACTUAL COMPUTED FONTS OK " + expected.size() + " representatives; family/size/weight negative cases 3");
            if (extended) verifyPopupFonts(cdp, output);
        }
    }

    /** Готовит данные настоящими построителями ядра, без копирования дампов или новых бизнес-правил. */
    private void prepareFontModels() {
        var today = java.time.LocalDate.of(2026, 9, 13);
        var plan = ru.cashprediction.core.app.SamplePlan.create(today);
        var view = ru.cashprediction.core.document.ViewState.defaults();
        var forecast = ru.cashprediction.core.forecast.ForecastEngine.forecast(plan, view.whatIf(), today, view.showSkipped());
        var document = new ru.cashprediction.core.app.DocumentView(plan, null, false, false, "", false, "", forecast, "", List.of());
        fontState = new ru.cashprediction.core.app.AppState(7, ru.cashprediction.core.app.ClientProfile.web(), today,
                root.resolve("CashMemory"), null, document, view, "", false,
                ru.cashprediction.core.document.AppSettings.defaults(), null, List.of(), null, null, "");
    }

    /** Открывает дополнительные живые элементы только штатными событиями и запросами renderer. */
    private void verifyPopupFonts(CdpBridge cdp, Path output) throws Exception {
        var coverage = new ArrayList<Object>();
        cdp.evaluate("window.cpParityTestApi.execute({kind:'Cancel',args:{window:'w1'}})");
        cdp.evaluate("document.querySelector('#toolbar [data-tooltip]').dispatchEvent(new PointerEvent('pointerover',{bubbles:true}))");
        cdp.waitFor("!!document.querySelector('.tooltip')", Duration.ofSeconds(5));
        coverage.add(readExtraFont(cdp, "tooltip", ".tooltip", FontToken.LEGEND, null));
        cdp.evaluate("document.querySelector('#summary [data-cp-id=m3]').dispatchEvent(new MouseEvent('contextmenu',{bubbles:true,cancelable:true,clientX:300,clientY:100}))");
        cdp.waitFor("!!document.querySelector('.context-menu:not([hidden]) .menu-label')", Duration.ofSeconds(5));
        coverage.add(readExtraFont(cdp, "context-menu", ".context-menu:not([hidden]) .menu-label", FontToken.BASE, null));
        cdp.evaluate("document.activeElement.dispatchEvent(new KeyboardEvent('keydown',{code:'Escape',key:'Escape',bubbles:true,cancelable:true}))");
        cdp.evaluate("document.querySelector('#summary [data-cp-id=m3]').dispatchEvent(new PointerEvent('pointerenter'))");
        cdp.waitFor("!!document.querySelector('.sparkline svg')", Duration.ofSeconds(5));
        coverage.add(readExtraFont(cdp, "sparkline.header", ".sparkline > strong", FontToken.HEADER, null));
        coverage.add(readExtraFont(cdp, "sparkline.explanation", ".sparkline > .hint", FontToken.SMALL, null));
        coverage.add(readExtraFont(cdp, "sparkline.minimum", ".sparkline > div:last-child > span:first-child", FontToken.MICRO, null));
        coverage.add(readExtraFont(cdp, "sparkline.maximum", ".sparkline > div:last-child > span:last-child", FontToken.MICRO, null));
        Files.writeString(output.resolve("sparkline-layout.json"), JsonWriter.write(cdp.evaluate("(()=>{const root=document.querySelector('.sparkline');const box=node=>node.getBoundingClientRect().toJSON();return {root:box(root),children:[...root.children].map(node=>({tag:node.tagName,box:box(node),display:getComputedStyle(node).display,fontSize:getComputedStyle(node).fontSize})),anchor:box(document.querySelector('#summary [data-cp-id=m3]'))};})()")), StandardCharsets.UTF_8);
        check(cdp.evaluate("(()=>{const root=document.querySelector('.sparkline');const svg=root.querySelector('svg');const box=svg.getBoundingClientRect();const css=getComputedStyle(root);return getComputedStyle(svg).display==='block' && box.width===240 && box.height===60 && parseFloat(css.paddingTop)===8 && parseFloat(css.paddingBottom)===8 && root.getBoundingClientRect().top===document.querySelector('#summary [data-cp-id=m3]').getBoundingClientRect().bottom+4;})()"), "actual sparkline chart, padding and card anchor per spec");
        cdp.evaluate("document.querySelector('#summary [data-cp-id=m3]').blur();window.cpParityTestApi.execute({kind:'Menu',args:{idOrPath:'view.chart'}})");
        cdp.waitFor("!document.getElementById('chart').hidden && !!document.querySelector('#chart .chart-legend text')", Duration.ofSeconds(5));
        coverage.add(readExtraFont(cdp, "chart.legend", "#chart .chart-legend text", FontToken.LEGEND, null));
        cdp.evaluate("(()=>{const node=document.getElementById('chart');const rect=node.getBoundingClientRect();node.dispatchEvent(new PointerEvent('pointermove',{bubbles:true,clientX:rect.x+rect.width/2,clientY:rect.y+rect.height/2}));})()");
        cdp.waitFor("!!document.querySelector('.dayCard > strong')", Duration.ofSeconds(5));
        coverage.add(readExtraFont(cdp, "day-card.header", ".dayCard > strong", FontToken.HEADER, null));
        coverage.add(readExtraFont(cdp, "day-card.balance", ".dayCard > div", FontToken.BASE, null));
        Files.writeString(output.resolve("day-card-layout.json"), JsonWriter.write(cdp.evaluate("(()=>{const root=document.querySelector('.dayCard');return {root:root.getBoundingClientRect().toJSON(),chart:document.getElementById('chart').getBoundingClientRect().toJSON(),children:[...root.children].map(node=>node.getBoundingClientRect().toJSON()),gap:getComputedStyle(root).rowGap};})()")), StandardCharsets.UTF_8);
        check(cdp.evaluate("(()=>{const root=document.querySelector('.dayCard');const rows=[...root.children].map(node=>node.getBoundingClientRect());const css=getComputedStyle(root);const box=root.getBoundingClientRect();const chart=document.getElementById('chart').getBoundingClientRect();return rows.every((row,index)=>index===0 || Math.abs(row.top-rows[index-1].bottom-3)<0.1) && parseFloat(css.paddingTop)===8 && box.width>=200 && box.width<=320 && box.left===chart.left+chart.width/2+16 && box.top===chart.top+chart.height/2+16;})()"), "actual day-card rows, padding, width and pointer anchor per spec");
        cdp.evaluate("window.cpParityTestApi.execute({kind:'Menu',args:{idOrPath:'help.hotkeys'}})");
        cdp.waitFor("!!document.querySelector('.alert-window .details-text:not([hidden])')", Duration.ofSeconds(5));
        coverage.add(readExtraFont(cdp, "help.hotkeys.mono", ".alert-window .details-text:not([hidden])", FontToken.MONO, null));
        Files.writeString(output.resolve("computed-fonts-extra.json"), JsonWriter.write(coverage), StandardCharsets.UTF_8);
        var errors = coverage.stream().map(value -> Json.asObject(value, "font")).filter(row -> !((List<?>) row.get("errors")).isEmpty()).toList();
        check(errors.isEmpty(), "actual extra font differences: " + JsonWriter.write(errors));
        System.out.println("ACTUAL EXTRA FONTS OK " + coverage.size());
    }

    /** Измеряет один дополнительный реальный виджет тем же валидатором, не создавая тестового узла. */
    private static Object readExtraFont(CdpBridge cdp, String id, String selector, FontToken token, Boolean bold) throws Exception {
        return cdp.evaluate("(() => {const previous=window.fontExpectations;try{window.fontExpectations=[" + JsonWriter.write(fontExpectation(id, selector, token, bold)) + "];return window.readFontCoverage()[0];}finally{window.fontExpectations=previous;}})()");
    }

    /** Сопоставляет роль виджета токену; дополнительные жирные варианты задаёт модель столбца или кнопки. */
    private static Map<String, Object> fontExpectation(String id, String selector, FontToken token, Boolean bold) {
        return Map.of("id", id, "selector", selector, "token", token.id(), "family", token.family(),
                "size", token.sizePx(), "weight", (bold == null ? token.bold() : bold) ? 700 : 400);
    }

    /** Проверяет общий minLines в обычной форме: два результата, ошибку, рост и перенос текста. */
    private void verifyResultReserve(Path output, String url) throws Exception {
        var window = Json.object(fixtures.get(1), "window");
        var spec = Json.object(window, "spec");
        var page = Json.asObject(((List<?>) spec.get("pages")).getFirst(), "page");
        var rows = new ArrayList<Object>((List<?>) page.get("rows"));
        rows.add(UiJson.toTree(new FormRow.Results("fixture-results", 3)));
        page.put("rows", rows);
        var two = List.of(Map.of("text", "first result", "color", "TEXT_PRIMARY"), Map.of("text", "second result", "color", "TEXT_PRIMARY"));
        Json.object(window, "view").put("results", two);
        try (var browser = BrowserBridge.start(output.resolve("results-browser"), 1200, url);
             var cdp = browser.connect()) {
            cdp.waitFor("!!window.cpParityTestApi", Duration.ofSeconds(20));
            cdp.evaluate("window.cpParityTestApi.execute({kind:'Menu',args:{idOrPath:'file.rename'}})");
            cdp.evaluate("window.cpParityTestApi.awaitIdle({timeoutMs:5000})");
            Object valid = cdp.evaluate("(async()=>{const {dialogContentBounds}=await import('/app/dom.js');const root=document.querySelector('dialog[data-cp-id=w1]');const results=root.querySelector('[data-cp-id=fixture-results]');window.resultsInitial=dialogContentBounds(root);return {content:window.resultsInitial,height:results.getBoundingClientRect().height,lineHeight:getComputedStyle(results).lineHeight,lines:results.children.length};})()");
            Files.writeString(output.resolve("results-valid.json"), JsonWriter.write(valid), StandardCharsets.UTF_8);
            check(cdp.evaluate("(()=>{const results=document.querySelector('[data-cp-id=fixture-results]');return results.children.length===2 && Math.abs(results.getBoundingClientRect().height-3*parseFloat(getComputedStyle(results).lineHeight))<0.1;})()"), "generic three-line reserve with two actual results");
            resultView(List.of());
            cdp.waitFor("document.querySelector('[data-cp-id=fixture-results]').children.length===0", Duration.ofSeconds(5));
            cdp.evaluate("window.cpParityTestApi.awaitIdle({timeoutMs:5000})");
            check(cdp.evaluate("(async()=>{const {dialogContentBounds}=await import('/app/dom.js');const root=document.querySelector('dialog[data-cp-id=w1]');const measured=dialogContentBounds(root);const dump=await window.cpParityTestApi.dump({step:'invalid-results'});return measured.height===window.resultsInitial.height && dump.value.windows[0].results.length===0 && !root.querySelector('.result-line');})()"), "invalid empty results keep real content height without invented lines");
            Files.writeString(output.resolve("results-invalid.json"), JsonWriter.write(cdp.evaluate("window.cpParityTestApi.dump({step:'invalid-results'}).then(reply=>reply.value)")), StandardCharsets.UTF_8);
            resultView(java.util.stream.IntStream.range(0, 5).mapToObj(index -> Map.of("text", "result " + index, "color", "TEXT_PRIMARY")).toList());
            cdp.waitFor("document.querySelector('[data-cp-id=fixture-results]').children.length===5", Duration.ofSeconds(5));
            check(cdp.evaluate("(()=>{const results=document.querySelector('[data-cp-id=fixture-results]');return results.getBoundingClientRect().height>=5*parseFloat(getComputedStyle(results).lineHeight);})()"), "more actual results grow beyond minimum");
            resultView(List.of(Map.of("text", "wrapped result ".repeat(80), "color", "TEXT_PRIMARY")));
            cdp.waitFor("document.querySelector('[data-cp-id=fixture-results]').children.length===1", Duration.ofSeconds(5));
            check(cdp.evaluate("(()=>{const results=document.querySelector('[data-cp-id=fixture-results]');const row=results.firstElementChild;return row.getBoundingClientRect().height>3*parseFloat(getComputedStyle(results).lineHeight) && results.getBoundingClientRect().height>=row.getBoundingClientRect().height;})()"), "wrapped actual line grows the result block");
            Files.write(output.resolve("results-wrapped.png"), cdp.captureScreenshot());
            cdp.evaluate("window.cpParityTestApi.execute({kind:'Cancel',args:{window:'w1'}})");
            synchronized (this) {
                rows.set(rows.size() - 1, UiJson.toTree(new FormRow.Results("fixture-results")));
                Json.object(window, "view").put("results", List.of());
            }
            cdp.evaluate("window.cpParityTestApi.execute({kind:'Menu',args:{idOrPath:'file.rename'}})");
            check(cdp.evaluate("(()=>{const results=document.querySelector('[data-cp-id=fixture-results]');return results.children.length===0 && results.getBoundingClientRect().height===0;})()"), "default zero minimum does not reserve rows");
            System.out.println("ACTUAL RESULT RESERVE OK " + JsonWriter.write(valid));
        }
    }

    /** Проверяет реальные строки спарклайна, переносы и отсутствие данных без фиксированной высоты popup. */
    private void verifySparkMetrics(Path output, String url) throws Exception {
        prepareFontModels();
        var seed = ru.cashprediction.core.ui.view.popup.PopupBuilders.sparkline(fontState, "now");
        var cases = new LinkedHashMap<String, ru.cashprediction.core.ui.view.popup.SparklineModel>();
        cases.put("normal", seed);
        cases.put("multiline", new ru.cashprediction.core.ui.view.popup.SparklineModel(seed.cardId(), seed.header() + "\n" + seed.header(),
                seed.explanation() + "\n" + seed.explanation(), seed.points(), seed.zeroY(), seed.marker(), seed.minText(), seed.maxText(), seed.noDataText()));
        cases.put("long-labels", new ru.cashprediction.core.ui.view.popup.SparklineModel(seed.cardId(), seed.header().replace(" ", "").repeat(4),
                seed.explanation().repeat(2), seed.points(), seed.zeroY(), seed.marker(), seed.minText().repeat(3), seed.maxText().repeat(3), seed.noDataText()));
        cases.put("no-data", new ru.cashprediction.core.ui.view.popup.SparklineModel(seed.cardId(), seed.header(), seed.explanation(), List.of(), null,
                null, "", "", ru.cashprediction.core.ui.text.UiText.get("spark.noData")));
        try (var browser = BrowserBridge.start(output.resolve("spark-browser"), 1200, url);
             var cdp = browser.connect()) {
            cdp.waitFor("!!window.cpParityTestApi", Duration.ofSeconds(20));
            cdp.evaluate("""
                    window.readSparkMetrics=()=>{const root=document.querySelector('.sparkline');const box=node=>node.getBoundingClientRect().toJSON();
                      const measure=node=>{const css=getComputedStyle(node);const walker=document.createTreeWalker(node,NodeFilter.SHOW_TEXT);const tops=new Set();let text;
                        while(text=walker.nextNode()){const range=document.createRange();range.selectNodeContents(text);for(const rect of range.getClientRects())if(rect.width>0&&rect.height>0)tops.add(rect.top);}
                        return {tag:node.tagName,text:node.textContent,box:box(node),lines:tops.size,fontFamily:css.fontFamily,fontSize:parseFloat(css.fontSize),
                          fontWeight:Number(css.fontWeight),lineHeight:parseFloat(css.lineHeight),overflowX:css.overflowX,overflowY:css.overflowY,
                          clientWidth:node.clientWidth,scrollWidth:node.scrollWidth,clientHeight:node.clientHeight,scrollHeight:node.scrollHeight};};
                      const css=getComputedStyle(root);const parts=[...root.children].map(measure);return {root:box(root),parts,
                        padding:[css.paddingTop,css.paddingBottom].map(parseFloat),border:[css.borderTopWidth,css.borderBottomWidth].map(parseFloat),gap:parseFloat(css.rowGap),
                        physicalGaps:parts.slice(1).map((part,index)=>part.box.top-parts[index].box.bottom),footer:[...root.querySelectorAll('.spark-footer > span')].map(measure),
                        anchor:box(document.querySelector('#summary [data-cp-id=m3]'))};};
                    """);
            double normalHeight = 0;
            for (var entry : cases.entrySet()) {
                sparklineFixture = entry.getValue();
                cdp.evaluate("(()=>{const card=document.querySelector('#summary [data-cp-id=m3]');card.dispatchEvent(new PointerEvent('pointerleave'));card.dispatchEvent(new PointerEvent('pointerenter'));})()");
                cdp.waitFor("document.querySelector('.sparkline > strong')?.textContent===" + JsonWriter.write(entry.getValue().header()), Duration.ofSeconds(5));
                Object measured = cdp.evaluate("window.readSparkMetrics()");
                Files.writeString(output.resolve("spark-" + entry.getKey() + ".json"), JsonWriter.write(measured), StandardCharsets.UTF_8);
                Files.write(output.resolve("spark-" + entry.getKey() + ".png"), cdp.captureScreenshot());
                var data = Json.asObject(measured, "spark metrics");
                var parts = ((List<?>) data.get("parts")).stream().map(value -> Json.asObject(value, "spark part")).toList();
                verifySparkText(parts.get(0), FontToken.HEADER, ru.cashprediction.core.ui.token.DesignTokens.SPARK_HEADER_LINE_HEIGHT);
                verifySparkText(parts.get(1), FontToken.SMALL, ru.cashprediction.core.ui.token.DesignTokens.SPARK_BODY_LINE_HEIGHT);
                for (Object value : (List<?>) data.get("footer")) verifySparkText(Json.asObject(value, "spark footer"), FontToken.MICRO,
                        ru.cashprediction.core.ui.token.DesignTokens.SPARK_FOOTER_LINE_HEIGHT);
                var footer = ((List<?>) data.get("footer")).stream().map(value -> Json.asObject(value, "footer")).toList();
                if (!footer.isEmpty()) check(((Number) Json.object(parts.getLast(), "box").get("height")).doubleValue()
                        == footer.stream().mapToInt(part -> ((Number) part.get("lines")).intValue()).max().orElseThrow()
                        * ru.cashprediction.core.ui.token.DesignTokens.SPARK_FOOTER_LINE_HEIGHT, "footer grows exactly with wrapped label lines " + entry.getKey());
                if ("no-data".equals(entry.getKey())) {
                    verifySparkText(parts.get(2), FontToken.SMALL, ru.cashprediction.core.ui.token.DesignTokens.SPARK_BODY_LINE_HEIGHT);
                    check(cdp.evaluate("!document.querySelector('.sparkline svg') && !document.querySelector('.sparkline .spark-footer')"), "no-data has genuine text and no empty chart/footer");
                } else check(cdp.evaluate("(()=>{const box=document.querySelector('.sparkline svg').getBoundingClientRect();return box.width===240&&box.height===60;})()"), "actual spark chart size " + entry.getKey());
                double height = ((Number) Json.object(data, "root").get("height")).doubleValue();
                double sum = parts.stream().mapToDouble(part -> ((Number) Json.object(part, "box").get("height")).doubleValue()).sum();
                for (String key : List.of("padding", "border")) for (Object value : (List<?>) data.get(key)) sum += ((Number) value).doubleValue();
                double gap = ((Number) data.get("gap")).doubleValue();
                sum += gap * (parts.size() - 1);
                check(gap == ru.cashprediction.core.ui.token.DesignTokens.SPARK_CONTENT_GAP && Math.abs(height - sum) < 0.1
                        && ((List<?>) data.get("physicalGaps")).stream().allMatch(value -> Math.abs(((Number) value).doubleValue() - gap) < 0.1), "popup height equals actual components, padding and shared gap " + entry.getKey());
                if ("normal".equals(entry.getKey())) normalHeight = height;
                if (List.of("multiline", "long-labels").contains(entry.getKey())) check(height > normalHeight, "wrapped content grows actual popup " + entry.getKey());
                System.out.println("ACTUAL SPARK " + entry.getKey() + " height=" + height + " componentHeights="
                        + parts.stream().map(part -> String.valueOf(Json.object(part, "box").get("height"))).toList());
            }
            System.out.println("ACTUAL SPARK METRICS GREEN 4 cases");
        }
    }

    /** Проверяет перенос по настоящим текстовым rects, шрифт токена и отсутствие clipping. */
    private static void verifySparkText(Map<String, Object> measured, FontToken font, int lineHeight) {
        int lines = ((Number) measured.get("lines")).intValue();
        double height = ((Number) Json.object(measured, "box").get("height")).doubleValue();
        // Footer может растянуть меньшую подпись до высоты соседней, но ни одну строку не обрезает.
        check(lines > 0 && ((Number) measured.get("lineHeight")).doubleValue() == lineHeight
                && height >= lines * lineHeight && ("SPAN".equals(measured.get("tag")) || Math.abs(height - lines * lineHeight) < 0.1)
                && ((Number) measured.get("fontSize")).intValue() == font.sizePx()
                && ((Number) measured.get("fontWeight")).intValue() == (font.bold() ? 700 : 400)
                && ((String) measured.get("fontFamily")).replace("\"", "").replace("'", "").replace(" ", "")
                        .equalsIgnoreCase(font.family().replace("\"", "").replace("'", "").replace(" ", ""))
                && ((Number) measured.get("scrollWidth")).doubleValue() <= ((Number) measured.get("clientWidth")).doubleValue() + 1
                && ((Number) measured.get("scrollHeight")).doubleValue() <= ((Number) measured.get("clientHeight")).doubleValue() + 1,
                "actual spark font/lines/no-clipping " + JsonWriter.write(measured));
    }

    /** Проверяет обычные пункты и separator по токенам, не ограничивая custom-содержимое. */
    private void verifyMenuMetrics(Path output, String url) throws Exception {
        prepareFontModels();
        try (var browser = BrowserBridge.start(output.resolve("menu-browser"), 1200, url);
             var cdp = browser.connect()) {
            cdp.waitFor("!!window.cpParityTestApi", Duration.ofSeconds(20));
            cdp.evaluate("document.querySelector('#menuBar [data-cp-id=file] > button').click()");
            cdp.evaluate("(()=>{const node=document.querySelector('#menuBar [data-cp-id=\"file.save\"] > button');node.dispatchEvent(new PointerEvent('pointerenter'));node.dispatchEvent(new PointerEvent('pointerover',{bubbles:true}));})()");
            cdp.waitFor("!!document.querySelector('.tooltip')", Duration.ofSeconds(5));
            check(cdp.evaluate("""
                    (()=>{const token=parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--cp-spacing'));
                      const height=parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--cp-control-height'));
                      const panel=document.querySelector('#menuBar [data-cp-id=file] > .menu-panel');const trigger=panel.parentElement.querySelector(':scope > button');
                      const item=panel.querySelector('[data-cp-id="file.save"] > button');const box=item.getBoundingClientRect();const tip=document.querySelector('.tooltip').getBoundingClientRect();
                      return [...panel.querySelectorAll(':scope > .menu-node > button')].every(node=>node.getBoundingClientRect().height===height)
                        && [...panel.querySelectorAll(':scope > [data-kind=Separator]')].every(node=>node.getBoundingClientRect().height===2*token+1)
                        && panel.getBoundingClientRect().left===trigger.getBoundingClientRect().left && panel.getBoundingClientRect().top===trigger.getBoundingClientRect().bottom
                        && tip.left===box.left+token && tip.top===box.bottom+token;
                    })()
                    """), "actual standard menu rows, separators, trigger and whole-widget tooltip anchor use shared tokens");
            Files.write(output.resolve("menu-save.png"), cdp.captureScreenshot());
            cdp.evaluate("document.querySelector('#menuBar [data-cp-id=view] > button').click()");
            check(cdp.evaluate("""
                    (()=>{const node=document.querySelector('[data-cp-id="view.horizonSlider"]');const input=node.querySelector('input');const label=node.querySelector('.menu-label');
                      const box=node.getBoundingClientRect();const css=getComputedStyle(node);return box.height>28
                        && label.getBoundingClientRect().top>=box.top && input.getBoundingClientRect().bottom<=box.bottom
                        && css.overflowY!=='hidden' && input.getBoundingClientRect().height>0;
                    })()
                    """), "custom slider keeps intrinsic multiline content height without clipping");
            Files.writeString(output.resolve("menu-custom.json"), JsonWriter.write(cdp.evaluate("(()=>{const root=document.querySelector('[data-cp-id=\"view.horizonSlider\"]');return {root:root.getBoundingClientRect().toJSON(),label:root.querySelector('.menu-label').getBoundingClientRect().toJSON(),input:root.querySelector('input').getBoundingClientRect().toJSON(),font:getComputedStyle(root).font};})()")), StandardCharsets.UTF_8);
            cdp.evaluate("document.querySelector('#summary [data-cp-id=m3]').dispatchEvent(new MouseEvent('contextmenu',{bubbles:true,cancelable:true,clientX:300,clientY:100}))");
            cdp.waitFor("!!document.querySelector('.context-menu:not([hidden]) .menu-label')", Duration.ofSeconds(5));
            cdp.evaluate("window.dumpMenuNode=document.querySelector('.context-menu:not([hidden])'); window.dumpMenuFocus=document.activeElement; window.dumpMenuBounds=JSON.stringify(window.dumpMenuNode.getBoundingClientRect().toJSON()); window.dumpScrollTop=document.querySelector('.table-scroll').scrollTop");
            check(cdp.evaluate("""
                    window.cpParityTestApi.dump({step:'context-shot'}).then(reply=>{
                      window.contextShotDump=reply.value;
                      return reply.value.contextMenus.length===1 && window.dumpMenuNode.isConnected
                        && !window.dumpMenuNode.hidden && document.activeElement===window.dumpMenuFocus
                        && JSON.stringify(window.dumpMenuNode.getBoundingClientRect().toJSON())===window.dumpMenuBounds
                        && document.querySelector('.table-scroll').scrollTop===window.dumpScrollTop;
                    })
                    """), "raw dump preserves actual context menu, focus, geometry and table scroll before PNG");
            Files.writeString(output.resolve("context-shot.raw.json"), JsonWriter.write(cdp.evaluate("window.contextShotDump")), StandardCharsets.UTF_8);
            Files.write(output.resolve("context-shot.png"), cdp.captureScreenshot());
            check(cdp.evaluate("window.dumpMenuNode.isConnected && !window.dumpMenuNode.hidden && document.activeElement===window.dumpMenuFocus"), "PNG capture preserves the dumped context menu");
            check(cdp.evaluate("window.cpParityTestApi.dump({step:'context-shot-repeat'}).then(reply=>reply.value.contextMenus.length===1 && document.querySelector('.context-menu:not([hidden])')===window.dumpMenuNode)"), "repeated observation preserves the same menu node");
            long incomeKeys = intents.stream().filter(intent -> "key".equals(intent.get("type"))
                    && "I".equals(Json.object(intent, "chord").get("key"))).count();
            cdp.evaluate("document.activeElement.dispatchEvent(new KeyboardEvent('keydown',{code:'KeyI',key:'i',ctrlKey:true,bubbles:true,cancelable:true})); window.cpParityTestApi.awaitIdle({timeoutMs:5000})");
            assertIncomeKeyOnce(incomeKeys);
            check(cdp.evaluate("window.dumpMenuNode.hidden"), "global shortcut closes the observed menu through keyboard handling, not dump");
            System.out.println("ACTUAL MENU METRICS GREEN standard/custom/separator/tooltip/trigger/context-shot");
        }
    }

    /** Проверяет единственное намерение Ctrl+I из настоящего открытого меню. */
    private void assertIncomeKeyOnce(long before) {
        long after = intents.stream().filter(intent -> "key".equals(intent.get("type"))
                && "I".equals(Json.object(intent, "chord").get("key"))).count();
        check(after == before + 1, "open-menu global shortcut sends exactly one core intent");
    }

    /** Проверяет строки MULTILINE, сохранённую RAW-геометрию и независимый резерв проблем на живых полях. */
    private void verifyMultilineMetrics(Path output, String url) throws Exception {
        prepareFontModels();
        var window = Json.object(fixtures.get(1), "window");
        var spec = Json.object(window, "spec");
        var page = Json.asObject(((List<?>) spec.get("pages")).getFirst(), "page");
        var rows = new ArrayList<Object>((List<?>) page.get("rows"));
        var view = Json.object(window, "view");
        var fields = Json.object(view, "fields");
        for (int count : new int[] {2, 3}) {
            String id = "multiline" + count;
            rows.add(UiJson.toTree(new FormRow.Field(ru.cashprediction.core.ui.form.FieldSpecs.multiline(id, id, count))));
            fields.put(id, UiJson.toTree(FieldView.of(String.join("\n", java.util.Collections.nCopies(count, "Agyp")))));
        }
        page.put("rows", rows);
        window.put("placement", Map.of("ownerId", "main", "bounds", Map.of("x", 180, "y", 140, "width", 600, "height", 600)));
        try (var browser = BrowserBridge.start(output.resolve("multiline-browser"), 1200, url);
             var cdp = browser.connect()) {
            cdp.waitFor("!!window.cpParityTestApi", Duration.ofSeconds(20));
            cdp.evaluate("window.cpParityTestApi.execute({kind:'Menu',args:{idOrPath:'file.rename'}})");
            cdp.evaluate("window.cpParityTestApi.awaitIdle({timeoutMs:5000})");
            String measurements = """
                    (()=>{const root=document.querySelector('dialog[open]');const box=node=>node.getBoundingClientRect().toJSON();
                    return {raw:box(root),problem:box(root.querySelector('.problem')),fields:[...root.querySelectorAll('textarea[data-kind=MULTILINE]')].map(node=>{
                      const css=getComputedStyle(node);const line=parseFloat(css.lineHeight);const canvas=document.createElement('canvas');const context=canvas.getContext('2d');
                      context.font=css.font;const text=context.measureText('Agyp');return {id:node.dataset.cpId,rows:node.rows,box:box(node),line,
                        paddingTop:css.paddingTop,paddingBottom:css.paddingBottom,paddingLeft:css.paddingLeft,paddingRight:css.paddingRight,
                        clientHeight:node.clientHeight,scrollHeight:node.scrollHeight,glyphHeight:text.actualBoundingBoxAscent+text.actualBoundingBoxDescent};})};})()
                    """;
            Files.writeString(output.resolve("multiline-metrics.json"), JsonWriter.write(cdp.evaluate(measurements)), StandardCharsets.UTF_8);
            check(cdp.evaluate("(()=>{const root=document.querySelector('dialog[open]');window.multilineRaw=root.getBoundingClientRect().toJSON();return window.multilineRaw.x===180 && window.multilineRaw.y===140 && window.multilineRaw.width===600 && window.multilineRaw.height===600 && [...root.querySelectorAll('textarea[data-kind=MULTILINE]')].every(node=>{const css=getComputedStyle(node);const line=parseFloat(css.lineHeight);const height=node.getBoundingClientRect().height;return height>0 && css.paddingTop==='0px' && css.paddingBottom==='0px' && css.paddingLeft==='6px' && css.paddingRight==='6px' && height===node.rows*line+2 && node.clientHeight>=node.rows*line && node.scrollHeight<=node.clientHeight;});})()"), "actual multiline rows without clipping or extra vertical padding; RAW restoration unchanged");
            check(cdp.evaluate("""
                    (()=>{const root=document.querySelector('dialog[open]');return [...root.querySelectorAll('textarea[data-kind=MULTILINE]')].every(node=>{
                      const css=getComputedStyle(node);const canvas=document.createElement('canvas');const context=canvas.getContext('2d');context.font=css.font;
                      const text=context.measureText('Agyp');return text.actualBoundingBoxAscent+text.actualBoundingBoxDescent<=parseFloat(css.lineHeight);
                    }) && !root.querySelector('.problem').hidden && root.querySelector('.problem').getBoundingClientRect().height===32;})()
                    """), "actual glyphs fit reserved font lines and problem keeps 32 pixels");
            synchronized (this) {
                view.put("problem", Map.of("severity", "NONE", "text", ""));
                view.put("revision", ((Number) view.get("revision")).longValue() + 1);
                add(Map.of("type", "form.view", "windowId", "w1", "view", new LinkedHashMap<>(view)));
            }
            cdp.waitFor("document.querySelector('dialog[open] .problem')?.textContent===''", Duration.ofSeconds(5));
            check(cdp.evaluate("(()=>{const root=document.querySelector('dialog[open]');const box=root.getBoundingClientRect();const problem=root.querySelector('.problem');return !problem.hidden && problem.getBoundingClientRect().height===32 && ['x','y','width','height'].every(key=>box[key]===window.multilineRaw[key]);})()"), "valid form keeps problem reserve and exact restored RAW bounds");
            Files.write(output.resolve("multiline.png"), cdp.captureScreenshot());
            cdp.evaluate("window.cpParityTestApi.execute({kind:'Cancel',args:{window:'w1'}})");
            cdp.evaluate("window.cpParityTestApi.execute({kind:'Menu',args:{idOrPath:'help.hotkeys'}})");
            cdp.waitFor("!!document.querySelector('.alert-window .details-text:not([hidden])')", Duration.ofSeconds(5));
            check(cdp.evaluate("(()=>{const node=document.querySelector('.alert-window .details-text');const css=getComputedStyle(node);return css.paddingTop==='3px' && css.paddingBottom==='3px';})()"), "alert details retain their independent padding");
            System.out.println("ACTUAL MULTILINE METRICS GREEN 2/3 rows; problem/RAW/details preserved");
        }
    }

    /** Публикует результат и ошибку через обычное обновление формы фикстуры. */
    private synchronized void resultView(List<Map<String, String>> lines) {
        var view = Json.object(Json.object(fixtures.get(1), "window"), "view");
        view.put("results", lines);
        view.put("problem", Map.of("severity", lines.isEmpty() ? "ERROR" : "WARNING", "text", "fixture result state"));
        view.put("revision", ((Number) view.get("revision")).longValue() + 1);
        add(Map.of("type", "form.view", "windowId", "w1", "view", new LinkedHashMap<>(view)));
    }

    /** Проверяет только историю реально показанных обозревателей, включая отмену и повторный bootstrap. */
    private void verifyChooserHistory(Path output, String url) throws Exception {
        chooserHistoryMode = true;
        try (var browser = BrowserBridge.start(output.resolve("chooser-browser"), 1200, url);
             var cdp = browser.connect()) {
            cdp.waitFor("!!window.cpParityTestApi", Duration.ofSeconds(20));
            cdp.evaluate("window.cpParityTestApi.awaitIdle({timeoutMs:5000})");
            chooser("chooser-no-metadata", null);
            cdp.waitFor("document.querySelector('dialog[data-cp-id=chooser-no-metadata]')?.open", Duration.ofSeconds(5));
            check(cdp.evaluate("window.cpParityTestApi.dump({step:'no-metadata'}).then(reply=>reply.value.chooserRequests.length===0)"), "missing metadata does not invent a request");
            cdp.evaluate("window.cpParityTestApi.execute({kind:'Cancel',args:{window:'chooser-no-metadata'}})");
            var open = Map.of("kind", "FILE", "mode", "OPEN", "title", "fixture open", "filter", "*.md", "folder", "C:/fixture/", "name", "");
            cdp.evaluate("window.cpParityTestApi.execute({kind:'Chooser',args:{path:null}})");
            check(cdp.evaluate("window.cpParityTestApi.execute({kind:'Chooser',args:{path:'C:/second.md'}}).then(()=>false,error=>error.message==='Chooser answer already pending')"), "second pending answer rejected");
            chooser("chooser1", open);
            cdp.waitFor("window.cpParityTestApi.awaitIdle({timeoutMs:5000}).then(()=>fetch('/api/test/counters',{headers:{'X-Token':'fixture'}})).then(response=>response.json()).then(reply=>reply.counters['fixture.chooser.answers']===2)", Duration.ofSeconds(5));
            check(cdp.evaluate("window.cpParityTestApi.dump({step:'cancelled'}).then(reply=>reply.value.chooserRequests.length===1 && reply.value.windows.length===0 && Object.entries(" + JsonWriter.write(open) + ").every(([key,value])=>reply.value.chooserRequests[0][key]===value))"), "cancelled actual chooser retains received provenance");
            var save = Map.of("kind", "FILE", "mode", "SAVE", "title", "fixture save", "filter", "*.md", "folder", "C:/fixture/", "name", "initial.md");
            var repeated = chooser("chooser2", save);
            cdp.waitFor("document.querySelector('dialog[data-cp-id=chooser2]')?.open", Duration.ofSeconds(5));
            int count;
            synchronized (this) { bootstrap.put("windows", List.of(repeated)); count = bootstrapCount; resyncOnce = true; }
            cdp.waitFor("document.querySelector('dialog[data-cp-id=chooser2]')?.open && window.cpParityTestApi.dump({step:'resynced'}).then(reply=>reply.value.chooserRequests.length===2)", Duration.ofSeconds(5));
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline) {
                synchronized (this) { if (bootstrapCount > count) break; }
                Thread.sleep(20);
            }
            synchronized (this) { check(bootstrapCount > count, "actual repeated bootstrap received"); bootstrap.put("windows", List.of()); }
            cdp.evaluate("window.cpParityTestApi.awaitIdle({timeoutMs:5000})");
            check(cdp.evaluate("window.cpParityTestApi.dump({step:'deduplicated'}).then(reply=>reply.value.chooserRequests.length===2 && Object.entries(" + JsonWriter.write(save) + ").every(([key,value])=>reply.value.chooserRequests[1][key]===value))"), "bootstrap deduplicates by real window id");
            cdp.evaluate("window.cpParityTestApi.execute({kind:'Chooser',args:{path:'C:/fixture/queued.md'}})");
            cdp.waitFor("window.cpParityTestApi.awaitIdle({timeoutMs:5000}).then(()=>fetch('/api/test/counters',{headers:{'X-Token':'fixture'}})).then(response=>response.json()).then(reply=>reply.counters['fixture.chooser.answers']===3)", Duration.ofSeconds(5));
            check(intents.stream().anyMatch(intent -> "chooser2".equals(intent.get("windowId")) && "name".equals(intent.get("fieldId")) && "queued.md".equals(intent.get("raw"))), "queued path enters actual field");
            Object dump = cdp.evaluate("window.cpParityTestApi.dump({step:'chooser-history'}).then(reply=>reply.value)");
            Files.writeString(output.resolve("chooser-history.json"), JsonWriter.write(dump), StandardCharsets.UTF_8);
            check(cdp.evaluate("window.cpParityTestApi.dump({step:'closed-history'}).then(reply=>reply.value.windows.length===0 && reply.value.chooserRequests.length===2)"), "history survives both actual closures");
            System.out.println("ACTUAL CHOOSER HISTORY OK");
        }
    }

    private void handle(HttpExchange exchange) {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/app.html")) { bytes(exchange, "text/html", resourceBytes("app.html")); return; }
            if (path.equals("/app/tokens.css")) { bytes(exchange, "text/css", TokenCss.webCss().getBytes(StandardCharsets.UTF_8)); return; }
            if (path.startsWith("/app/")) {
                Path file = root.resolve("web/src/main/resources/web").resolve(path.substring(1)).normalize();
                if (!file.startsWith(root.resolve("web/src/main/resources/web/app"))) throw new IllegalArgumentException(path);
                bytes(exchange, path.endsWith(".css") ? "text/css" : "text/javascript", resourceBytes(path.substring(1))); return;
            }
            if (path.equals("/api/ui/bootstrap")) {
                synchronized (this) { bootstrapCount++; bootstrap.put("seq", seq); queueStep = !chooserHistoryMode || bootstrapCount == 1; slowBootstrapRows = true; earlyStep = false; json(exchange, bootstrap); } return;
            }
            if (path.equals("/api/ui/events")) {
                Thread.sleep(150);
                long after = Long.parseLong(java.util.Arrays.stream(exchange.getRequestURI().getQuery().split("&")).filter(part -> part.startsWith("after=")).findFirst().orElseThrow().substring(6));
                synchronized (this) {
                    if (resyncOnce) { resyncOnce = false; json(exchange, Map.of("resync", true)); return; }
                    if (queueStep) { queueStep = false; add(Map.of("type", "test.step", "n", 17, "command", "sample")); }
                    json(exchange, Map.of("seq", seq, "effects", effects.stream().filter(effect -> ((Number) effect.get("seq")).longValue() > after).toList()));
                } return;
            }
            if (path.equals("/api/test/counters")) {
                if (!"fixture".equals(exchange.getRequestHeaders().getFirst("X-Token"))) throw new IllegalArgumentException("test token");
                json(exchange, Map.of("counters", Map.of("fixture.server.snapshot", 7, "fixture.chooser.answers", intents.stream().filter(intent -> String.valueOf(intent.get("windowId")).startsWith("chooser") && "formButton".equals(intent.get("type"))).count(), "fixture.select.answers", intents.stream().filter(intent -> "selectRow".equals(intent.get("type"))).count()))); return;
            }
            if (!path.startsWith("/api/")) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
            var body = Json.asObject(JsonParser.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)), "request");
            if (path.equals("/api/ui/query")) {
                if (fontState != null) {
                    var chart = ru.cashprediction.core.ui.view.chart.ChartLayout.model(fontState, 7);
                    Object result = switch (Json.requireString(body, "type")) {
                        case "sparkline" -> sparklineFixture != null ? sparklineFixture : ru.cashprediction.core.ui.view.popup.PopupBuilders.sparkline(fontState, Json.requireString(body, "cardId"));
                        case "contextMenu" -> ru.cashprediction.core.ui.menu.MenuModels.contextMenu(fontState,
                                new ru.cashprediction.core.ui.menu.ContextTarget.Card(Json.requireString(Json.object(body, "target"), "cardId")), ru.cashprediction.core.app.ClientKind.WEB);
                        case "chartScene" -> chart.layout(((Number) body.get("w")).doubleValue(), ((Number) body.get("h")).doubleValue());
                        case "chartHover" -> chart.hover(((Number) body.get("x")).doubleValue(), ((Number) body.get("y")).doubleValue(),
                                ((Number) body.get("w")).doubleValue(), ((Number) body.get("h")).doubleValue()).orElse(null);
                        default -> null;
                    };
                    if (result != null) { json(exchange, Map.of("result", UiJson.toTree(result))); return; }
                }
                if ("rows".equals(body.get("type"))) {
                    boolean delay;
                    synchronized (this) { delay = slowBootstrapRows; slowBootstrapRows = false; if (delay) bootstrapRowsLoading = true; }
                    if (delay) Thread.sleep(1000);
                    synchronized (this) { if (delay) bootstrapRowsLoading = false; }
                    int from = ((Number) body.get("from")).intValue(), count = ((Number) body.get("count")).intValue();
                    var rows = new ArrayList<>();
                    for (int index = from; index < Math.min(311, from + count); index++) rows.add(Map.of("rowId", "fixture-" + index, "kind", "RULE", "cells", List.of("13.09.2026", "fixture", "fixture-" + index, "", "1 000,00", "", "1 000,00", ""), "rowStyle", Map.of("text", "TEXT_PRIMARY", "bold", false, "italic", false), "cellStyles", Map.of(), "leadingSpan", 1));
                    json(exchange, Map.of("result", rows));
                } else json(exchange, Map.of("result", ""));
                return;
            }
            if (path.equals("/api/ui/intent")) {
                synchronized (this) {
                    var intent = Json.object(body, "intent"); intents.add(intent);
                    if (queueStep && "mainGeometry".equals(intent.get("type"))) {
                        earlyStep = bootstrapRowsLoading;
                        queueStep = false; add(Map.of("type", "test.step", "n", 17, "command", "sample"));
                    }
                    if ("formField".equals(intent.get("type")) && "money".equals(intent.get("fieldId"))) {
                        var window = Json.object(fixtures.get(1), "window");
                        var view = Json.object(window, "view");
                        String raw = Json.requireString(intent, "raw");
                        String value = Boolean.TRUE.equals(intent.get("committed"))
                                ? FieldCodec.display(FieldKind.MONEY, FieldCodec.canonical(FieldKind.MONEY, raw)) : raw;
                        Json.object(view, "fields").put("money", UiJson.toTree(FieldView.of(value)));
                        view.put("revision", ((Number) view.get("revision")).longValue() + 1);
                        add(Map.of("type", "form.view", "windowId", intent.get("windowId"), "view", new LinkedHashMap<>(view),
                                "echoOf", Map.of("tab", body.get("tab"), "clientRev", intent.get("clientRev"))));
                    }
                    if ("file.rename".equals(intent.get("command"))) add(fixtures.get(1));
                    if ("help.about".equals(intent.get("command"))) add(fixtures.get(5));
                    if (fontState != null && "view.chart".equals(intent.get("command"))) add(Map.of("type", "screen", "revision", 7, "parts", Map.of("MODE", "CHART")));
                    if (fontState != null && "help.hotkeys".equals(intent.get("command"))) add(Map.of("type", "alert.open", "alertId", "font-help",
                            "spec", UiJson.toTree(ru.cashprediction.core.ui.alert.AlertCatalog.hotkeys(ru.cashprediction.core.ui.command.HotkeyTable.text()))));
                    if ("formClose".equals(intent.get("type"))) add(Map.of("type", "form.close", "windowId", intent.get("windowId")));
                    if ("formButton".equals(intent.get("type"))) add(Map.of("type", "form.close", "windowId", intent.get("windowId")));
                    if ("alertButton".equals(intent.get("type"))) add(Map.of("type", "alert.close", "alertId", intent.get("alertId")));
                    if ("clientError".equals(intent.get("type"))) throw new AssertionError(intent.toString());
                    long after = ((Number) body.get("afterSeq")).longValue();
                    json(exchange, Map.of("seq", seq, "effects", effects.stream().filter(effect -> ((Number) effect.get("seq")).longValue() > after).toList()));
                } return;
            }
            exchange.sendResponseHeaders(404, -1); exchange.close();
        } catch (Throwable error) {
            error.printStackTrace(); try { exchange.sendResponseHeaders(500, -1); } catch (Exception ignored) { } exchange.close();
        }
    }

    /** Проверяет минимальную прокрутку реальных действий и общие координаты header/body. */
    private void verifyTableAnchors(Path output, String url) throws Exception {
        try (var browser = BrowserBridge.start(output.resolve("anchor-browser"), 1200, url);
             var cdp = browser.connect()) {
            cdp.waitFor("!!window.cpParityTestApi", Duration.ofSeconds(20));
            cdp.evaluate("window.cpParityTestApi.awaitIdle({timeoutMs:5000})");
            cdp.evaluate("window.anchorInitial=document.querySelector('.table-scroll').scrollTop");
            cdp.evaluate("window.cpParityTestApi.execute({kind:'Select',args:{rowId:'fixture-3'}})");
            check(cdp.evaluate("document.querySelector('.table-scroll').scrollTop===window.anchorInitial"), "already visible row does not move to viewport top");
            cdp.evaluate("window.cpParityTestApi.execute({kind:'Select',args:{rowId:'fixture-100'}})");
            check(cdp.evaluate("(()=>{const scroll=document.querySelector('.table-scroll');const row=document.querySelector('.table-row[data-cp-id=fixture-100]');return row && Math.abs(scroll.scrollTop-(101*26-scroll.clientHeight))<0.1;})()"), "offscreen row minimally aligns its bottom with viewport");
            cdp.evaluate("window.anchorVisible=document.querySelector('.table-scroll').scrollTop");
            cdp.evaluate("window.cpParityTestApi.execute({kind:'Select',args:{rowId:'fixture-99'}})");
            check(cdp.evaluate("document.querySelector('.table-scroll').scrollTop===window.anchorVisible"), "another fully visible row preserves viewport");
            Object measured = cdp.evaluate("(()=>{const box=node=>node.getBoundingClientRect().toJSON();const row=document.querySelector('.table-row[data-cp-id=fixture-99]');const scroll=document.querySelector('.table-scroll');return {scrollTop:scroll.scrollTop,viewport:box(scroll),clientWidth:scroll.clientWidth,offsetWidth:scroll.offsetWidth,columns:[...document.querySelector('.table-header').children].map(header=>({id:header.dataset.cpId,header:box(header),cell:box([...row.children].find(cell=>cell.dataset.cpId===header.dataset.cpId))}))};})()");
            Files.writeString(output.resolve("actual-table-anchors.json"), JsonWriter.write(measured), StandardCharsets.UTF_8);
            check(cdp.evaluate("(()=>{const row=document.querySelector('.table-row[data-cp-id=fixture-99]');return [...document.querySelector('.table-header').children].every(header=>{const cell=[...row.children].find(cell=>cell.dataset.cpId===header.dataset.cpId);return Math.abs(header.getBoundingClientRect().x-cell.getBoundingClientRect().x)<0.1&&Math.abs(header.getBoundingClientRect().width-cell.getBoundingClientRect().width)<0.1&&cell.getBoundingClientRect().height===row.getBoundingClientRect().height;});})()"), "all actual header/cell x and widths agree with scrollbar; empty/nonempty cells fill row height");
            long selected = intents.stream().filter(intent -> "selectRow".equals(intent.get("type"))).count();
            tableSelection("fixture-99");
            synchronized (this) { add(Map.of("type", "reveal", "rowId", "fixture-99", "mode", "SELECT_AND_SCROLL")); }
            cdp.waitFor("document.getElementById('table').dataset.selectedRowId==='fixture-99'", Duration.ofSeconds(5));
            cdp.evaluate("window.cpParityTestApi.awaitIdle({timeoutMs:5000})");
            check(cdp.evaluate("document.querySelector('.table-scroll').scrollTop===window.anchorVisible"), "SELECT_AND_SCROLL effect preserves visible row position");
            check(intents.stream().filter(intent -> "selectRow".equals(intent.get("type"))).count() == selected, "core selection effect does not send a duplicate user intent");
            selected = intents.stream().filter(intent -> "selectRow".equals(intent.get("type"))).count();
            cdp.evaluate("document.getElementById('table').dispatchEvent(new KeyboardEvent('keydown',{code:'ArrowDown',bubbles:true,cancelable:true}))");
            cdp.waitFor("fetch('/api/test/counters',{headers:{'X-Token':'fixture'}}).then(response=>response.json()).then(reply=>reply.counters['fixture.select.answers']>" + selected + ")", Duration.ofSeconds(5));
            check(intents.stream().filter(intent -> "selectRow".equals(intent.get("type"))).reduce((first, last) -> last).orElseThrow().get("rowId").equals("fixture-100"), "keyboard selects next row through actual intent");
            check(cdp.evaluate("document.querySelector('.table-scroll').scrollTop===window.anchorVisible"), "keyboard navigation preserves fully visible target position");
            selected = intents.stream().filter(intent -> "selectRow".equals(intent.get("type"))).count();
            synchronized (this) { add(Map.of("type", "reveal", "rowId", "fixture-100", "mode", "SCROLL_TO_TOP")); }
            cdp.waitFor("document.querySelector('.table-scroll').scrollTop===100*26", Duration.ofSeconds(5));
            check(intents.stream().filter(intent -> "selectRow".equals(intent.get("type"))).count() == selected, "SCROLL_TO_TOP effect leaves selection alone");
            Files.writeString(output.resolve("actual-reveal-modes.json"), JsonWriter.write(cdp.evaluate("({nearest:window.anchorVisible,top:document.querySelector('.table-scroll').scrollTop,selected:document.getElementById('table').dataset.selectedRowId})")), StandardCharsets.UTF_8);
        }
        System.out.println("ACTUAL TABLE ANCHORS GREEN");
    }

    /** Читает production-ресурс из выбранного immutable JAR или исходников при обычной разработке фикстуры. */
    private byte[] resourceBytes(String name) throws Exception {
        String archive = System.getProperty("web.probe.resourceJar");
        if (archive == null) return Files.readAllBytes(root.resolve("web/src/main/resources/web").resolve(name));
        try (var jar = new java.util.jar.JarFile(archive)) {
            var entry = jar.getJarEntry("web/" + name);
            if (entry == null) throw new IllegalArgumentException("Missing JAR resource: " + name);
            try (var input = jar.getInputStream(entry)) { return input.readAllBytes(); }
        }
    }

    /** Проверяет настоящие fallback-значки и обнаружение неверного/отсутствующего значка без доверия spec.glyph. */
    private void verifyAlertGlyphs(Path output, String url) throws Exception {
        try (var browser = BrowserBridge.start(output.resolve("glyph-browser"), 1200, url);
             var cdp = browser.connect()) {
            cdp.waitFor("!!window.cpParityTestApi", Duration.ofSeconds(20));
            cdp.evaluate("window.cpParityTestApi.execute({kind:'Menu',args:{idOrPath:'help.about'}})");
            cdp.evaluate("window.cpParityTestApi.awaitIdle({timeoutMs:5000})");
            var evidence = new ArrayList<Object>();
            for (var kind : ru.cashprediction.core.ui.alert.AlertKind.values()) {
                synchronized (this) { Json.object(fixtures.get(5), "spec").put("kind", kind.name()); }
                alertGlyph("");
                cdp.waitFor("document.querySelector('.alert-window')?.dataset.kind==='" + kind.name() + "'", Duration.ofSeconds(5));
                cdp.evaluate("window.cpParityTestApi.awaitIdle({timeoutMs:5000})");
                check(cdp.evaluate("(async()=>{const {nativeAlertGlyph}=await import('/app/render-alert.js');const root=document.querySelector('.alert-window');const actual=root.querySelector('.window-glyph').textContent;const dump=await window.cpParityTestApi.dump({step:'glyph'});return actual===nativeAlertGlyph(root.dataset.kind) && actual!=='' && dump.value.alerts[0].glyph==='';})()"), "visible native glyph normalized only when correct " + kind);
                evidence.add(cdp.evaluate("window.cpParityTestApi.dump({step:'glyph'}).then(reply=>({kind:document.querySelector('.alert-window').dataset.kind,visible:document.querySelector('.alert-window .window-glyph').textContent,glyph:reply.value.alerts[0].glyph}))"));
                cdp.evaluate("document.querySelector('.alert-window .window-glyph').textContent='wrong-icon'");
                check(cdp.evaluate("window.cpParityTestApi.dump({step:'wrong-glyph'}).then(reply=>reply.value.alerts[0].glyph==='wrong-icon')"), "wrong native icon detected " + kind);
                cdp.evaluate("document.querySelector('.alert-window .window-glyph').remove()");
                check(cdp.evaluate("window.cpParityTestApi.dump({step:'missing-glyph'}).then(reply=>reply.value.alerts[0].glyph==='missing-native-icon:" + kind.name() + "')"), "missing native icon detected " + kind);
            }
            alertGlyph("\u27f2");
            cdp.waitFor("document.querySelector('.alert-window .window-glyph')?.textContent===String.fromCharCode(10226)", Duration.ofSeconds(5));
            check(cdp.evaluate("window.cpParityTestApi.dump({step:'override'}).then(reply=>reply.value.alerts[0].glyph===String.fromCharCode(10226))"), "custom glyph stays actual text");
            Files.writeString(output.resolve("actual-glyphs.json"), JsonWriter.write(evidence), StandardCharsets.UTF_8);
            Files.write(output.resolve("actual-glyph.png"), cdp.captureScreenshot());
        }
        System.out.println("ACTUAL GLYPHS GREEN 4 KINDS + WRONG/MISSING + CUSTOM OVERRIDE");
    }

    /** Измеряет три настоящие раскладки мастера из ядра, проверяя резерв проблемы через общие токены. */
    private void verifyWizardMetrics(Path output, String url) throws Exception {
        prepareFontModels();
        var context = new ru.cashprediction.core.ui.form.FormContext("w1", "main", Map.of(), fontState);
        var logic = new ru.cashprediction.core.ui.forms.plan.NewPlanWizardForm();
        var window = Json.object(fixtures.get(1), "window");
        window.put("spec", UiJson.toTree(logic.spec(context)));
        for (int page = 0; page < 3; page++) {
            window.put("view", UiJson.toTree(logic.evaluate(new ru.cashprediction.core.ui.form.FormState(page, logic.defaults(context)), context)));
            bootstrap.put("windows", List.of(fixtures.get(1)));
            try (var browser = BrowserBridge.start(output.resolve("wizard-" + page), 1200, url);
                 var cdp = browser.connect()) {
                cdp.waitFor("!!window.cpParityTestApi && !!document.querySelector('dialog[open] .form-grid')", Duration.ofSeconds(20));
                cdp.evaluate("window.cpParityTestApi.awaitIdle({timeoutMs:5000})");
                Object measured = cdp.evaluate("(async()=>{const {dialogContentBounds}=await import('/app/dom.js');const root=document.querySelector('dialog[open]');const metrics=node=>{const css=getComputedStyle(node);return {bounds:node.getBoundingClientRect().toJSON(),font:css.font,lineHeight:css.lineHeight,minHeight:css.minHeight,marginTop:css.marginTop,paddingTop:css.paddingTop,paddingBottom:css.paddingBottom,rowGap:css.rowGap};};return {content:dialogContentBounds(root),header:metrics(root.querySelector('.window-header')),body:metrics(root.querySelector('.form-body')),footer:metrics(root.querySelector('.window-buttons')),grid:metrics(root.querySelector('.form-grid')),problem:metrics(root.querySelector('.problem')),hints:[...root.querySelectorAll('.hint')].map(metrics)};})()");
                Files.writeString(output.resolve("wizard-page-" + page + ".json"), JsonWriter.write(measured), StandardCharsets.UTF_8);
                check(cdp.evaluate("(()=>{const root=document.querySelector('dialog[open]');const problem=root.querySelector('.problem');const css=getComputedStyle(problem);const grid=root.querySelector('.form-grid');return !problem.hidden && parseFloat(css.minHeight)===" + (8 * ru.cashprediction.core.ui.token.DesignTokens.SPACING)
                        + " && parseFloat(css.marginTop)===" + ru.cashprediction.core.ui.token.DesignTokens.FORM_VGAP
                        + " && parseFloat(getComputedStyle(grid).rowGap)===" + ru.cashprediction.core.ui.token.DesignTokens.FORM_VGAP + ";})()"), "shared wizard problem reserve and grid gap page " + page);
                Files.write(output.resolve("wizard-page-" + page + ".png"), cdp.captureScreenshot());
            }
        }
        System.out.println("ACTUAL CORE WIZARD METRICS GREEN 3 PAGES");
    }

    /** Проверяет общий POPUP: независимую ширину поля, порядок ошибки и подсказки, скрытие ошибки. */
    private void verifyPopupOrder(Path output, String url) throws Exception {
        var window = Json.object(fixtures.get(1), "window");
        var spec = Json.object(window, "spec"); spec.put("presentation", "POPUP"); spec.put("width", 340); spec.put("glyph", "");
        var page = Json.asObject(((List<?>) spec.get("pages")).getFirst(), "page");
        var field = ru.cashprediction.core.ui.form.FieldSpecs.withWidthPx(ru.cashprediction.core.ui.form.FieldSpecs.money("money", ""), 140);
        page.put("rows", UiJson.toTree(List.of(new FormRow.Field(field), new FormRow.Hint("fixture-hint", ru.cashprediction.core.ui.text.UiText.get("quick.hint")))));
        var view = Json.object(window, "view");
        view.put("problem", Map.of("severity", "ERROR", "text", ru.cashprediction.core.ui.text.UiText.get("quick.error")));
        bootstrap.put("windows", List.of(fixtures.get(1)));
        try (var browser = BrowserBridge.start(output.resolve("popup-browser"), 1200, url);
             var cdp = browser.connect()) {
            cdp.waitFor("!!window.cpParityTestApi && !!document.querySelector('.quick-edit .hint')", Duration.ofSeconds(20));
            cdp.evaluate("window.cpParityTestApi.awaitIdle({timeoutMs:5000})");
            String measurements = "(()=>{const root=document.querySelector('.quick-edit');const box=node=>node.getBoundingClientRect().toJSON();const problem=root.querySelector('.problem');const css=getComputedStyle(root);return {root:box(root),field:box(root.querySelector('input')),problem:box(problem),hint:box(root.querySelector('.hint')),hidden:problem.hidden,fontSize:getComputedStyle(problem).fontSize,paint:{background:css.backgroundColor,border:css.borderColor,borderWidth:css.borderWidth,radius:css.borderRadius,shadow:css.boxShadow},fonts:['.window-header-text','input','.problem','.hint'].map(selector=>{const style=getComputedStyle(root.querySelector(selector));return {selector,family:style.fontFamily,size:style.fontSize,weight:style.fontWeight};})};})()";
            Files.writeString(output.resolve("popup-error.json"), JsonWriter.write(cdp.evaluate(measurements)), StandardCharsets.UTF_8);
            check(cdp.evaluate("(()=>{const root=document.querySelector('.quick-edit');const field=root.querySelector('input').getBoundingClientRect();const problem=root.querySelector('.problem').getBoundingClientRect();const hint=root.querySelector('.hint').getBoundingClientRect();return root.getBoundingClientRect().width===340 && field.width===140 && problem.top-field.bottom===6 && hint.top-problem.bottom===6 && parseFloat(getComputedStyle(root.querySelector('.problem')).fontSize)===" + FontToken.SMALL.sizePx() + ";})()"), "generic popup width and field/error/hint order with shared small font");
            var fonts = List.of(fontExpectation("popup.header", ".quick-edit .window-header-text", FontToken.BASE, true),
                    fontExpectation("popup.field", ".quick-edit input", FontToken.BASE, false),
                    fontExpectation("popup.problem", ".quick-edit .problem", FontToken.SMALL, false),
                    fontExpectation("popup.hint", ".quick-edit .hint", FontToken.SMALL, false));
            check(cdp.evaluate("(()=>{const normalize=value=>value.replace(/[\"'\\s]/g,'').toLowerCase();return " + JsonWriter.write(fonts) + ".every(expected=>{const css=getComputedStyle(document.querySelector(expected.selector));return normalize(css.fontFamily)===normalize(expected.family)&&parseFloat(css.fontSize)===expected.size&&Number(css.fontWeight)===expected.weight;});})()"), "popup header/field/error/hint actual family size weight");
            check(cdp.evaluate("(()=>{const css=getComputedStyle(document.querySelector('.quick-edit'));const rgb=hex=>{const value=parseInt(hex.slice(1),16);return 'rgb('+(value>>16&255)+', '+(value>>8&255)+', '+(value&255)+')';};return css.borderColor===rgb(" + JsonWriter.write(ru.cashprediction.core.ui.token.ColorToken.BORDER_STRONG.css()) + ")&&css.backgroundColor===rgb(" + JsonWriter.write(ru.cashprediction.core.ui.token.ColorToken.BG_SURFACE.css()) + ")&&css.borderWidth==='1px'&&css.borderRadius==='4px'&&css.boxShadow.replace(/\\s/g,'')===" + JsonWriter.write(ru.cashprediction.core.ui.token.ColorToken.SHADOW.css().replace(" ", "") + "0px4px12px0px") + ";})()"), "popup actual border strong radius and shared shadow token");
            Files.write(output.resolve("popup-error.png"), cdp.captureScreenshot());
            synchronized (this) {
                view.put("problem", Map.of("severity", "NONE", "text", ""));
                view.put("revision", ((Number) view.get("revision")).longValue() + 1);
                add(Map.of("type", "form.view", "windowId", window.get("id"), "view", new LinkedHashMap<>(view)));
            }
            cdp.waitFor("document.querySelector('.quick-edit .problem').hidden", Duration.ofSeconds(5));
            cdp.evaluate("window.cpParityTestApi.awaitIdle({timeoutMs:5000})");
            check(cdp.evaluate("(()=>{const root=document.querySelector('.quick-edit');return root.querySelector('.hint').getBoundingClientRect().top-root.querySelector('input').getBoundingClientRect().bottom===6 && root.querySelectorAll('.hint').length===1;})()"), "valid popup has no error reservation and keeps one hint");
            Files.writeString(output.resolve("popup-valid.json"), JsonWriter.write(cdp.evaluate(measurements)), StandardCharsets.UTF_8);
        }
        System.out.println("ACTUAL GENERIC POPUP ORDER GREEN");
    }

    /** Проверяет приоритет пиксельной ширины на обычных полях и совместимость старых метаданных. */
    private void verifyFieldWidths(Path output, String url) throws Exception {
        var window = Json.object(fixtures.get(1), "window");
        var spec = Json.object(window, "spec");
        var page = Json.asObject(((List<?>) spec.get("pages")).getFirst(), "page");
        var rows = new ArrayList<Object>((List<?>) page.get("rows"));
        var fields = Json.object(Json.object(window, "view"), "fields");
        for (String id : List.of("fixedText", "fixedChoice", "legacyColumns", "automatic")) {
            var kind = "fixedChoice".equals(id) ? FieldKind.CHOICE : FieldKind.TEXT;
            var options = kind == FieldKind.CHOICE ? List.of(new Option("value", "fixture option", "", false)) : List.<Option>of();
            var field = new LinkedHashMap<>(Json.asObject(UiJson.toTree(new FieldSpec(id, kind, "fixture width", "", "",
                    options, 0, 0, 0, 31, 0, false, "", Orientation.HORIZONTAL, false)), "field"));
            field.remove("widthPx");
            if (id.startsWith("fixed")) field.put("widthPx", "fixedText".equals(id) ? 140 : 180);
            else if ("legacyColumns".equals(id)) { field.put("widthPx", 0); field.put("columns", 9); }
            else field.put("columns", 0);
            rows.add(Map.of("kind", "Field", "field", field));
            fields.put(id, UiJson.toTree(FieldView.of("value")));
        }
        page.put("rows", rows);
        bootstrap.put("windows", List.of(fixtures.get(1)));
        for (int width : new int[] {1200, 500}) {
            try (var browser = BrowserBridge.start(output.resolve("width-" + width), width, url);
                 var cdp = browser.connect()) {
                cdp.waitFor("!!window.cpParityTestApi && !!document.querySelector('dialog[open] [data-cp-id=fixedText]')", Duration.ofSeconds(20));
                cdp.evaluate("window.cpParityTestApi.awaitIdle({timeoutMs:5000})");
                Object measured = cdp.evaluate("['fixedText','fixedChoice','legacyColumns','automatic'].map(id=>{const node=document.querySelector('dialog[open] [data-cp-id='+id+']');return {id,bounds:node.getBoundingClientRect().toJSON(),width:node.style.width,flex:node.style.flex,computedWidth:getComputedStyle(node).width};})");
                Files.writeString(output.resolve("field-widths-" + width + ".json"), JsonWriter.write(measured), StandardCharsets.UTF_8);
                check(cdp.evaluate("['fixedText','fixedChoice'].every(id=>{const node=document.querySelector('dialog[open] [data-cp-id='+id+']');const style=getComputedStyle(node);return node.getBoundingClientRect().width===(id==='fixedText'?140:180) && style.flexGrow==='0' && style.flexShrink==='0';})"), "positive widthPx overrides columns and flex growth at " + width);
                check(cdp.evaluate("document.querySelector('dialog[open] [data-cp-id=legacyColumns]').style.width==='9ch' && document.querySelector('dialog[open] [data-cp-id=legacyColumns]').style.flex==='' && document.querySelector('dialog[open] [data-cp-id=automatic]').style.width==='' && document.querySelector('dialog[open] [data-cp-id=automatic]').style.flex===''"), "zero and absent widthPx retain legacy layout at " + width);
            }
        }
        System.out.println("ACTUAL FIELD WIDTHS GREEN: text/choice positive, zero/absent legacy at 1200/500");
    }

    private void add(Map<String, Object> fixture) {
        var effect = new LinkedHashMap<>(fixture); effect.put("seq", ++seq); effects.add(effect);
    }

    /** Публикует замену предпросмотра через настоящий HTTP-журнал эффектов фикстуры. */
    private synchronized void preview(List<Map<String, Object>> items) {
        var view = Json.object(Json.object(fixtures.get(1), "window"), "view");
        view.put("preview", items);
        view.put("revision", ((Number) view.get("revision")).longValue() + 1);
        add(Map.of("type", "form.view", "windowId", "w1", "view", new LinkedHashMap<>(view)));
    }

    /** Публикует настоящий обозреватель после заранее поставленного ответа драйвера. */
    private synchronized void chooser() {
        chooser("chooser", null);
    }

    /** Публикует окно с полученной метаинформацией запроса, не вычисляя её из полей. */
    private synchronized Map<String, Object> chooser(String id, Map<String, String> request) {
        var effect = Json.asObject(JsonParser.parse(JsonWriter.write(fixtures.get(1))), "chooser");
        var window = Json.object(effect, "window"); window.put("id", id);
        window.remove("chooserRequest");
        if (request != null) window.put("chooserRequest", request);
        var spec = Json.object(window, "spec"); spec.put("presentation", "FILE_BROWSER");
        var page = Json.asObject(((List<?>) spec.get("pages")).getFirst(), "page");
        page.put("rows", List.of(UiJson.toTree(new FormRow.Field(ru.cashprediction.core.ui.form.FieldSpecs.text("path", "fixture path", ""))),
                UiJson.toTree(new FormRow.Field(ru.cashprediction.core.ui.form.FieldSpecs.text("name", "fixture name", "")))));
        var view = Json.object(window, "view");
        view.put("fields", Map.of("path", UiJson.toTree(FieldView.of("C:/fixture/")), "name", UiJson.toTree(FieldView.of(""))));
        add(effect);
        return effect;
    }

    /** Проверяет явный значок через реальный HTTP-эффект обновления сообщения. */
    private synchronized void alertGlyph(String glyph) {
        var spec = Json.object(fixtures.get(5), "spec"); spec.put("glyph", glyph);
        add(Map.of("type", "alert.update", "alertId", "a1", "spec", new LinkedHashMap<>(spec)));
    }

    /** Передаёт логический выбор таблицы через эффект, не подменяя физические DOM-строки. */
    private synchronized void tableSelection(String rowId) {
        var table = Json.object(Json.object(bootstrap, "screen"), "table");
        table.put("selectedRowId", rowId);
        table.put("revision", ((Number) table.get("revision")).longValue() + 1);
        add(Map.of("type", "screen", "revision", table.get("revision"), "parts", Map.of("TABLE", new LinkedHashMap<>(table))));
    }

    /** Меняет только число физических строк для проверки появления и удаления полосы прокрутки. */
    private synchronized void tableRows(int count) {
        var table = Json.object(Json.object(bootstrap, "screen"), "table"); table.put("rowCount", count);
        table.put("revision", ((Number) table.get("revision")).longValue() + 1);
        add(Map.of("type", "screen", "revision", table.get("revision"), "parts", Map.of("TABLE", new LinkedHashMap<>(table))));
    }

    private static void json(HttpExchange exchange, Object body) throws Exception {
        bytes(exchange, "application/json", JsonWriter.write(body).getBytes(StandardCharsets.UTF_8));
    }

    private static void bytes(HttpExchange exchange, String type, byte[] data) throws Exception {
        exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        exchange.sendResponseHeaders(200, data.length); exchange.getResponseBody().write(data); exchange.close();
    }

    /** Подключает существующий стенд только при ручном запуске, без зависимости Maven-модуля web. */
    static final class BrowserBridge implements AutoCloseable {
        private final AutoCloseable session;
        private BrowserBridge(AutoCloseable session) { this.session = session; }

        /** Запускает Edge через штатный изолирующий стенд. */
        static BrowserBridge start(Path profile, int width, String url) throws Exception {
            profile = profile.resolveSibling(profile.getFileName() + "-" + java.util.UUID.randomUUID());
            Class<?> launcher = Class.forName("ru.cashprediction.parity.browser.EdgeLauncher");
            var executable = (java.util.Optional<?>) launcher.getMethod("findBrowser").invoke(null);
            Object session = launcher.getMethod("startWithViewport", Path.class, Path.class, int.class, int.class, String.class, Duration.class)
                    .invoke(null, executable.orElseThrow(), profile, width, 800, url, Duration.ofSeconds(20));
            return new BrowserBridge((AutoCloseable) session);
        }

        /** Подключает JDK CDP к созданной странице. */
        CdpBridge connect() throws Exception {
            int port = (Integer) session.getClass().getMethod("port").invoke(session);
            var client = Class.forName("ru.cashprediction.parity.browser.CdpClient").getMethod("connectToFirstPage", int.class, Duration.class)
                    .invoke(null, port, Duration.ofSeconds(20));
            return new CdpBridge((AutoCloseable) client);
        }

        /** Завершает всё дерево собственного браузера. */
        @Override public void close() throws Exception { session.close(); }
    }

    /** Типизированная обёртка отражения для трёх разрешённых команд CDP. */
    static final class CdpBridge implements AutoCloseable {
        private final AutoCloseable client;
        private CdpBridge(AutoCloseable client) { this.client = client; }

        /** Предоставляет штатный клиент только локальному сборщику диагностического запуска. */
        Object delegate() { return client; }

        /** Исполняет выражение и ожидает Promise. */
        Object evaluate(String expression) throws Exception { return client.getClass().getMethod("evaluate", String.class).invoke(client, expression); }

        /** Ожидает готовность порта без изменения страницы. */
        void waitFor(String expression, Duration timeout) throws Exception { client.getClass().getMethod("waitFor", String.class, Duration.class).invoke(client, expression, timeout); }

        /** Снимает PNG настоящего viewport. */
        byte[] captureScreenshot() throws Exception { return (byte[]) client.getClass().getMethod("captureScreenshot").invoke(client); }

        /** Закрывает CDP-соединение. */
        @Override public void close() throws Exception { client.close(); }
    }
}
