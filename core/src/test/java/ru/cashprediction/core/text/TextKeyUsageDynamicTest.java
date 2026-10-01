package ru.cashprediction.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Проверяет статически выводимые ключи и отсутствие фиктивных ссылок на весь каталог. */
class TextKeyUsageDynamicTest {
    @TempDir Path dir;

    @Test void concreteStatusKeysIncludeMissingKeysWithoutCountingUnknownInput() throws IOException {
        source("Status.java", """
                class Status {
                    void run(String unknown) {
                        context.status(level, "status.msg." + "saved", file);
                        context.status(level, "status.hint.missing");
                        context.status(level, unknown);
                        // context.status(level, "status.msg.comment");
                    }
                }
                """);
        var references = collect(Set.of("status.msg.saved"));
        assertEquals(Set.of("status.msg.saved", "status.hint.missing"), TextKeyUsage.usedKeys(references));
        assertEquals(1, TextKeyUsage.missing(references, key -> key.equals("status.msg.saved")).size());
    }

    @Test
    void concreteAlertsAndPrivateForwarderRetainCallSite() throws IOException {
        Path file = source("Calls.java", """
                class Calls {
                    void run() {
                        AlertCatalog.error("save", failure, file);
                        AlertCatalog.info("empty");
                        error("read", failure);
                    }
                    private void error(String key, Throwable failure, Object... args) {
                        AlertCatalog.error(key, failure, args);
                    }
                }
                """);
        // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
        Set<String> keys = Set.of("err.save", "err.save.content", "info.empty", "info.empty.content",
                "err.read", "err.unused", "err.unused.content");
        List<TextKeyUsage.Reference> references = collect(keys);
        assertEquals(Set.of("err.save", "err.save.content", "info.empty", "info.empty.content", "err.read"),
                TextKeyUsage.usedKeys(references));
        for (TextKeyUsage.Reference reference : references) {
            assertEquals("production", reference.owner());
            assertEquals(file, reference.file());
            assertEquals(TextKeyUsage.Via.DERIVED, reference.via());
            assertTrue(reference.arguments().isEmpty());
            assertEquals(reference.key().startsWith("err.save") ? 3
                    : reference.key().startsWith("info.") ? 4 : 5, reference.line());
        }
    }

    @Test
    void restoreVariantsFollowSwitchAndConditionalConcatenation() throws IOException {
        source("Restore.java", """
                class Restore {
                    void restore(String id, boolean available) {
                        String base = switch (id) {
                            case "registry" -> "button.restoreRegistry";
                            case "xml" -> "button.restoreXml";
                            default -> "button.restoreServer";
                        };
                        String key = available ? base : base + ".none";
                        UiText.get(key, time);
                    }
                }
                """);
        Set<String> keys = Set.of("button.restoreRegistry", "button.restoreXml", "button.restoreServer",
                "button.restoreRegistry.none", "button.restoreXml.none", "button.restoreServer.none",
                "button.unused.none");
        List<TextKeyUsage.Reference> derived = collect(keys).stream()
                .filter(reference -> reference.via() == TextKeyUsage.Via.DERIVED).toList();
        assertEquals(6, derived.size());
        assertTrue(derived.stream().allMatch(reference -> reference.line() == 9));
        assertFalse(TextKeyUsage.usedKeys(derived).contains("button.unused.none"));
    }

    @Test
    void unknownValuesCommentsOtherReceiversAndTestsAreNotUses() throws IOException {
        source("Unknown.java", """
                class Unknown {
                    void run(String key, boolean flag) {
                        // AlertCatalog.error("comment", null);
                        String sample = "AlertCatalog.info(\\\"sample\\\")";
                        AlertCatalog.error(key, null);
                        Other.error("other", null);
                        error("unrelated", null);
                        String conditional = flag ? "known" : key;
                        AlertCatalog.info(conditional);
                        String changed = "old";
                        changed = key;
                        AlertCatalog.info(changed);
                        String appended = "prefix";
                        appended += key;
                        AlertCatalog.info(appended);
                    }
                    void error(String key, Throwable error) { Other.error(key, error); }
                }
                """);
        source("src/test/java/OnlyTest.java", """
                class OnlyTest { String value = UiText.get("err.testOnly"); }
                """);
        assertEquals(Set.of(), TextKeyUsage.usedKeys(collect(Set.of("err.comment", "info.sample", "err.other",
                "err.unrelated", "info.known", "info.old", "info.prefix", "err.testOnly"))));
    }

    @Test
    void missingConcreteLookupAndOptionalContentAreDistinguished() throws IOException {
        source("Missing.java", """
                class Missing {
                    void run() {
                        UiText.get("label." + "missing");
                        AlertCatalog.info("present");
                    }
                }
                """);
        List<TextKeyUsage.Reference> references = collect(Set.of("info.present"));
        assertEquals(Set.of("label.missing", "info.present"), TextKeyUsage.usedKeys(references));
        assertEquals(1, TextKeyUsage.missing(references, key -> key.equals("info.present")).size());
    }

    @Test
    void wrapperAndVariableScopesDoNotLeak() throws IOException {
        source("Scopes.java", """
                class Outer {
                    private void error(String key, Throwable failure) { AlertCatalog.error(key, failure); }
                    void first() { String key = "first"; AlertCatalog.info(key); }
                    void second(String key) { AlertCatalog.info(key); }
                    class Inner { void run() { error("inner", null); } }
                }
                class Overloaded {
                    private void error(String key, Throwable failure) { AlertCatalog.error(key, failure); }
                    private void error(String key, int number) { }
                    void run() { error("overloaded", 1); }
                }
                class Changed {
                    private void error(String key, Throwable failure) {
                        key = unknown();
                        AlertCatalog.error(key, failure);
                    }
                    void run() { error("changed", null); }
                }
                """);
        assertEquals(Set.of("info.first"), TextKeyUsage.usedKeys(collect(
                Set.of("info.first", "err.inner", "err.overloaded", "err.changed"))));
    }

    /** Создаёт независимый исходник для синтаксического разбора без компиляции фиктивных типов. */
    private Path source(String name, String text) throws IOException {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text);
    }

    /** Запускает сбор с намеренно широким корнем, проверяя также исключение тестовых каталогов. */
    private List<TextKeyUsage.Reference> collect(Set<String> keys) {
        return TextKeyUsage.collect(List.of(TextKeyUsage.SourceSet.java("production", dir, true)),
                keys, Set.of(), key -> false);
    }
}
