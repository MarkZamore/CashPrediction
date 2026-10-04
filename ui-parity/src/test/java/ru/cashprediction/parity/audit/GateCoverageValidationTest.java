package ru.cashprediction.parity.audit;

import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.ui.selftest.SelfTestScript;
import static org.junit.jupiter.api.Assertions.*;

/** Отрицательные проверки не позволяют засчитать пустые наблюдения или неполный журнал. */
public final class GateCoverageValidationTest {
    @TempDir Path temporary;

    /** Папка XML gate явно связана с Surefire, а не только передана как неиспользуемое свойство. */
    @Test void gateReportDirectoryIsBoundToSurefire() throws Exception {
        Path root = ru.cashprediction.parity.launch.ReactorLayout.fromSystemProperties().root();
        String pom = Files.readString(root.resolve("ui-parity/pom.xml"));
        String script = Files.readString(root.resolve(".github/scripts/Invoke-UiGates.ps1"));
        assertTrue(pom.contains("<reportsDirectory>${parity.reports.directory}</reportsDirectory>"));
        assertTrue(pom.contains("<parity.reports.directory>${project.build.directory}/surefire-reports</parity.reports.directory>"));
        assertTrue(script.contains("-Dparity.reports.directory=$reports"));
        assertFalse(script.contains("-Dsurefire.reportsDirectory="));
    }

    /** Пустая папка не может стать манифестом настоящих свидетельств. */
    @Test void emptyEvidenceFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> FreshAllowanceEvidence.aggregate(temporary, temporary.resolve("out")));
        assertFalse(Files.exists(temporary.resolve("out/evidence-manifest.json")));
    }

    /** DONE без исполнения команд и журнал с одним удалённым подтверждением отклоняются. */
    @Test void truncatedAndForgedJournalFails() throws Exception {
        String scenario = SelfTestScript.SCENARIOS.getFirst();
        Path log = temporary.resolve("selftest.log");
        Files.writeString(log, "SELFTEST DONE\n");
        assertThrows(AssertionError.class, () -> GateCoverageTest.requireJournal(log, scenario));
        var text = new StringBuilder();
        for (var command : SelfTestScript.load(scenario).lines())
            text.append("SELFTEST ").append(command.number()).append(" OK ").append(command.text()).append('\n');
        text.append("SELFTEST DONE\n"); Files.writeString(log, text);
        GateCoverageTest.requireJournal(log, scenario);
        Files.writeString(log, text.toString().replaceFirst(" OK ", " FAIL "));
        assertThrows(AssertionError.class, () -> GateCoverageTest.requireJournal(log, scenario));
    }

    /** Старый файл и чужой каталог не принимаются даже при правдоподобном имени. */
    @Test void staleAndForeignArtifactsFail() throws Exception {
        Path file = temporary.resolve("probe.json"); Files.writeString(file, "{}");
        long started = System.currentTimeMillis();
        Files.setLastModifiedTime(file, FileTime.fromMillis(started - 10000));
        assertThrows(IllegalArgumentException.class, () -> FreshAllowanceEvidence.requireFresh(file, temporary, started));
        Files.setLastModifiedTime(file, FileTime.fromMillis(started));
        FreshAllowanceEvidence.requireFresh(file, temporary, started);
        Path other = Files.createDirectory(temporary.resolve("other"));
        assertThrows(IllegalArgumentException.class, () -> FreshAllowanceEvidence.requireFresh(file, other, started));
    }

    /** Недостающая точка не заменяется дубликатом другого снимка того же клиента. */
    @Test void duplicateVisualPointFailsDespiteCorrectCount() {
        var identities = new ArrayList<String>();
        for (var point : ru.cashprediction.parity.check.visual.VisualPlan.checkpoints("", "first"))
            for (String client : List.of("fx", "swing", "web"))
                identities.add(client + "/" + point.script().name() + "/" + point.step());
        GateCoverageTest.requireVisualIdentities(identities);
        identities.set(3, identities.getFirst());
        assertThrows(AssertionError.class, () -> GateCoverageTest.requireVisualIdentities(identities));
    }

    /** 120 произвольных заголовков и строка зелёного итога не являются покрытием матрицы. */
    @Test void correctReportCountCannotMaskMissingIdentities() {
        String forged = "<h2>unrelated</h2>".repeat(120) + "<p>Failures: 0</p>";
        assertThrows(AssertionError.class, () -> GateCoverageTest.requireReport(forged));
    }

    /** Изменённая замороженная сборка отклоняется независимо от успеха сценарного журнала. */
    @Test void changedFrozenProductionBytesFail() throws Exception {
        Path folder = Files.createDirectory(temporary.resolve("jars"));
        Files.writeString(folder.resolve("cashprediction-core-1.0.0.jar"), "changed");
        var expected = Map.of("cashprediction-core-1.0.0.jar", "0".repeat(64),
                "cashprediction-web-1.0.0.jar", "1".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> FreshAllowanceEvidence.verifyFrozenJars(folder, expected, "web"));
    }

    /** Наличие старого output не разрешает дописать новый результат поверх прежнего манифеста. */
    @Test void existingOutputFails() throws Exception {
        var sources = new ArrayList<Map<String, String>>();
        for (String module : List.of("core", "ui-fx", "ui-swing", "web"))
            sources.add(Map.of("name", "cashprediction-" + module + "-1.0.0.jar", "sha256", "0".repeat(64)));
        Files.writeString(temporary.resolve("fresh-run.json"), ru.cashprediction.core.ui.json.UiJson.write(Map.of(
                "schema", 1, "runId", UUID.randomUUID().toString(), "input", temporary.toRealPath().toString(),
                "startedAtEpochMillis", System.currentTimeMillis() - 10, "sources", sources)));
        Path output = Files.createDirectory(temporary.resolve("existing"));
        assertThrows(IllegalArgumentException.class, () -> FreshAllowanceEvidence.aggregate(temporary, output));
        assertThrows(IllegalArgumentException.class, () -> FreshAllowanceEvidence.requireRunIdentity(temporary, UUID.randomUUID().toString()));
    }
}
