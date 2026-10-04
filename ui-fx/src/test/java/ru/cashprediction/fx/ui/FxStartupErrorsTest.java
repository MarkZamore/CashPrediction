package ru.cashprediction.fx.ui;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет завершение ранней ошибки без запуска toolkit и без зависания процесса. */
public class FxStartupErrorsTest {
    /** Дочерний процесс действительно завершается кодом ошибки, не создавая окна. */
    @Test void failureBeforeToolkitExitsNonzero() throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-XX:-UsePerfData", "-cp", classpath, FxStartupErrorsTest.class.getName()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "ранняя ошибка не должна оставлять процесс");
            assertEquals(1, process.exitValue());
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(output.contains("startup-probe"), output);
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    /** Вход изолированного процесса: обработчик вызывается до Application.launch. */
    public static void main(String[] args) {
        FxStartupErrors.fatal(new IllegalStateException("startup-probe"));
        throw new AssertionError("fatal returned");
    }
}
