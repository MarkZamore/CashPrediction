package ru.cashprediction.core.ui.dump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.io.AppInfo;
import ru.cashprediction.core.text.Texts;
import ru.cashprediction.core.ui.text.UiText;

/** Проверяет переносимость эталонов без потери текстов, порядка и точности данных. */
class DumpNormalizerTest {

    private static final Path HOME = Path.of("target", "normalizer", "CashMemory").toAbsolutePath();
    private static final String NODE = "ru/cashprediction/selftest/example";

    @Test
    void replacesOnlyValidTimesAndExactPathPrefixes() {
        String home = HOME.toString();
        assertEquals("<CashMemory>/plan.md <node>/fx <time> 24:00:00 12:60:00 112:34:56",
                DumpNormalizer.normalizeText(home + "/plan.md " + NODE + "/fx 23:59:59 24:00:00 12:60:00 112:34:56",
                        HOME, NODE));
        assertEquals(home + "Other " + NODE + "Other",
                DumpNormalizer.normalizeText(home + "Other " + NODE + "Other", HOME, NODE));
        assertEquals("<CashMemory> <node>", DumpNormalizer.normalizeText(
                home.replace('\\', '/') + " " + NODE.replace('/', '\\'), HOME, NODE));
        assertEquals("plain", DumpNormalizer.normalizeText("plain", HOME, ""));
    }

    @Test
    void recursivelyCopiesSchemaAndRoundsOnlyGeometry() {
        UiDump original = dump(new UiDump.Box(1.1, -1.1, 101.1, 49.1));
        UiDump normalized = DumpNormalizer.normalize(original, HOME, NODE);
        assertNotSame(original, normalized);
        assertEquals(new UiDump.Box(2, -2, 102, 50), normalized.frame().regions().get("center"));
        assertEquals(1202, normalized.frame().contentWidth());
        assertEquals("<CashMemory>", normalized.frame().title());
        assertEquals("<time>", normalized.summary().cards().getFirst().tooltip());
        assertEquals("123.123", normalized.table().rows().getFirst().cells().getFirst());
        assertEquals(7, normalized.table().rowCount());
        assertEquals(List.of("b", "a"), normalized.menuBar().stream().map(UiDump.MenuItem::id).toList());
        assertEquals(Map.of("SAVE", 5), normalized.counters());
        assertEquals(4, normalized.table().placeholderButtons().getFirst().x());
        assertEquals(HOME.toString(), original.frame().title());
        assertEquals(normalized, DumpNormalizer.normalize(normalized, HOME, NODE));
    }

    @Test
    void invalidGeometryCannotBeDisguisedAsAValidBox() {
        assertThrows(IllegalArgumentException.class, () -> DumpNormalizer.normalize(
                dump(new UiDump.Box(Double.NaN, 0, 100, 100)), HOME, NODE));
    }

    static UiDump dump(UiDump.Box box) {
        UiDump.MenuItem first = new UiDump.MenuItem("b", "Action", "first", "", true, false,
                "", "", "", "", List.of());
        UiDump.MenuItem second = new UiDump.MenuItem("a", "Action", "second", "", true, false,
                "", "", "", "", List.of());
        return new UiDump(1, "model", "scenario", "step",
                new UiDump.Frame(HOME.toString(), "os", new UiDump.Size(900, 600), 1201.1, 800,
                        Map.of("center", box)), List.of(first, second), null,
                new UiDump.Summary(true, List.of(new UiDump.Card("now", "title", "123.123", "text.primary",
                        "caption", "text.primary", "12:34:56", box)), ""),
                new UiDump.Table(List.of("column"), 7,
                        List.of(new UiDump.Row(1, "r1", "INCOME", List.of("123.123"), "", Map.of())),
                        "digest", "", List.of(new UiDump.Button("add", "add", "", true, true, 3.1)), "r1"),
                null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(), Map.of("SAVE", 5));
    }

    @Test void onlyAboutRuntimeVersionIsPortableAndOtherTextRemainsExact() {
        var a = new UiDump.Alert("a", "about", "INFORMATION", "title", "", 460,
                "header", "Client: JavaFX 25, Java 25.0.3.\nJava 25.0.3 is user text", "", "", false, List.of());
        var b = new UiDump.Alert("b", "other", "INFORMATION", "title", "", 460,
                "header", a.content(), "", "", false, List.of());
        var original = new UiDump(1, "model", "s", "step", null, List.of(), null, null, null, null,
                List.of(), List.of(), List.of(), List.of(a, b), List.of(), List.of(), List.of(), Map.of(), Map.of());
        var normalized = DumpNormalizer.normalize(original, HOME, NODE);
        assertEquals("Client: JavaFX 25, Java <java>.\nJava 25.0.3 is user text",
                normalized.alerts().getFirst().content());
        assertEquals(a.content(), normalized.alerts().getLast().content());
        assertEquals(normalized, DumpNormalizer.normalize(normalized, HOME, NODE));
    }

    @Test
    void developmentAndReleaseHelpersReplaceOnlyTheVersionSlot() {
        for (String version : versions()) {
            String content = about(version, "client " + version, "folder/" + version);
            String expected = about("<app-version>", "client " + version, "folder/" + version);
            assertEquals(expected, DumpNormalizer.normalizeAboutAppVersion(content, version));
            assertEquals(expected, DumpNormalizer.normalizeAboutAppVersion(expected, version));
        }
    }

    @Test
    void staleFakeAndPartialVersionsRemainVisible() {
        for (String current : versions()) {
            for (String actual : versions()) {
                if (current.equals(actual)) continue;
                String content = about(actual, "client " + current, "folder/" + current);
                assertEquals(content, DumpNormalizer.normalizeAboutAppVersion(content, current));
            }
            for (String fake : List.of("", "fake", current + " extra", "extra " + current,
                    current + "\n", current.toUpperCase(java.util.Locale.ROOT))) {
                String content = about(fake, current, current);
                assertEquals(content, DumpNormalizer.normalizeAboutAppVersion(content, current));
            }
        }
    }

    @Test
    void versionRequiresTheCompleteLocalizedTemplateAtItsExpectedPosition() {
        String version = AppInfo.displayVersion();
        String content = about(version, "client", "folder");
        String description = UiText.template("alert.about.content").orElseThrow()
                .split("\\{1\\}", 2)[0].substring("{0}".length());
        for (String altered : List.of(version, "prefix " + content, "\n" + content,
                content.replace(description, "\n\nchanged description\n\n"),
                content.replace(", Java ", ", Runtime "), content.replace("\n", "\r\n"))) {
            assertEquals(altered, DumpNormalizer.normalizeAboutAppVersion(altered, version));
        }
        // Служебное значение сравнивается буквально, включая метасимволы регулярных выражений.
        String special = "release [12].+ ($1) \\E";
        assertEquals(about("<app-version>", "client", "folder"),
                DumpNormalizer.normalizeAboutAppVersion(about(special, "client", "folder"), special));
        String fake = about("release 12x ($1) \\E", "client", "folder");
        assertEquals(fake, DumpNormalizer.normalizeAboutAppVersion(fake, special));
    }

    @Test
    void productionNormalizesCurrentAboutContentAndPreservesOtherFieldsAndUserText() {
        String version = AppInfo.displayVersion();
        String content = about(version, "client " + version, "folder/" + version);
        // JavaFX: Alert → Swing: JOptionPane → Web: dialog
        var about = new UiDump.Alert("about", "about", "INFORMATION", version, "", 460,
                version, content, version, version, false, List.of());
        // JavaFX: Alert → Swing: JOptionPane → Web: dialog
        var other = new UiDump.Alert("other", "other", "INFORMATION", version, "", 460,
                version, content, version, version, false, List.of());
        var original = alertDump(List.of(about, other));
        var normalized = DumpNormalizer.normalize(original, HOME, NODE);
        // JavaFX: Alert → Swing: JOptionPane → Web: dialog
        assertEquals(new UiDump.Alert("about", "about", "INFORMATION", version, "", 460,
                version, about("<app-version>", "client " + version, "folder/" + version)
                        .replace("Java 25.0.3.", "Java <java>."),
                version, version, false, List.of()), normalized.alerts().getFirst());
        assertEquals(other, normalized.alerts().getLast());
        assertEquals(content, original.alerts().getFirst().content());
        assertEquals(content, DumpNormalizer.normalizeText(content, HOME, NODE));
        assertEquals(normalized, DumpNormalizer.normalize(normalized, HOME, NODE));
    }

    @Test
    void productionDoesNotHideAFalseAboutVersionEvenWhenCurrentVersionAppearsElsewhere() {
        String current = AppInfo.displayVersion();
        for (String fake : List.of("fake", current + " extra", Texts.get("appinfo.version.release", 999999))) {
            String content = about(fake, "client " + current, "folder/" + current);
            // JavaFX: Alert → Swing: JOptionPane → Web: dialog
            var alert = new UiDump.Alert("about", "about", "INFORMATION", "title", "", 460,
                    "header", content, "", "", false, List.of());
            assertEquals(content.replace("Java 25.0.3.", "Java <java>."),
                    DumpNormalizer.normalize(alertDump(List.of(alert)), HOME, NODE).alerts().getFirst().content());
        }
    }

    /** Строки разработки и релизов с разными номерами и хешами из общего каталога. */
    private static List<String> versions() {
        return List.of(Texts.get("appinfo.version.dev"), Texts.get("appinfo.version.release", 12),
                Texts.get("appinfo.version.releaseCommit", 12, "a1b2c3d"),
                Texts.get("appinfo.version.releaseCommit", 12, "d4e5f6a"),
                Texts.get("appinfo.version.releaseCommit", 13, "a1b2c3d"));
    }

    /** Формирует настоящее локализованное содержимое без обращения к клиентам. */
    private static String about(String version, String client, String path) {
        return UiText.get("alert.about.content", version, client, "25.0.3", path);
    }

    /** Минимальный дамп сообщений для проверки производственного обхода. */
    private static UiDump alertDump(List<UiDump.Alert> alerts) {
        return new UiDump(1, "model", "s", "step", null, List.of(), null, null, null, null,
                List.of(), List.of(), List.of(), alerts, List.of(), List.of(), List.of(), Map.of(), Map.of());
    }
}
