package ru.cashprediction.core.update.tree;

import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.update.model.FileEntry;
import static org.junit.jupiter.api.Assertions.*;

/** Независимые векторы framing, порядка UTF-8 и атрибута read-only. */
class TreeDigestTest {
    @Test void independentLiteralFrames() throws IOException {
        // Ожидаемые SHA вычислены отдельно средствами .NET по буквальным hex-байтам.
        assertEquals("35f76addb8f284a8c28f24336a8dc85658a67ef7bc401fb902f6f843defccd05",
                TreeDeltaEngine.treeHash(List.of()));
        FileEntry a = new FileEntry("app/a", 0,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", false);
        FileEntry b = new FileEntry("runtime/\u00e9", 3,
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", true);
        String expected = "1df2dd298b713591c1bcabe11e20fac7de00e06d78594d4c29cc082cd8c212f9";
        assertEquals(expected, TreeDeltaEngine.treeHash(List.of(a, b)));
        assertEquals(expected, TreeDeltaEngine.treeHash(List.of(b, a)));
        assertNotEquals(expected, TreeDeltaEngine.treeHash(List.of(a,
                new FileEntry(b.path(), b.sizeBytes(), b.sha256(), false))));
    }

    @Test void unsignedUtf8DiffersFromUtf16() throws IOException {
        FileEntry bmp = new FileEntry("app/\ue000", 0, "0".repeat(64), false);
        FileEntry supplementary = new FileEntry("app/\ud800\udc00", 0, "1".repeat(64), true);
        assertTrue(bmp.path().compareTo(supplementary.path()) > 0);
        assertTrue(ru.cashprediction.core.update.model.UpdateValidation.comparePaths(bmp.path(), supplementary.path()) < 0);
        assertEquals("441407f93795e133350abe6644c943f2bae0bac6925f81d73e85fabc5a4cea44",
                TreeDeltaEngine.treeHash(List.of(supplementary, bmp)));
        assertEquals(TreeDeltaEngine.treeHash(List.of(bmp, supplementary)),
                TreeDeltaEngine.treeHash(List.of(supplementary, bmp)));
    }
}
