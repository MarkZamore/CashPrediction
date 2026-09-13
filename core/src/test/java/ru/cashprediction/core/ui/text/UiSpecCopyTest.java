package ru.cashprediction.core.ui.text;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.text.CoreModuleDir;

/**
 * Две копии спецификации интерфейса v2 не расходятся.
 *
 * <p>{@code docs/design/ui-spec-v2.md} — утверждённый документ дизайна; {@code docs/ui-spec.md} — рабочая копия, на
 * которую ссылаются этапы (stages.md: S0 кладёт её байт в байт, S3 и S5 правят её вместе с эталонами). Чтобы у проекта
 * оставался один источник правды, файлы обязаны совпадать байт в байт: правка одного без другого ломает сборку.</p>
 */
class UiSpecCopyTest {

    @Test
    void workingCopyIsByteIdenticalToDesignSpec() throws IOException {
        Path design = CoreModuleDir.resolve("../docs/design/ui-spec-v2.md");
        Path copy = CoreModuleDir.resolve("../docs/ui-spec.md");
        assertTrue(Files.isRegularFile(design), design.toString());
        assertTrue(Files.isRegularFile(copy), copy.toString());

        byte[] expected = Files.readAllBytes(design);
        byte[] actual = Files.readAllBytes(copy);
        int mismatch = Arrays.mismatch(expected, actual);
        assertTrue(mismatch < 0, () -> "docs/ui-spec.md differs from docs/design/ui-spec-v2.md at byte " + mismatch
                + "; apply the same edit to both files");
    }
}
