package ru.cashprediction.parity.e2e.interaction;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.cashprediction.core.ui.text.UiText;

/** Два настоящих экземпляра одной копии: второй не может затереть снимок первого. */
@EnabledIfSystemProperty(named = "parity.e2e", matches = "true")
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class AlreadyRunningE2ETest {
    /** Проверяет выход B, продолжение B без записи и восстановление полей аварийно закрытого A. */
    @ParameterizedTest @ValueSource(strings = {"fx", "swing"})
    void secondInstanceCannotOverwriteFirst(String client) throws Exception {
        try (var test = new InteractionSession(client)) {
            var a = test.launch("first", "key Esc\nsample\nfiltertype A-only-sentinel\nshot a\nsignal hold\n");
            test.barrier(a, "hold");
            var saved = test.committed(a, s -> s.main().filterText().equals("A-only-sentinel"));
            byte[] xml = Files.readAllBytes(test.xml.file());
            var registry = test.registry.load().orElseThrow();
            var marker = test.registry.readMarker().orElseThrow();
            assertEquals(a.pid(), marker.pid());

            var exit = test.launch("second-exit", "shot blocked\nsignal inspect\n"
                    + InteractionSession.answer(UiText.get("button.exit")));
            test.barrier(exit, "inspect");
            test.alert(exit, "blocked", "alreadyRunning");
            test.release(exit, "inspect");
            assertTrue(exit.process().waitFor(30, TimeUnit.SECONDS), "Second instance failed to exit");
            assertEquals(0, exit.process().exitValue());
            test.terminalPrefix(exit);
            assertTrue(a.process().isAlive());
            assertArrayEquals(xml, Files.readAllBytes(test.xml.file()));
            assertEquals(registry, test.registry.load().orElseThrow());
            assertEquals(marker, test.registry.readMarker().orElseThrow());

            var b = test.launch("second-continue", "shot blocked\n"
                    + InteractionSession.answer(UiText.get("button.openWithoutRestore"))
                    + "key Esc\nsample\nfiltertype B-only-sentinel\nmenu recovery.snapshotNow\nshot recording-off\nsignal inspect\n");
            test.barrier(b, "inspect");
            test.alert(b, "blocked", "alreadyRunning");
            test.alert(b, "recording-off", "info.recordingOff");
            assertTrue(test.dump(b, "recording-off").toString().contains("B-only-sentinel"), "Actual second UI did not apply edit");
            assertTrue(a.process().isAlive());
            assertArrayEquals(xml, Files.readAllBytes(test.xml.file()));
            assertEquals(registry, test.registry.load().orElseThrow());
            assertEquals(marker, test.registry.readMarker().orElseThrow());
            test.crash(b);
            test.crash(a);

            var recovered = test.launch("third-recover", "shot recovery\n" + test.restoreXml(saved)
                    + "shot restored\nsignal inspect\n");
            test.barrier(recovered, "inspect");
            test.alert(recovered, "recovery", "crashRecovery");
            assertTrue(test.dump(recovered, "restored").toString().contains("A-only-sentinel"));
            assertFalse(test.dump(recovered, "restored").toString().contains("B-only-sentinel"));
            test.committed(recovered, s -> s.main().filterText().equals("A-only-sentinel"));
        }
    }
}
