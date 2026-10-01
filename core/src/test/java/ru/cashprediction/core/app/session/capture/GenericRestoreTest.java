package ru.cashprediction.core.app.session.capture;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.flow.*;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.session.store.*;
import ru.cashprediction.core.ui.form.FormSession;

/** Настоящий координатор проходит асинхронную цепочку вложенных форм после каждого формата хранения. */
class GenericRestoreTest {
    @TempDir Path home;

    /** Родитель должен получить shown до открытия ребёнка; страница, поля, владельцы и границы сохраняются. */
    @Test void storesRestoreNestedWindowsThroughCoordinator() throws SessionStoreException {
        CaptureContext before = new CaptureContext(home, ClientProfile.swing());
        SessionBridge source = new SessionBridge(before.flow);
        CoreWindowFactory factory = new CoreWindowFactory(before.flow);
        factory.open(new WindowState("old1", WindowType.NEW_PLAN_WIZARD, true, "main",
                new WindowBounds(20, 30, 700, 600), Map.of("page", "2"), Map.of("name", "typed name")),
                "main", window -> { }, reason -> fail(reason));
        factory.open(new WindowState("old2", WindowType.RULE_EDITOR, true, "old1",
                new WindowBounds(50, 60, 800, 700), Map.of("mode", "create"),
                Map.of("title", "unfinished", "amount", "invalid amount", "note", "line1\nline2")),
                "old1", window -> { }, reason -> fail(reason));
        factory.open(new WindowState("old3", WindowType.ALERT, true, "old2", null,
                Map.of("purpose", "deleteRule", "targetId", "r1"), Map.of()),
                "old2", window -> { }, reason -> fail(reason));
        List<WindowState> windows = new ArrayList<>(before.sessions.stream().map(FormSession::captureState).toList());
        windows.add(before.alerts.getFirst().session().captureState());
        SessionSnapshot original = SessionSnapshot.of(Instant.parse("2026-10-01T00:00:00Z"), "swing",
                source.captureMain(), source.capturePlan(), windows);
        for (SessionStore store : List.of(RegistrySessionStore.inMemory("swing", before.environment.cashMemory()),
                before.environment.xmlStore("swing"), before.environment.webStore())) {
            store.save(original);
            CaptureContext after = new CaptureContext(home, ClientProfile.swing());
            after.delayedShow = true;
            SessionBridge bridge = new SessionBridge(after.flow);
            SessionRecorder recorder = new SessionRecorder("swing", List.of(), UiExecutor.direct(), bridge,
                    after.port.scheduler(), Clock.fixed(original.savedAt(), ZoneOffset.UTC), 1234);
            after.recorder = recorder;
            List<RestoreReport> reports = new ArrayList<>();
            new RestoreCoordinator().restore(store.load().orElseThrow(), bridge,
                    new CoreWindowFactory(after.flow), recorder, reports::add);
            assertEquals(1, after.sessions.size());
            assertTrue(reports.isEmpty());
            FormSession parent = after.sessions.getFirst();
            parent.shown();
            assertEquals(2, after.sessions.size());
            assertTrue(reports.isEmpty());
            FormSession child = after.sessions.get(1);
            assertEquals(parent.windowId(), child.ownerId());
            child.shown();
            assertEquals(1, after.alerts.size());
            assertTrue(reports.isEmpty());
            var alert = after.alerts.getFirst().session();
            assertEquals(child.windowId(), alert.ownerId());
            alert.shown();
            alert.shown();
            assertEquals(1, reports.size());
            assertTrue(reports.getFirst().warnings().isEmpty(), reports.getFirst().warnings().toString());
            assertEquals(3, reports.getFirst().windowsRestored());
            assertEquals(2, parent.state().page());
            for (int index = 0; index < windows.size(); index++) {
                WindowState expected = windows.get(index);
                WindowState actual = index < after.sessions.size()
                        ? after.sessions.get(index).captureState() : alert.captureState();
                assertEquals(expected.type(), actual.type());
                assertEquals(expected.modal(), actual.modal());
                assertEquals(expected.bounds(), actual.bounds());
                assertEquals(expected.context(), actual.context());
                assertEquals(expected.fields(), actual.fields());
            }
            recorder.shutdownClean();
            store.clear();
        }
    }
}
