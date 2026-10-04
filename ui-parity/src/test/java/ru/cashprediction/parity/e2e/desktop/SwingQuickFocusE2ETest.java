package ru.cashprediction.parity.e2e.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Быстрая регрессия настоящего фокуса quick-edit рядом с открытым немодальным калькулятором цели. */
@EnabledIfSystemProperty(named = "parity.e2e", matches = "true")
class SwingQuickFocusE2ETest {
    /** Проверяет ввод, запись, forced death и восстановление; не заменяет полную двадцатисценарную матрицу. */
    @Test
    @Timeout(180)
    void quickEditReceivesFocusAlongsideGoalAndRestoresInvalidText() throws Exception {
        try (var harness = new DesktopCrashHarness("swing")) {
            harness.run("registry", DesktopCrashHarness.Cohort.QUICK);
        }
    }
}
