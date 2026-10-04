package ru.cashprediction.core.session.codec;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.cashprediction.core.markdown.*;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.session.store.*;

/** Схема снимков не меняется: версия ячеек определяется только внутри восстановленного текста плана. */
class PlanCellCodecSessionCompatibilityTest {
    @TempDir Path memory;

    /** Настоящие хранилища и встроенный Web-блок сохраняют старые литералы и новые переносы. */
    @ParameterizedTest
    @CsvSource({"xml,1", "registry,1", "external,1", "embedded,1",
            "xml,2", "registry,2", "external,2", "embedded,2"})
    void restoredPlanValuesSurviveMigrationAndResave(String mode, int version) throws Exception {
        String client = mode.equals("xml") || mode.equals("registry") ? "fx" : "web";
        String note = PlanCellCodecFixtures.NOTE + (version == 2 ? "\nLF\rCR\r\nCRLF" : "");
        Plan expected = PlanCellCodecFixtures.withNotes(note);
        String input = version == 1 ? PlanCellCodecFixtures.V1 : PlanMarkdownWriter.write(expected);
        SessionSnapshot snapshot = SessionSnapshot.of(Instant.parse("2026-09-13T12:00:00Z"), client,
                MainWindowState.empty(), PlanState.dirty(input), List.of());
        SessionSnapshot restored = roundTrip(mode, snapshot);
        assertEquals(snapshot, restored);
        Plan plan = PlanMarkdownReader.read(restored.plan().markdown(), "unused", PlanCellCodecFixtures.TODAY).plan();
        assertEquals(expected, plan);
        assertEquals(note, plan.rules().getFirst().note());
        assertEquals(note, plan.oneTimes().getFirst().note());
        assertEquals(note, plan.adjustments().getFirst().note());
        String migrated = PlanMarkdownWriter.write(plan);
        assertTrue(migrated.contains("- Формат: CashPrediction 2"));
        SessionSnapshot saved = SessionSnapshot.of(snapshot.savedAt(), client, snapshot.main(),
                PlanState.dirty(migrated), snapshot.windows());
        SessionSnapshot again = roundTrip(mode, saved);
        assertEquals(saved, again);
        Plan reopened = PlanMarkdownReader.read(again.plan().markdown(), "unused", PlanCellCodecFixtures.TODAY).plan();
        assertEquals(expected, reopened);
        assertEquals(migrated, PlanMarkdownWriter.write(reopened));
    }

    /** Использует реальный XML, реестр в памяти, внешнее Web-хранилище либо встроенный кодек. */
    private SessionSnapshot roundTrip(String mode, SessionSnapshot snapshot) throws Exception {
        if (mode.equals("embedded")) {
            MarkdownSnapshotCodec codec = new MarkdownSnapshotCodec();
            return codec.decode(codec.encode(snapshot));
        }
        SessionStore store = switch (mode) {
            case "xml" -> XmlSessionStore.inCashMemory(memory, "fx");
            case "registry" -> RegistrySessionStore.inMemory("fx", memory);
            case "external" -> MarkdownSessionStore.inCashMemory(memory);
            default -> throw new IllegalArgumentException(mode);
        };
        store.save(snapshot);
        return store.load().orElseThrow();
    }
}
