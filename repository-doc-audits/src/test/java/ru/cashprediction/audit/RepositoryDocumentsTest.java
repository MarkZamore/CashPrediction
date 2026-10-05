package ru.cashprediction.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Регрессии аудитора: потерянный документ не пропускается, правила поиска не сужаются. */
final class RepositoryDocumentsTest {
    @TempDir Path emptyRepository;

    /** Каждый обязательный файл при отсутствии вызывает ошибку. */
    @Test
    void missingDocumentsAreErrors() {
        for (String document : RepositoryDocuments.DOCUMENTS) {
            assertThrows(IOException.class, () -> RepositoryDocuments.read(emptyRepository, document), document);
        }
    }

    /** Удалённые корневые документы не требуются при чтении четырёх действующих спецификаций. */
    @Test
    void requiredSpecificationsRemainReadableWithoutDeletedRootDocuments() throws IOException {
        assertEquals(List.of("docs/ui-spec.md", "docs/design/ui-spec-v2.md",
                "docs/FORMAT.md", "docs/ui-protocol.md"), RepositoryDocuments.DOCUMENTS);
        for (String document : RepositoryDocuments.DOCUMENTS) {
            Path file = emptyRepository.resolve(document);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "# Specification\nUse hyphen-minus - only.\n");
        }
        for (String removed : List.of("README.md", "CHANGELOG.md", "CLAUDE.md", "AGENTS.md")) {
            assertFalse(Files.exists(emptyRepository.resolve(removed)), removed);
        }
        for (String document : RepositoryDocuments.DOCUMENTS) {
            String text = RepositoryDocuments.read(emptyRepository, document);
            assertEquals(List.of(), RepositoryDocuments.dashes(document, text));
            assertEquals(List.of(), RepositoryDocuments.minuses(document, text));
        }
        Path missing = emptyRepository.resolve("docs/FORMAT.md");
        Files.delete(missing);
        assertThrows(IOException.class, () -> RepositoryDocuments.read(emptyRepository, "docs/FORMAT.md"));
    }

    /** Поиск сохраняет символы, повторные u, регистр и ведущие нули HTML-сущностей. */
    @Test
    void everyHistoricalDashEncodingIsRejected() {
        for (String form : List.of(String.valueOf((char) 0x2013), String.valueOf((char) 0x2014),
                "\\u2013", "\\uu2014", "&ndash;", "&MDASH;", "&#8211;", "&#008212;", "&#x2013;", "&#x002014;")) {
            assertEquals(1, RepositoryDocuments.dashes("sample.md", "ok\n" + form).size(), form);
        }
        assertEquals(List.of(), RepositoryDocuments.dashes("sample.md", "hyphen - minus " + (char) 0x2212));
    }

    /** Минус проверяется независимо от тире; обычный дефис остаётся допустимым. */
    @Test
    void typographicMinusIsStillRejected() {
        assertEquals(1, RepositoryDocuments.minuses("sample.md", "a\n" + (char) 0x2212).size());
        assertEquals(List.of(), RepositoryDocuments.minuses("sample.md", "a - b"));
    }
}
