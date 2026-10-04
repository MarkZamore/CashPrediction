package ru.cashprediction.fx.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import javafx.scene.image.Image;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.token.ColorToken;
import ru.cashprediction.core.ui.token.UiIcons;

/** Проверяет связь установленных объектов с их исходными байтами, не качество рисунка. */
final class FxIconProvenanceTest {
    /** Кэш удерживает именно декодированные байты и не отдаёт внутреннюю память. */
    @Test void retainedBytesFollowImageIdentityAndAreCopied() {
        Image image = FxIcons.image("undo", ColorToken.TEXT_PRIMARY).orElseThrow();
        var source = FxIcons.decodedSource(image).orElseThrow();
        byte[] expected = UiIcons.png("undo", ColorToken.TEXT_PRIMARY).orElseThrow();
        assertArrayEquals(expected, source.bytes());
        byte[] changed = source.bytes(); changed[0] = 0;
        assertArrayEquals(expected, FxIcons.decodedSource(image).orElseThrow().bytes());
        assertSame(image, FxIcons.image("undo", ColorToken.TEXT_PRIMARY).orElseThrow());
    }

    /** Равные пиксели другого stream-объекта не наследуют регистрацию изображения. */
    @Test void replacementImageCannotBorrowRegisteredSource() {
        byte[] bytes = UiIcons.png("undo", ColorToken.TEXT_PRIMARY).orElseThrow();
        FxIcons.image("undo", ColorToken.TEXT_PRIMARY).orElseThrow();
        Image replacement = new Image(new ByteArrayInputStream(bytes));
        assertTrue(FxIcons.decodedSource(replacement).isEmpty());
        assertTrue(FxIcons.decodedSource(null).isEmpty());
    }

    /** Переданные конструктору байты также копируются до возможного изменения владельцем. */
    @Test void provenanceRecordCopiesInputBytes() {
        byte[] bytes = {1, 2, 3};
        var source = new FxIcons.DecodedPng(bytes, "unit-source");
        bytes[0] = 0;
        assertArrayEquals(new byte[]{1, 2, 3}, source.bytes());
    }
}
