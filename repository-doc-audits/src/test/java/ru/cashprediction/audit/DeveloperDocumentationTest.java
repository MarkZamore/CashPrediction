package ru.cashprediction.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Проверяет состав и локальную навигацию разделённой документации, не объявляя её факты доказанными.
 *
 * <p>Сверка описаний с реализацией и сборка извлечённого архива остаются отдельными проверками.
 * Аудитор находится вне поставляемого исходного дерева и не добавляет зависимость в приложение.</p>
 */
final class DeveloperDocumentationTest {
    private static final List<String> DEVELOPER = List.of("architecture.md", "techstack.md", "edge-cases.md",
            "db-schema.md", "linx.md", "ui-kit.md");
    private static final List<String> AI = List.of("CurrentSprint.md", "ContextDump.md", "ChangeRequest.md",
            "LegacyWarning.md");
    private static final Pattern LINK = Pattern.compile("\\[(?:\\\\.|[^\\]\\r\\n])+]\\(([^)\\r\\n]+)\\)");
    private static final Pattern REFERENCE = Pattern.compile("(?m)^ {0,3}\\[(?:\\\\.|[^\\]\\r\\n])+]:\\s*(<[^>\\r\\n]+>|[^\\s]+)");
    private static final Pattern SECTION = Pattern.compile("(?m)^## +\\S");
    private static final Pattern RUSSIAN = Pattern.compile("[А-Яа-яЁё]{3,}");

    @TempDir Path isolatedRepository;

    /** Каждый из шести обязательных документов содержит структуру, а не пустую заглушку. */
    @Test
    void sixDeveloperDocumentsExistAndHaveStructuredRussianProse() throws IOException {
        checkSixDeveloperDocuments(RepositoryDocuments.root());
    }

    /** Проверяет тот же обязательный технический набор в настоящем корне или изолированной фикстуре. */
    private static void checkSixDeveloperDocuments(Path root) throws IOException {
        for (String name : DEVELOPER) {
            String text = RepositoryDocuments.read(root, "docs/design/" + name);
            assertTrue(text.startsWith("# "), name);
            assertTrue(text.length() > 500, "Not a substantive document: " + name);
            assertTrue(SECTION.matcher(text).results().count() >= 3, name);
            assertTrue(RUSSIAN.matcher(text).find(), name);
        }
    }

    /** Контекст, текущее задание, итерация и долг существуют отдельно от технического набора. */
    @Test
    void fourAiDocumentsExistSeparatelyAndDeclareNoArchiveDelivery() throws IOException {
        for (String name : AI) {
            String text = RepositoryDocuments.read(RepositoryDocuments.root(), "docs/ai/" + name);
            assertTrue(text.startsWith("# "), name);
            assertTrue(text.length() > 500, name);
            assertTrue(SECTION.matcher(text).results().count() >= 3, name);
            assertTrue(text.contains(".7z"), "Archive boundary must be explicit: " + name);
        }
        assertEquals(6, DEVELOPER.size());
        assertEquals(4, AI.size());
    }

    /** Ссылки поставляемых документов не требуют документов AI или исключённых спецификаций. */
    @Test
    void deliveredDocumentationLinksResolveInsideSourceDelivery() throws IOException {
        checkDeliveredDocumentationLinks(RepositoryDocuments.root());
    }

    /** Проверяет ссылки тем же аудитором, не требуя корневых README или инструкций агента. */
    private static void checkDeliveredDocumentationLinks(Path repository) throws IOException {
        Path root = repository.toAbsolutePath().normalize();
        List<String> failures = new ArrayList<>();
        for (String name : DEVELOPER) {
            Path document = root.resolve("docs/design/" + name);
            for (String rawTarget : linkTargets(Files.readString(document))) {
                String target = rawTarget.strip();
                if (target.startsWith("<") && target.endsWith(">")) {
                    target = target.substring(1, target.length() - 1);
                }
                if (target.startsWith("https://") || target.startsWith("#")) continue;
                int fragment = target.indexOf('#');
                if (fragment >= 0) target = target.substring(0, fragment);
                if (target.isEmpty()) continue;
                if (target.indexOf(':') >= 0 || target.startsWith("/")) {
                    failures.add(name + ": nonportable link " + target);
                    continue;
                }
                Path resolved = document.getParent().resolve(target).normalize();
                if (!resolved.startsWith(root) || !Files.exists(resolved)) {
                    failures.add(name + ": missing link " + target);
                    continue;
                }
                String relative = root.relativize(resolved).toString().replace('\\', '/');
                if (!deliveryLinkAllowed(relative)) failures.add(name + ": excluded link " + target);
            }
        }
        assertEquals(List.of(), failures);
    }

    /** Отсутствие корневых документов допустимо; все шесть технических документов остаются обязательными. */
    @Test
    void sixTechnicalDocumentsAndTheirLinksWorkWithoutDeletedRootDocuments() throws IOException {
        String prose = "# Технический документ\n\n## Границы\n"
                + "Описание ответственности и ограничений.\n".repeat(12)
                + "\n## Проверка\nДоказательства исполнения проверяются отдельно.\n"
                + "\n## Навигация\n[Архитектура](architecture.md)\n";
        for (String name : DEVELOPER) {
            Path file = isolatedRepository.resolve("docs/design/" + name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, prose);
        }
        for (String removed : List.of("README.md", "CHANGELOG.md", "CLAUDE.md", "AGENTS.md")) {
            assertFalse(Files.exists(isolatedRepository.resolve(removed)), removed);
            assertFalse(deliveryLinkAllowed(removed), removed);
            assertFalse(deliveryLinkAllowed("core/src/test/resources/nested/" + removed), removed);
        }
        checkSixDeveloperDocuments(isolatedRepository);
        checkDeliveredDocumentationLinks(isolatedRepository);
        for (String name : DEVELOPER) {
            Path file = isolatedRepository.resolve("docs/design/" + name);
            Files.delete(file);
            try {
                assertThrows(IOException.class, () -> checkSixDeveloperDocuments(isolatedRepository), name);
            } finally {
                Files.writeString(file, prose);
            }
        }
        for (String name : AI) {
            assertFalse(deliveryLinkAllowed("docs/ai/" + name), name);
            assertFalse(deliveryLinkAllowed("core/src/main/resources/nested/" + name), name);
        }
        // Поставляемая ссылка на уже исключённый файл запрещена даже при его наличии в фикстуре.
        Files.writeString(isolatedRepository.resolve("README.md"), "fixture - not delivered");
        Files.writeString(isolatedRepository.resolve("docs/design/linx.md"), prose + "\n[root](../../README.md)\n");
        assertThrows(AssertionError.class, () -> checkDeliveredDocumentationLinks(isolatedRepository));
    }

    /** AI-документы могут ссылаться на репозиторные спецификации, но не на потерянные локальные файлы. */
    @Test
    void aiContextLinksResolveInRepository() throws IOException {
        Path root = RepositoryDocuments.root().toAbsolutePath().normalize();
        for (String name : AI) {
            Path document = root.resolve("docs/ai/" + name);
            for (String rawTarget : linkTargets(Files.readString(document))) {
                String target = rawTarget.strip();
                if (target.startsWith("<") && target.endsWith(">")) {
                    target = target.substring(1, target.length() - 1);
                }
                if (target.startsWith("https://") || target.startsWith("#")) continue;
                int fragment = target.indexOf('#');
                if (fragment >= 0) target = target.substring(0, fragment);
                Path resolved = document.getParent().resolve(target).normalize();
                assertTrue(resolved.startsWith(root) && Files.exists(resolved), name + ": " + target);
            }
        }
    }

    /** Проверяет цели inline-ссылок с экранированной подписью и всех определений ссылок-сносок. */
    @Test
    void escapedLabelsAndReferenceDefinitionsCannotHideExcludedTargets() {
        assertEquals(List.of("../../README.md"), linkTargets("[root\\]](../../README.md)"));
        assertEquals(List.of("../../README.md"), linkTargets("[root][excluded]\n[excluded]: ../../README.md"));
        assertEquals(List.of(), linkTargets("```md\n[example](../../README.md)\n```"));
        assertEquals(List.of(), linkTargets("`[example](../../README.md)`"));
        for (String path : List.of("core/src/test/resources/ui-scenarios/README.md",
                "ui-parity/src/test/java/ru/cashprediction/parity/check/visual/README.md",
                "core/src/test/resources/.idea/settings.json", "core/src/test/resources/INSTRUCTIONS.txt",
                "core/src/test/resources/CurrentSprint.txt")) assertFalse(deliveryLinkAllowed(path), path);
    }

    /** Литералы примеров не являются навигацией; определения проверяются даже без использования. */
    private static List<String> linkTargets(String text) {
        String prose = text.replaceAll("(?ms)^ {0,3}(`{3,}|~{3,})[^\\r\\n]*\\R.*?^ {0,3}\\1[^\\r\\n]*(?:\\R|$)", "")
                .replaceAll("`+[^`\\r\\n]*`+", "");
        List<String> targets = new ArrayList<>();
        for (Pattern pattern : List.of(LINK, REFERENCE)) {
            Matcher matcher = pattern.matcher(prose);
            while (matcher.find()) targets.add(matcher.group(1));
        }
        return targets;
    }

    /** Отрицательные примеры не позволяют AI-файлу пройти как документ или тестовый ресурс. */
    @Test
    void linkBoundaryRejectsAiNamesAndOtherRepositoryDocuments() {
        for (String path : List.of("docs/ai/CurrentSprint.md", "docs/ai/ContextDump.md",
                "docs/design/stages.md", "docs/ui-spec.md", "README.md", ".claude/history.md",
                "core/src/test/resources/ChangeRequest.md", "web/src/main/resources/LEGACYWARNING.MD",
                "ui-fx/src/main/resources/SKILL.md", "core/src/test/resources/nested/AGENTS.md")) {
            assertFalse(deliveryLinkAllowed(path), path);
        }
        for (String name : DEVELOPER) assertTrue(deliveryLinkAllowed("docs/design/" + name), name);
        assertTrue(deliveryLinkAllowed("core/src/main/java/ru/cashprediction/core/model/Money.java"));
        assertTrue(deliveryLinkAllowed("core/src/main/resources/ru/cashprediction/core/ui/text/help-format_ru.md"));
        assertTrue(deliveryLinkAllowed("core/src/test/resources/ui-scenarios/s01-first-run.cps"));
        assertTrue(deliveryLinkAllowed("dist/pom.xml"));
        assertTrue(deliveryLinkAllowed("core/pom.xml"));
    }

    /** Консервативная граница ссылок; сам состав архива отдельно проверяет настоящий упаковщик. */
    private static boolean deliveryLinkAllowed(String path) {
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        for (String segment : normalized.split("/")) {
            if (List.of(".claude", ".codex", ".agents", ".git", "target", "cashmemory",
                    ".idea", ".vscode", ".vs", "node_modules", "secrets", "credentials").contains(segment)) return false;
        }
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (name.matches("(?:readme|changelog|contributing|developer-(?:notes|guide)|instructions|agents|claude|gemini|copilot|currentsprint|contextdump|changerequest|legacywarning)(?:\\..*)?")) return false;
        if (List.of("agents.md", "claude.md", "skill.md", "currentsprint.md", "contextdump.md",
                "changerequest.md", "legacywarning.md").contains(name)) return false;
        if (normalized.startsWith("docs/")) {
            return DEVELOPER.stream().anyMatch(doc -> normalized.equals("docs/design/" + doc));
        }
        return normalized.matches("(?:core|ui-fx|ui-swing|web|update-tool|ui-parity)/src/.+")
                || normalized.matches("(?:core|ui-fx|ui-swing|web|update-tool|ui-parity|dist)/pom\\.xml")
                || normalized.startsWith("dist/scripts/") || normalized.startsWith(".github/scripts/")
                || normalized.startsWith("licenses/") || normalized.equals("pom.xml");
    }
}
