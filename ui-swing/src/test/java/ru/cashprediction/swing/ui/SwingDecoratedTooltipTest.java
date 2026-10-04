package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.token.ColorToken;
import ru.cashprediction.core.ui.view.table.DecoratedTooltip;

/** Проверяет явное оформление табличной подсказки без анализа пользовательских строк. */
class SwingDecoratedTooltipTest {
    private static final List<String> SERVICE_KEYS = List.of("table.tip.amountChanged", "table.tip.moved",
            "table.tip.shifted", "table.tip.skipped", "table.tip.whatIfAmount");
    private static final List<String> GLYPHS = List.of("\u270e", "\u2192", "\u21c4", "\u2715", "\u0394");

    @Test
    void paintsOnlyDeclaredServiceOccurrenceAndEscapesUserText() {
        String service = UiText.get("table.tip.amountChanged");
        String title = "\ud83d\ude00 <title>\n" + service;
        String text = title + "\n" + service + "\n" + UiText.get("table.tip.note", service + "\n" + service);
        DecoratedTooltip tooltip = new DecoratedTooltip(text,
                List.of(new DecoratedTooltip.IconPosition(title.length() + 1, "\u270e")));
        String html = SwingLook.tableTooltipHtml(tooltip);
        assertEquals(1, html.split("<img", -1).length - 1);
        assertTrue(html.contains("&lt;title&gt;"));
        assertTrue(html.contains(SwingLook.escape(title).replace("\n", "<br>")));
        assertFalse(SwingLook.tableTooltipHtml(DecoratedTooltip.plain(text)).contains("<img"));
        assertNull(SwingLook.tableTooltipHtml(DecoratedTooltip.plain("")));
    }

    /** Проверяет точное место всех значков между одинаковыми многострочными пользовательскими фрагментами. */
    @Test
    void placesEveryDeclaredGlyphBetweenMultilineServiceLookalikes() {
        List<String> services = SERVICE_KEYS.stream().map(UiText::get).toList();
        String lookalikes = String.join("\n", services);
        String title = "\ud83d\ude00\n" + lookalikes;
        String note = UiText.get("table.tip.note", lookalikes + "\n\n" + lookalikes);
        StringBuilder text = new StringBuilder(title).append('\n');
        StringBuilder expected = new StringBuilder(title.replace("\n", "<br>")).append("<br>");
        List<DecoratedTooltip.IconPosition> positions = new ArrayList<>();
        for (int i = 0; i < services.size(); i++) {
            positions.add(new DecoratedTooltip.IconPosition(text.length(), GLYPHS.get(i)));
            text.append(services.get(i)).append('\n');
            expected.append(SwingIcons.htmlImage(GLYPHS.get(i), ColorToken.TOOLTIP_TEXT))
                    .append(services.get(i).substring(GLYPHS.get(i).length())).append("<br>");
        }
        text.append(note);
        expected.append(note.replace("\n", "<br>"));
        assertEquals(expected.toString(), content(new DecoratedTooltip(text.toString(), positions)));
    }

    /** Фиксирует экранирование разметки и сущностей независимо от функции экранирования реализации. */
    @Test
    void escapesHtmlInjectionAndEntitiesAroundDeclaredGlyph() {
        String prefix = "<html><img src='user'>&lt;\"quoted\"\n\ud83d\ude00 & ";
        String suffix = " <script>alert(\"x\")</script>\n</div></html>&#9998;\n";
        String expectedPrefix = "&lt;html&gt;&lt;img src='user'&gt;&amp;lt;\"quoted\"<br>\ud83d\ude00 &amp; ";
        String expectedSuffix = " &lt;script&gt;alert(\"x\")&lt;/script&gt;<br>"
                + "&lt;/div&gt;&lt;/html&gt;&amp;#9998;<br>";
        String glyph = GLYPHS.get(0);
        String text = prefix + glyph + suffix;
        assertEquals(expectedPrefix + SwingIcons.htmlImage(glyph, ColorToken.TOOLTIP_TEXT) + expectedSuffix,
                content(new DecoratedTooltip(text, List.of(new DecoratedTooltip.IconPosition(prefix.length(), glyph)))));
        assertEquals(expectedPrefix + glyph + expectedSuffix, content(DecoratedTooltip.plain(text)));
    }

    /** Проверяет крайние и соседние позиции после суррогатных пар и экранируемых символов. */
    @Test
    void usesOriginalUtf16OffsetsForAdjacentAndBoundaryGlyphs() {
        String first = GLYPHS.get(0);
        String middle = "\ud83d\ude00<&\ud83d\ude80\n";
        String second = GLYPHS.get(1);
        String last = GLYPHS.get(4);
        int secondOffset = first.length() + middle.length();
        assertEquals(first.length() + middle.codePointCount(0, middle.length()) + 2, secondOffset);
        String text = first + middle + second + last;
        DecoratedTooltip tooltip = new DecoratedTooltip(text, List.of(
                new DecoratedTooltip.IconPosition(0, first),
                new DecoratedTooltip.IconPosition(secondOffset, second),
                new DecoratedTooltip.IconPosition(secondOffset + second.length(), last)));
        assertEquals(SwingIcons.htmlImage(first, ColorToken.TOOLTIP_TEXT) + "\ud83d\ude00&lt;&amp;\ud83d\ude80<br>"
                + SwingIcons.htmlImage(second, ColorToken.TOOLTIP_TEXT)
                + SwingIcons.htmlImage(last, ColorToken.TOOLTIP_TEXT), content(tooltip));
    }

    /** Извлекает всё содержимое контейнера, чтобы сравнение замечало потерю или перестановку любого фрагмента. */
    private static String content(DecoratedTooltip tooltip) {
        String html = SwingLook.tableTooltipHtml(tooltip);
        assertNotNull(html);
        assertTrue(html.startsWith("<html><div style='width:"));
        assertTrue(html.endsWith("</div></html>"));
        int start = html.indexOf('>', html.indexOf("<div")) + 1;
        return html.substring(start, html.length() - "</div></html>".length());
    }
}
