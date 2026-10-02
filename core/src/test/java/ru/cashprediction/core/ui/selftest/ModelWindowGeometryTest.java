package ru.cashprediction.core.ui.selftest;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.session.WindowBounds;
import static org.junit.jupiter.api.Assertions.*;

/** Эталон не подменяет измерение содержимого сохранённой внешней геометрией окна. */
final class ModelWindowGeometryTest {
    @TempDir Path root;

    /** RAW-координаты сохраняются дословно, но без интерфейса границы содержимого неизвестны. */
    @Test void persistedOuterBoundsAreNotInventedContentMeasurements() {
        var environment = AppEnvironment.from(LaunchOptions.parse("--home", root.toString(),
                "--registry", "memory", "--today", "2026-09-13"));
        var port = new RecordingUiPort(ClientProfile.fx("25"));
        var controller = new AppController(port, environment);
        try {
            controller.start();
            var handle = port.top();
            assertNotNull(handle.form);
            var raw = new WindowBounds(1925, 45, 580, 760);
            handle.placement = Placement.restored(handle.placement.ownerId(), raw);
            assertEquals(raw, handle.form.captureState().bounds());
            var dump = ModelDump.build(port, controller, "geometry", "restored", List.of());
            assertEquals(1, dump.windows().size());
            assertNull(dump.windows().getFirst().bounds());
            assertEquals(raw, handle.form.captureState().bounds());
        } finally { port.scheduler.shutdown(); }
    }
}
