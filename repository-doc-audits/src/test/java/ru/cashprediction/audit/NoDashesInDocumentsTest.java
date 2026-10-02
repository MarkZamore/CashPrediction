package ru.cashprediction.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Документные части прежнего NoDashesInUiTextTest, обязательные при обычной сборке репозитория. */
final class NoDashesInDocumentsTest {
    /** Все пять документов существуют и не содержат ни одной прежней формы тире. */
    @Test
    void repositoryDocumentsHaveNoDashes() throws IOException {
        List<String> found = new ArrayList<>();
        for (String relative : RepositoryDocuments.DOCUMENTS) {
            found.addAll(RepositoryDocuments.dashes(relative, RepositoryDocuments.read(RepositoryDocuments.root(), relative)));
        }
        assertEquals(List.of(), found, "Repository documents must use hyphen-minus");
    }

    /** Обе настоящие спецификации не содержат типографского знака минуса U+2212. */
    @Test
    void specificationsHaveNoTypographicMinus() throws IOException {
        List<String> found = new ArrayList<>();
        for (String relative : List.of("docs/ui-spec.md", "docs/design/ui-spec-v2.md")) {
            found.addAll(RepositoryDocuments.minuses(relative, RepositoryDocuments.read(RepositoryDocuments.root(), relative)));
        }
        assertEquals(List.of(), found, "Specifications must not use U+2212");
    }
}
