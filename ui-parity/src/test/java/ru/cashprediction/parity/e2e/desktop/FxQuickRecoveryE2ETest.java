package ru.cashprediction.parity.e2e.desktop;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Проверяет ввод и восстановление Popup после немодального калькулятора в настоящем JavaFX. */
@EnabledIfSystemProperty(named = "parity.e2e", matches = "true")
class FxQuickRecoveryE2ETest {
    /** Сырой ошибочный ввод сохраняется независимо в реестре и XML; отказ очищает оба снимка. */
    @ParameterizedTest(name = "FX quick edit/{0}")
    @ValueSource(strings = {"registry", "xml", "none"})
    void popupAfterModelessCalculatorKeepsTypedState(String source) throws Exception {
        try (var harness = new DesktopCrashHarness("fx")) {
            harness.run(source, DesktopCrashHarness.Cohort.QUICK);
        }
    }
}
