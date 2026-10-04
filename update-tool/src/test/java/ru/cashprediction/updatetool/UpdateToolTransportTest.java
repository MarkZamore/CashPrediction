package ru.cashprediction.updatetool;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.json.JsonWriter;

import static org.junit.jupiter.api.Assertions.*;

/** Проверяет точность ASCII-конверта CLI и сохранение обычных правил команд и путей. */
class UpdateToolTransportTest {
    @TempDir Path temporary;

    @Test
    void unicodeInventoryMatchesDirectArgumentsByteForByte() throws Exception {
        Path root = temporary.resolve("portable space " + Character.toString(0x043f) + Character.toString(0x8def));
        Files.createDirectories(root.resolve("app"));
        Files.createDirectories(root.resolve("runtime/bin"));
        for (String name : List.of("CashPrediction.exe", "CashPrediction-Swing.exe", "CashPrediction-Web.exe")) {
            Files.writeString(root.resolve(name), name);
        }
        Files.writeString(root.resolve("app/shared.txt"), "shared");
        Files.writeString(root.resolve("runtime/bin/java.exe"), "fixture");
        Path direct = temporary.resolve("direct.json");
        Path encoded = temporary.resolve("inventory " + Character.toString(0x8def) + ".json");
        assertEquals(0, invoke("inventory", "--root", root.toString(), "--out", direct.toString()));
        assertEquals(0, invoke("--arguments-base64", encode(List.of("inventory", "--root", root.toString(),
                "--out", encoded.toString()))));
        assertArrayEquals(Files.readAllBytes(direct), Files.readAllBytes(encoded));
    }

    @Test
    void malformedOrNestedTransportIsUsageErrorWithoutCreatingOutput() {
        List<String> invalid = new ArrayList<>(List.of("!", "", "a".repeat(262145),
                Base64.getEncoder().encodeToString(new byte[]{(byte) 0xc3, 0x28}),
                encode(List.of()), encode(List.of("--arguments-base64", encode(List.of("help")))),
                encode(List.of("inventory", 1)), encode(List.of("help", (Object) "\0")),
                Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8)),
                encode(java.util.Collections.nCopies(65, "help"))));
        String canonical = encode(List.of("help"));
        invalid.add(canonical.replace("=", ""));
        for (String payload : invalid) assertEquals(2, invoke("--arguments-base64", payload));
        assertEquals(2, invoke("--arguments-base64"));
        assertEquals(2, invoke("--arguments-base64", canonical, "extra"));
        assertEquals(0, invoke("--arguments-base64", canonical));
    }

    @Test
    void decodedArgumentsCannotBypassCommandAndPathValidation() throws Exception {
        assertEquals(2, invoke("--arguments-base64", encode(List.of("unknown"))));
        assertEquals(2, invoke("--arguments-base64", encode(List.of("inventory", "--root", "anything"))));
        Path output = temporary.resolve("must-not-exist.json");
        assertEquals(1, invoke("--arguments-base64", encode(List.of("inventory", "--root", "../unsafe",
                "--out", output.toString()))));
        assertFalse(Files.exists(output));
    }

    /** Кодирует именно JSON-массив, как независимый PowerShell-вызыватель. */
    private static String encode(List<?> values) {
        return Base64.getEncoder().encodeToString(JsonWriter.write(values).getBytes(StandardCharsets.UTF_8));
    }

    /** Вызывает настоящий CLI без завершения тестовой JVM и без печати тестовых входов. */
    private static int invoke(String... arguments) {
        return UpdateTool.run(arguments, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));
    }
}
