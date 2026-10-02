package ru.cashprediction.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Регрессии аудитора: потерянный документ не пропускается, правила поиска не сужаются. */
final class RepositoryDocumentsTest {
    @TempDir Path emptyRepository;

    /** Каждый из пяти обязательных файлов при отсутствии вызывает ошибку. */
    @Test
    void missingDocumentsAreErrors() {
        for (String document : RepositoryDocuments.DOCUMENTS) {
            assertThrows(IOException.class, () -> RepositoryDocuments.read(emptyRepository, document), document);
        }
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
