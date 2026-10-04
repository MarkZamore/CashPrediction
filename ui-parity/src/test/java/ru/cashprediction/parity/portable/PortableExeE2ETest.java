package ru.cashprediction.parity.portable;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import ru.cashprediction.parity.process.ProcessTree;
import static org.junit.jupiter.api.Assertions.*;

/** Явно включаемый S5 gate: настоящий portable-скрипт проверяет девять сочетаний пути и EXE. */
@EnabledIfSystemProperty(named = "parity.portable", matches = "true")
class PortableExeE2ETest {
    /** Требует готовую сборку и выполняет полный -Parity без пропуска отсутствующих ресурсов. */
    @Test
    void allThreeActualExecutablesInAllThreePathClasses() throws Exception {
        assertTrue(System.getProperty("os.name", "").startsWith("Windows"), "Windows EXE required");
        String directory = System.getProperty("parity.portable.dir");
        assertNotNull(directory, "Opt-in requires -Dparity.portable.dir=<built distribution>");
        Path project = Path.of(System.getProperty("parity.reactor.root", "..")).toRealPath();
        Path portable = Path.of(directory).toRealPath();
        for (String exe : List.of("CashPrediction.exe", "CashPrediction-Swing.exe", "CashPrediction-Web.exe"))
            assertTrue(Files.isRegularFile(portable.resolve(exe)), "Missing actual EXE: " + exe);
        List<String> command = List.of("pwsh.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                project.resolve(".github/scripts/Test-Portable.ps1").toString(), "-PortableDir", portable.toString(),
                "-Parity");
        Process process = new ProcessBuilder(command).directory(project.toFile()).inheritIO().start();
        try {
            long deadline = System.nanoTime() + Duration.ofMinutes(90).toNanos();
            while (!process.waitFor(30, TimeUnit.SECONDS)) assertTrue(System.nanoTime() < deadline, "Portable parity timed out");
            assertEquals(0, process.exitValue(), "Actual EXE parity failed; inspect redacted portable evidence");
        } finally {
            ProcessTree.kill(process.toHandle(), Duration.ofSeconds(30)).requireClean();
        }
    }
}
