package ru.cashprediction.parity.e2e.desktop;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Не допускает снятия Swing-интерфейса до настоящего показа восстановленного Popup. */
@EnabledIfSystemProperty(named = "parity.e2e", matches = "true")
class SwingQuickRecoveryE2ETest {
    /** Оба источника и отказ проверяются через реальные видимые окна и штатные снимки. */
    @ParameterizedTest(name = "Swing quick edit/{0}")
    @ValueSource(strings = {"registry", "xml", "none"})
    void restoredPopupIsShownBeforeFirstShot(String source) throws Exception {
        try (var harness = new DesktopCrashHarness("swing")) {
            harness.run(source, DesktopCrashHarness.Cohort.QUICK);
        }
    }
}
