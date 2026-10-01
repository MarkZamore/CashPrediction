package ru.cashprediction.core.app.session.capture;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.flow.*;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.session.codec.XmlSnapshotCodec;
import ru.cashprediction.core.session.store.MarkdownSessionStore;
import ru.cashprediction.core.ui.form.*;

/** Фикстуры S1 из FxWindowFactory, SwingWindowFactory и web SessionApi проходят настоящую фабрику ядра. */
class LegacyFactoryTest {
    @TempDir Path home;

    /** Канонические и некорректные поля каждого прежнего редактора сохраняются вместе с владельцем и границами. */
    @Test void legacyEditorsOpenThroughFactory() throws Exception {
        Path root = Path.of(getClass().getResource("/session/legacy").toURI());
        int tested = 0;
        EnumSet<WindowType> covered = EnumSet.noneOf(WindowType.class);
        java.util.Set<String> alertPurposes = new java.util.HashSet<>();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.getFileName().toString().matches("session-(fx|swing)\\.xml|web-session\\.md")).toList()) {
                SessionSnapshot snapshot = file.toString().endsWith(".xml")
                        ? new XmlSnapshotCodec().decode(Files.readString(file))
                        : new MarkdownSessionStore(file, file.resolveSibling("web-session.plan.md"), "web").load().orElseThrow();
                CaptureContext fake = new CaptureContext(home, ClientProfile.web());
                SessionBridge bridge = new SessionBridge(fake.flow);
                if (snapshot.plan().dirty()) bridge.loadPlan(snapshot.plan(), snapshot.main().planPath(), warning -> { });
                bridge.applyMain(snapshot.main());
                CoreWindowFactory factory = new CoreWindowFactory(fake.flow);
                for (WindowState state : snapshot.windows()) {
                    List<StatefulWindow> shown = new ArrayList<>();
                    List<String> failed = new ArrayList<>();
                    factory.open(state, state.ownerId(), shown::add, failed::add);
                    assertTrue(failed.isEmpty(), file + ": " + failed);
                    assertEquals(1, shown.size(), file.toString());
                    WindowState captured = shown.getFirst().captureState();
                    assertEquals(state.type(), captured.type(), file.toString());
                    assertEquals(state.ownerId(), captured.ownerId(), file.toString());
                    assertEquals(state.bounds(), captured.bounds(), file.toString());
                    assertEquals(state.id(), captured.id(), file.toString());
                    assertEquals(state.modal(), captured.modal(), file.toString());
                    covered.add(state.type());
                    if (state.type() == WindowType.ALERT) {
                        alertPurposes.add(state.contextValue("purpose"));
                        assertEquals(state.contextValue("purpose"), captured.contextValue("purpose"));
                        assertEquals(state.contextValue("targetId"), captured.contextValue("targetId"));
                        tested++;
                        continue;
                    }
                    if (state.context().containsKey("page")) assertEquals(state.contextValue("page"), captured.contextValue("page"));
                    FormRequest request = FormCatalog.forRestore(state, fake.state());
                    FormSession session = (FormSession) shown.getFirst();
                    var normalized = request.logic().normalizeRestoredValues(request.restored().fields(), session.context());
                    for (var entry : state.fields().entrySet()) {
                        String expected = normalized.getOrDefault(entry.getKey(), FieldCodec.acceptLegacy(state.type(), entry.getKey(), entry.getValue()));
                        assertEquals(expected, captured.fields().get(entry.getKey()), file + ": " + entry.getKey());
                    }
                    tested++;
                }
            }
        }
        assertTrue(tested >= 70, "Legacy coverage: " + tested);
        assertEquals(EnumSet.allOf(WindowType.class), covered);
        assertEquals(java.util.Set.of("deleteRule", "deleteOneTime", "actualize", "applyWhatIf", "clearSnapshots"), alertPurposes);
    }
}
