package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.swing.SwingMain;

/** Ранний сбой проверяется в отдельной headless JVM: код ненулевой, hooks чистого выхода не выполняются. */
class SwingStartupFailureTest {
    @TempDir Path temporary;

    @Test void earlyFailureDoesNotExecuteCleanShutdownHooks() throws Exception {
        verifyFailure("injected");
    }

    @Test void headlessEntrypointFailsWithoutOpeningWindows() throws Exception {
        verifyFailure("entry");
    }

    /** Запускает настоящий обработчик, не подменяя halt и не открывая GUI. */
    private void verifyFailure(String mode) throws Exception {
        Path marker = temporary.resolve(mode + "-clean-marker");
        Path output = temporary.resolve(mode + "-stderr.txt");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (!Files.isRegularFile(java)) java = java.resolveSibling("java");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        // На Windows stderr иначе может использовать системную кодовую страницу вместо UTF-8 файла отчёта.
        Process child = new ProcessBuilder(java.toString(), "-Djava.awt.headless=true", "-XX:-UsePerfData",
                "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-cp", classpath, Probe.class.getName(), marker.toString(), mode)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "дочерний процесс должен завершиться");
            assertEquals(2, child.exitValue(), Files.readString(output));
            assertFalse(Files.exists(marker), "shutdown hook не должен записывать чистый маркер");
            String diagnostic = Files.readString(output, StandardCharsets.UTF_8);
            assertTrue(diagnostic.contains(mode.equals("entry") ? UiText.get("s2.startup.errorHeader") : "injected-startup-failure"), diagnostic);
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    /** Дочерний процесс с имитацией потенциально опасного hook чистого выхода. */
    public static final class Probe {
        private Probe() { }

        /** Устанавливает hook и вызывает реальную точку входа либо ранний обработчик. */
        public static void main(String[] args) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { Files.writeString(Path.of(args[0]), "clean"); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            }, "test-clean-marker"));
            if (args[1].equals("entry")) SwingMain.main(new String[0]);
            else SwingCoreMain.startupFailed(new IllegalStateException("injected-startup-failure"));
        }
    }
}
