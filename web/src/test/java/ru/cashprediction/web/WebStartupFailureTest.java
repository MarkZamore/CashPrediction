package ru.cashprediction.web;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.AppController;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.text.Texts;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет ошибки настоящей точки входа в отдельной headless JVM без HTTP-сервера и окон. */
class WebStartupFailureTest {
    @TempDir Path temporary;

    /** Неверный известный аргумент получает локализованный заголовок, а не необработанный stack trace. */
    @Test void invalidArgumentsFailBeforeOpeningServerOrUi() throws Exception {
        String output = failedRun(null, List.of("--no-window", "--registry", "invalid"));
        assertTrue(output.contains(UiText.get("launch.error.registryMode", "invalid")), output);
    }

    /** Неверный порт проходит через тот же безопасный запуск и не создаёт CashMemory. */
    @Test void invalidPortFailsBeforeCreatingApplicationFiles() throws Exception {
        String output = failedRun("invalid", List.of("--no-window=true", "--registry", "memory"));
        assertTrue(output.contains(Texts.get("app.web.invalidPort", PortFinder.PORT_PROPERTY, "invalid")), output);
    }

    /** Запускает только собственный процесс, ограничивает ожидание и гарантирует его завершение. */
    private String failedRun(String port, List<String> arguments) throws Exception {
        String executable = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows")
                ? "java.exe" : "java";
        Path web = Path.of(WebMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path core = Path.of(AppController.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Djava.awt.headless=true", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-Dcashprediction.ui.strictText=true", "-classpath", web + File.pathSeparator + core));
        if (port != null) command.add("-Dcashprediction.web.port=" + port);
        command.add(WebMain.class.getName()); command.add("--no-browser");
        command.add("--home"); Path home = temporary.resolve("home"); command.add(home.toString());
        command.addAll(arguments);
        Path log = temporary.resolve("startup.log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Startup failure must terminate without a dialog");
            String output = Files.readString(log, StandardCharsets.UTF_8);
            assertEquals(1, process.exitValue(), output);
            assertTrue(output.contains(UiText.get("s2.startup.errorHeader")), output);
            assertFalse(output.contains("Exception in thread"), output);
            assertFalse(output.contains("PARITY_URL"), output);
            assertFalse(Files.exists(home.resolve("CashMemory")), "Startup rejection must not create application data");
            return output;
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        }
    }
}
