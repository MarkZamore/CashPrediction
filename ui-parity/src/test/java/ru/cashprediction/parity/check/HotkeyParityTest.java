package ru.cashprediction.parity.check;

import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Assumptions;
import ru.cashprediction.parity.check.hotkey.HotkeyCases;
import ru.cashprediction.parity.check.hotkey.HotkeyRun;
import ru.cashprediction.parity.check.hotkey.HotkeyAssertions;
import ru.cashprediction.parity.launch.ReactorLayout;
import ru.cashprediction.parity.pipeline.ParityPipeline;

/** Проверяет горячие клавиши выбранных настоящих клиентов; запуск явно включается parity.realClients. */
public final class HotkeyParityTest {
    /** Проверяет, что изменился только нужный счётчик ровно на единицу. */
    public static void exactlyOnce(String command, Map<String, Integer> before, Map<String, Integer> after) {
        HotkeyAssertions.exactlyOnce(command, before, after);
    }
    /** Создаёт независимый запуск каждой привязки, включая отключённые команды и русские DOM-события web. */
    @TestFactory Stream<DynamicTest> realWidgetHotkeys() {
        Assumptions.assumeTrue(Boolean.getBoolean("parity.realClients"),
                "Enable -Dparity.realClients=true for real-widget hotkeys");
        var layout = ReactorLayout.fromSystemProperties();
        return ParityPipeline.clients(System.getProperty("parity.clients", "fx,swing,web")).stream()
                .flatMap(client -> HotkeyCases.select(Boolean.getBoolean("parity.hotkey.residual")
                        ? HotkeyCases.residual(HotkeyCases.forClient(client), client) : HotkeyCases.forClient(client),
                        System.getProperty("parity.hotkeys", "")).stream().map(probe ->
                        DynamicTest.dynamicTest(client + ": " + probe.name(), () ->
                                HotkeyRun.verify(layout, client, probe))));
    }
}
