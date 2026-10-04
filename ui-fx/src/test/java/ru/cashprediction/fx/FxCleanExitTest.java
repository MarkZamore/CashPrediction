package ru.cashprediction.fx;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javafx.application.Application;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import ru.cashprediction.core.app.ExitKind;
import ru.cashprediction.fx.ui.FxUiPort;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет настоящий код процесса; запуск JavaFX разрешается отдельным свойством стенда. */
public class FxCleanExitTest {
    /** Без запроса обычного выхода точка входа сохраняет нулевой код. */
    @Test void defaultExitCodeIsZero() throws Exception { checkProcess("default", 0); }

    /** Ненулевой код переживает границу между записью результата и завершением процесса. */
    @Test void recordedCleanExitCodeIsPreserved() throws Exception { checkProcess("record", 7); }

    /** Настоящий порт останавливает JavaFX, после чего точка входа возвращает код ядра. */
    @Test
    @EnabledIfSystemProperty(named = "fx.cleanExitProof", matches = "true")
    void nativePortReturnsNonzeroCodeAfterLaunch() throws Exception { checkProcess("native", 7); }

    private static void checkProcess(String mode, int expected) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-XX:-UsePerfData", "-cp", classpath, FxCleanExitTest.class.getName(), mode)
                .redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "процесс должен завершиться после обычного выхода");
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(expected, process.exitValue(), output);
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    /** Вход изолированного процесса без контроллера, CashMemory и реестра. */
    public static void main(String[] args) {
        if (args[0].equals("native")) Application.launch(ExitApplication.class);
        else if (args[0].equals("record")) FxMain.recordCleanExit(7);
        FxMain.exitAfterLaunch();
    }

    /** Минимальное приложение проверяет эффект порта без запуска основного приложения. */
    public static final class ExitApplication extends Application {
        /** Создаётся загрузчиком JavaFX. */
        public ExitApplication() { }
        /** Закрывает настоящий порт с ненулевым кодом обычного выхода. */
        @Override public void start(Stage stage) {
            javafx.application.Platform.setImplicitExit(false);
            new FxUiPort(stage).exit(ExitKind.CLEAN, 7);
        }
    }
}
