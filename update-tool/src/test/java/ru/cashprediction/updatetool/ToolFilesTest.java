package ru.cashprediction.updatetool;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Проверяет границы ввода и защиты путей независимо от движка W1. */
class ToolFilesTest {
    @TempDir Path temporary;

    @Test
    void symlinkAncestorAndInputAreRejectedWhenHostAllowsCreation() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Path file = source.resolve("input.json");
        Files.writeString(file, "{}");
        Path link = temporary.resolve("link");
        try {
            Files.createSymbolicLink(link, source);
        } catch (IOException | UnsupportedOperationException | SecurityException ex) {
            org.junit.jupiter.api.Assumptions.abort("Host cannot create symbolic links");
        }
        assertThrows(IOException.class, () -> ToolFiles.path(link.resolve("output.json").toString()));
        assertThrows(IOException.class, () -> ToolFiles.json(link.resolve("input.json")));
        assertFalse(Files.exists(source.resolve("output.json")));
        assertEquals("{}", Files.readString(file));
    }

    @Test
    void caseFoldedTreeAndAncestorOverlapAreRejected() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("Source"));
        Files.createDirectories(temporary.resolve("source"));
        assertThrows(IOException.class, () -> ToolFiles.output(temporary.resolve("source/out.json"), List.of(root), List.of()));
        Path destination = temporary.resolve("new-tree");
        Path child = destination.resolve("manifest.json");
        assertThrows(IOException.class, () -> ToolFiles.output(destination, List.of(), List.of(child)));
    }

    @Test
    void jsonLimitAndMalformedUtf8AreRejected() throws Exception {
        Path input = temporary.resolve("oversize.json");
        try (FileChannel file = FileChannel.open(input, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                StandardOpenOption.SPARSE)) {
            file.position(8L * 1024 * 1024);
            file.write(ByteBuffer.wrap(new byte[]{0}));
        }
        assertThrows(IOException.class, () -> ToolFiles.json(input));
        Files.write(input, new byte[]{(byte) 0xc3, (byte) 0x28});
        assertThrows(IOException.class, () -> ToolFiles.json(input));
    }

    @Test
    void sparseOversizeContainerIsRejectedBeforeStreaming() throws Exception {
        Path input = temporary.resolve("oversize.zip");
        try (FileChannel file = FileChannel.open(input, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                StandardOpenOption.SPARSE)) {
            file.position(512L * 1024 * 1024);
            file.write(ByteBuffer.wrap(new byte[]{0}));
        }
        assertThrows(IOException.class, () -> ToolFiles.digest(input));
    }

    @Test
    void unicodePathIsPreservedAndUnsafeInputsAreNotNormalized() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("\u041f\u0430\u043f\u043a\u0430"));
        assertEquals(root.toRealPath(), ToolFiles.path(root.toString()));
        for (String name : List.of("../escape", "./escape", "e\u0301", "file:ads", "COM1.txt", "CLOCK$",
                "CON .json", "NUL   .txt", "COM1 .json", "LPT\u00b2 .txt", "CONIN$ .json",
                "bad.", "bad ", "bad\u0001", "bad\ud800")) {
            assertThrows(IOException.class, () -> ToolFiles.path(root + "/" + name), name);
        }
    }
}
