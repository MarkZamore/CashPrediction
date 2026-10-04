package ru.cashprediction.parity.audit;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import ru.cashprediction.parity.launch.ReactorLayout;

/** Отдельный реальный opt-in gate после удаления legacy и переключения default UI. */
public final class NoLegacyModuleJarsTest {
    /** Проверяет jar четырёх модулей из ReactorLayout, а не src или синтетические фикстуры. */
    @Test @EnabledIfSystemProperty(named = "parity.noLegacy", matches = "true")
    void actualModuleJarsContainNoLegacy() throws Exception {
        var layout = ReactorLayout.fromSystemProperties();
        for (String module : List.of("core", "ui-fx", "ui-swing", "web")) NoLegacyJarAudit.verify(module, layout.moduleJar(module));
    }
}
