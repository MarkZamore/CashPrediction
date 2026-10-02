package ru.cashprediction.parity.launch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет настоящий вывод дочерней JVM, независимо от кодовой страницы Windows. */
class LauncherEncodingTest {
    @TempDir Path root;

    /** Русская строка перед handshake не должна делать весь UTF-8 журнал нечитаемым. */
    @Test void redirectedStreamsRemainUtf8WithNonUtf8NativeEncoding() throws Exception {
        Path stdout = root.resolve("stdout.log"), stderr = root.resolve("stderr.log");
        var command = new ArrayList<String>();
        command.add(ClientLauncher.javaExecutable().toString());
        command.add("-Dnative.encoding=windows-1251");
        command.addAll(ClientLauncher.STANDARD_JVM_OPTIONS);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Probe.class.getName());
        Process process = new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        try {
            assertTrue(process.waitFor(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS));
            assertEquals(0, process.exitValue());
            assertEquals("Порт занят\nPARITY_URL http://127.0.0.1:1234/app.html?t=test\n",
                    Files.readString(stdout).replace("\r\n", "\n"));
            assertEquals("Ошибка записи\n", Files.readString(stderr).replace("\r\n", "\n"));
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }

    /** Дочерняя JVM проверяет именно кодировку System.out/System.err, а не writer теста. */
    public static final class Probe {
        /** Пишет локализованное сообщение и ASCII handshake в те же потоки клиента. */
        public static void main(String[] args) {
            System.out.println("Порт занят");
            System.out.println("PARITY_URL http://127.0.0.1:1234/app.html?t=test");
            System.err.println("Ошибка записи");
        }
    }
}
