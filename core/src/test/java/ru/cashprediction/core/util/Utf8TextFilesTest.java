package ru.cashprediction.core.util;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Регрессии нейтрального чтения: ограничения и ошибки не превращаются в успешный текст. */
class Utf8TextFilesTest {
    @TempDir Path dir;

    @Test void strictUtf8AndSingleBom() throws IOException {
        Path file = dir.resolve("utf8.md");
        Files.writeString(file, "\uFEFF\uFEFFтекст\r\n", StandardCharsets.UTF_8);
        assertEquals("\uFEFFтекст\r\n", Utf8TextFiles.readString(file, Files.size(file)));
    }

    @Test void malformedUtf8IsNotReplaced() throws IOException {
        Path file = dir.resolve("bad.md");
        Files.write(file, new byte[]{(byte) 0xC3, 0x28});
        assertThrows(IOException.class, () -> Utf8TextFiles.readString(file, 2));
    }

    @Test void exactByteLimitAndOversize() throws IOException {
        Path file = dir.resolve("limit.md");
        Files.writeString(file, "ёж", StandardCharsets.UTF_8);
        assertEquals("ёж", Utf8TextFiles.readString(file, 4));
        assertThrows(IOException.class, () -> Utf8TextFiles.readString(file, 3));
    }

    @Test void missingDirectoryAndNonCanonicalAreRejected() {
        assertThrows(IOException.class, () -> Utf8TextFiles.readString(dir.resolve("absent"), 10));
        assertThrows(IOException.class, () -> Utf8TextFiles.readString(dir, 10));
        assertThrows(IOException.class, () -> Utf8TextFiles.readString(dir.resolve("x/../file"), 10));
    }

    @Test void invalidLimitsFailBeforeRead() {
        assertThrows(IllegalArgumentException.class, () -> Utf8TextFiles.readString(dir, 0));
        assertThrows(IllegalArgumentException.class, () -> Utf8TextFiles.readString(dir, Long.MAX_VALUE));
    }

}
