package ru.cashprediction.parity.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.selftest.SelfTestCommand;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.parity.launch.ReactorLayout;
import static org.junit.jupiter.api.Assertions.*;

/** Дополнительные настоящие Web-пробы §10; не объявляют полный allowance-аудит успешным. */
public final class WebAllowanceTest {
    private static final List<String> NAMES = List.of("halt-cancel", "snapshot-absent", "snapshot-prepared",
            "replace-file", "narrow-toolbar", "js-error", "offline", "stopped", "crashed");

    /** Запускает только явно разрешённую headless-выборку на собственных копиях jar. */
    @TestFactory Stream<DynamicTest> realWebAllowanceStates() {
        Assumptions.assumeTrue(Boolean.getBoolean("parity.realClients"), "Enable parity.realClients");
        Assumptions.assumeTrue(System.getProperty("parity.clients", "web").equals("web"), "Web-only probe");
        String selection = System.getProperty("parity.allowance.probes", "");
        List<String> names = selection.isBlank() ? NAMES : List.of(selection.split(",", -1));
        assertTrue(NAMES.containsAll(names)); assertEquals(names.size(), names.stream().distinct().count());
        return names.stream().map(name -> DynamicTest.dynamicTest(name, () -> verify(name)));
    }

    /** Выполняет независимую пробу; ошибка содержит каталог всех исходных наблюдений. */
    private static void verify(String name) throws Exception {
        try (var run = WebAllowanceSession.open(ReactorLayout.fromSystemProperties(), name,
                name.equals("narrow-toolbar") ? 900 : 1200)) {
            try {
                run.execute(new SelfTestCommand.Cancel("last"));
                run.cdp.waitFor("!document.querySelector('dialog[data-kind=NEW_PLAN_WIZARD][open]')", WebAllowanceSession.TIMEOUT);
                run.execute(new SelfTestCommand.Sample());
                assertEquals(1, run.dump("prepared-sample").counters().get("file.sample"));
                switch (name) {
                    case "halt-cancel" -> {
                        run.execute(new SelfTestCommand.Menu("recovery.simulate.halt"));
                        var alert = alert(run.dump("halt-question"), "simulateHalt");
                        assertEquals(UiText.get("alert.halt.web"), alert.header());
                        answer(run, "cancel");
                        assertTrue(run.dump("halt-cancelled").alerts().isEmpty());
                        assertTrue(run.server.process().isAlive());
                    }
                    case "snapshot-absent" -> {
                        run.execute(new SelfTestCommand.Menu("recovery.clear")); answer(run, "clear");
                        run.execute(new SelfTestCommand.Menu("recovery.showLast"));
                        var alert = alert(run.dump("snapshot-absent"), "lastSnapshot");
                        assertEquals(UiText.get("s2.recovery.summary", UiText.get("store.server.title"),
                                UiText.get("s2.recovery.absent")), alert.content());
                        assertTrue(alert.detailsExpanded());
                        Path session = run.output.resolve("home/CashMemory/web-session.md");
                        String heading = UiText.get("s2.recovery.fileBlock", UiText.get("store.server.title"), session, "");
                        assertTrue(alert.details().startsWith(heading));
                        // Recorder может уже записать следующий снимок на диск; проверяем payload снятого сообщения.
                        var document = new ru.cashprediction.core.session.codec.MarkdownSnapshotCodec()
                                .decodeDocument(alert.details().substring(heading.length()));
                        assertNull(document.snapshot(), "Captured details unexpectedly contain a saved snapshot");
                        assertNotNull(document.marker(), "Captured running marker is missing");
                    }
                    case "snapshot-prepared" -> {
                        run.execute(new SelfTestCommand.Save()); run.execute(new SelfTestCommand.Snapshot());
                        assertTrue(Files.isRegularFile(run.output.resolve("home/CashMemory/web-session.md")));
                        run.execute(new SelfTestCommand.Menu("recovery.showLast"));
                        var alert = alert(run.dump("snapshot-prepared"), "lastSnapshot");
                        assertTrue(alert.detailsExpanded());
                        assertTrue(alert.content().startsWith(UiText.get("store.server.title") + ": "));
                        assertFalse(alert.content().contains(UiText.get("s2.recovery.absent")));
                        assertTrue(alert.details().contains("web-session.md"));
                    }
                    case "replace-file" -> {
                        run.execute(new SelfTestCommand.Save());
                        Path file;
                        try (var files = Files.list(run.output.resolve("home/CashMemory"))) {
                            // Служебные reconnect-файлы не являются планами, как и в штатном каталоге.
                            var plans = files.filter(p -> Files.isRegularFile(p)
                                    && p.getFileName().toString().endsWith(".md")
                                    && !ru.cashprediction.core.io.CashMemoryLayout.isServiceFileName(
                                            p.getFileName().toString())).toList();
                            assertEquals(1, plans.size(), "Expected one saved plan fixture");
                            file = plans.getFirst();
                        }
                        byte[] before = Files.readAllBytes(file);
                        run.execute(new SelfTestCommand.Chooser(file)); run.execute(new SelfTestCommand.Menu("file.saveAs"));
                        var alert = alert(run.dump("replace-file"), "replaceFile");
                        assertTrue(alert.buttons().stream().anyMatch(b -> b.id().equals("replace") && b.enabled()));
                        answer(run, "cancel");
                        assertArrayEquals(before, Files.readAllBytes(file));
                    }
                    case "narrow-toolbar" -> {
                        var dump = run.dump("narrow-toolbar");
                        assertEquals(900, dump.frame().contentWidth());
                        assertTrue(dump.toolbar().wrap());
                        assertTrue(dump.toolbar().items().stream().anyMatch(item -> item.row() > 0));
                    }
                    case "js-error" -> {
                        run.cdp.evaluate("setTimeout(() => {throw new Error('allowance-js-error')},0); true");
                        run.cdp.waitFor("!!document.querySelector('dialog[data-purpose=uncaught][open]')", WebAllowanceSession.TIMEOUT);
                        var alert = alert(run.dump("js-error"), "uncaught");
                        assertEquals(List.of("reloadPage", "continueWork"), alert.buttons().stream().map(UiDump.Button::id).toList());
                        assertEquals("allowance-js-error", alert.content());
                        assertTrue(alert.details().contains("allowance-js-error"));
                        answer(run, "continueWork");
                        assertTrue(run.dump("js-continued").alerts().isEmpty());
                        assertTrue(run.server.process().isAlive());
                    }
                    case "offline" -> { run.server.close(); verifyScreen(run, "offline", "offline"); }
                    case "stopped" -> {
                        run.execute(new SelfTestCommand.Save());
                        clickMenuForExit(run, "file.exit"); verifyScreen(run, "stopped", "offline.stopped");
                    }
                    case "crashed" -> {
                        run.execute(new SelfTestCommand.Menu("recovery.simulate.halt"));
                        alert(run.dump("crash-question"), "simulateHalt");
                        clickAlertForExit(run, "halt"); verifyScreen(run, "crashed", "offline.crashed");
                    }
                    default -> throw new IllegalArgumentException(name);
                }
                Files.writeString(run.output.resolve("result.txt"), "PASS " + name + "\n");
            } catch (Exception | AssertionError failure) {
                throw new AssertionError("Web allowance " + name + "; artifacts=" + run.output, failure);
            }
        }
    }

    /** Находит одно настоящее сообщение по purpose. */
    private static UiDump.Alert alert(UiDump dump, String purpose) {
        var alerts = dump.alerts().stream().filter(a -> a.purpose().equals(purpose)).toList();
        assertEquals(1, alerts.size(), "Missing/duplicate alert " + purpose); return alerts.getFirst();
    }

    /** Выбирает настоящий текст кнопки из наблюдения и передаёт его существующему API ответа. */
    private static void answer(WebAllowanceSession run, String id) throws Exception {
        var dump = run.dump("before-answer-" + id);
        var buttons = dump.alerts().stream().flatMap(a -> a.buttons().stream()).filter(b -> b.id().equals(id)).toList();
        assertEquals(1, buttons.size());
        run.execute(new SelfTestCommand.Answer(buttons.getFirst().text()));
    }

    /** Проверяет только реальные поля post-exit DOM-наблюдения, без полной телеметрии. */
    private static void verifyScreen(WebAllowanceSession run, String kind, String prefix) throws Exception {
        Map<String, Object> screen = run.screen(kind);
        AllowanceEvidence.screen(screen, kind);
        assertEquals(UiText.get(prefix + ".title"), screen.get("title"));
        assertEquals(UiText.get(prefix + ".text"), screen.get("text"));
        assertEquals(true, screen.get("open")); assertEquals(true, screen.get("mainInert"));
        assertEquals(kind.equals("offline") ? 1 : 0, ((List<?>) screen.get("buttons")).size());
    }

    /** Открывает предков меню и нажимает живую кнопку без ожидания недоступного после exit сервера. */
    private static void clickMenuForExit(WebAllowanceSession run, String id) {
        run.cdp.evaluate("(async()=>{const n=[...document.querySelectorAll('.menu-node')].find(n=>n.dataset.cpId==="
                + ru.cashprediction.core.ui.json.UiJson.write(id) + ");if(!n)throw Error('Menu missing');"
                + "const ancestors=[];let p=n.parentElement;while(p){if(p.classList.contains('menu-panel'))ancestors.unshift(p);p=p.parentElement;}"
                + "for(const p of ancestors){p.parentElement.querySelector(':scope > button').click();await new Promise(r=>requestAnimationFrame(r));}"
                + "const b=n.querySelector(':scope > button');if(!b||b.disabled)throw Error('Menu disabled');b.click();return true;})()");
    }

    /** Нажимает живую кнопку завершения; не вызывает ядро или showScreen напрямую. */
    private static void clickAlertForExit(WebAllowanceSession run, String id) {
        run.cdp.evaluate("(()=>{const b=[...document.querySelectorAll('dialog[open] button')].find(b=>b.dataset.cpId==="
                + ru.cashprediction.core.ui.json.UiJson.write(id) + ");if(!b||b.disabled)throw Error('Alert button unavailable');b.click();return true;})()");
    }
}
