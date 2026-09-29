package ru.cashprediction.core.ui.view.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.DocumentView;
import ru.cashprediction.core.app.RecorderStatus;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.session.StoreStatus;
import ru.cashprediction.core.ui.token.ColorToken;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.view.table.ViewStates;

/** Проверяет восемь сегментов строки состояния, включая web-хранилище и выключенный рекордер. */
class StatusModelTest {

    private static final Instant NOW = Instant.parse("2026-09-13T10:15:30Z");

    @Test
    void defaultStateHasAllSegmentsInTheDocumentedOrder() {
        StatusModel model = StatusBuilder.build(ViewStates.sample(), NOW, ZoneOffset.UTC);
        assertEquals(StatusModel.ORDER, model.segments().stream().map(StatusSegment::id).toList());
        assertTrue(model.find(StatusModel.FILE).orElseThrow().text().startsWith("Файл:"));
        assertFalse(model.find(StatusModel.WHAT_IF).orElseThrow().visible());
        assertFalse(model.find(StatusModel.AUTOSAVE).orElseThrow().visible());
        assertEquals(UiText.get("status.session.none"), model.find(StatusModel.SESSION).orElseThrow().text());
    }

    @Test
    void recordingShowsServerNameAndAStoreFailure() {
        AppState state = copy(ViewStates.sample(), ClientProfile.web(), RecorderStatus.RECORDING,
                List.of(new StoreStatus("server", true, NOW, "")), "", AppSettings.defaults());
        StatusSegment session = StatusBuilder.build(state, NOW, ZoneOffset.UTC).find(StatusModel.SESSION).orElseThrow();
        assertTrue(session.text().contains("сервер"));
        assertTrue(session.text().contains("10:15:30"));

        AppState failed = copy(state, ClientProfile.web(), RecorderStatus.RECORDING,
                List.of(new StoreStatus("server", false, null, "ошибка")), "", AppSettings.defaults());
        StatusSegment broken = StatusBuilder.build(failed, NOW, ZoneOffset.UTC).find(StatusModel.SESSION).orElseThrow();
        assertEquals(ColorToken.EXPENSE, broken.color());
        assertTrue(broken.tooltip().contains("ошибка"));
    }

    @Test
    void disabledRecorderAndAutosaveProblemAreExplicit() {
        AppSettings settings = AppSettings.defaults().withAutosave(true);
        AppState state = copy(ViewStates.sample(), ClientProfile.swing(), RecorderStatus.DISABLED_SECOND_INSTANCE,
                List.of(), "нет доступа", settings);
        StatusModel model = StatusBuilder.build(state, NOW, ZoneOffset.UTC);
        StatusSegment autosave = model.find(StatusModel.AUTOSAVE).orElseThrow();
        assertTrue(autosave.visible());
        assertEquals(ColorToken.WARN, autosave.color());
        assertEquals("нет доступа", autosave.tooltip());
        assertTrue(model.find(StatusModel.SESSION).orElseThrow().text().contains("отключена"));
    }

    private static AppState copy(AppState source, ClientProfile profile, RecorderStatus recorder,
                                 List<StoreStatus> stores, String autosaveProblem, AppSettings settings) {
        return new AppState(source.revision(), profile, source.today(), Path.of("CashMemory"), null,
                new DocumentView(source.document().plan(), source.document().file(), source.document().dirty(),
                        source.document().canUndo(), source.document().undoText(), source.document().canRedo(),
                        source.document().redoText(), source.document().forecast(), source.document().forecastError(),
                        source.document().loadDiagnostics()),
                source.view(), source.selectedRowId(), source.pastExpanded(), settings, recorder, stores,
                source.windows(), source.status(), autosaveProblem);
    }
}
