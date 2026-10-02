package ru.cashprediction.fx.ui;

import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.text.UiText;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет разделитель настоящей подписи независимо от запуска графической подсистемы. */
class FxFieldCaptionTest {
    /** Непустой текст имеет один разделитель, семантический дамп сохраняет исходную подпись. */
    @Test void captionHasOneSeparatorAndSemanticTextRemainsStable() {
        String label = UiText.get("form.planSettings.startBalance");
        assertEquals(label + ":", FxFieldWidgets.caption(label));
        assertEquals(label + ":", FxFieldWidgets.caption(label + ":"));
        assertEquals(label, FxFieldWidgets.semanticCaption(FxFieldWidgets.caption(label)));
        assertEquals("", FxFieldWidgets.caption(""));
        assertEquals("", FxFieldWidgets.semanticCaption(""));
    }
}
