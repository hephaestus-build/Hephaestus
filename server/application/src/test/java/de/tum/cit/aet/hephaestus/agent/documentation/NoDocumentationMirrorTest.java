package de.tum.cit.aet.hephaestus.agent.documentation;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The floor under the documentation seam: without this bean, a deployment with the documentation
 * switch off has no implementation to inject and the application would not start.
 */
class NoDocumentationMirrorTest extends BaseUnitTest {

    private final DocumentProjection projection = new NoDocumentationMirror();

    @Test
    @DisplayName("nothing is mirrored, so nothing is found — never a fabricated document")
    void answersNothingForEveryLookup() {
        assertThat(projection.documentsForWorkspace(1L)).isEmpty();
        assertThat(projection.documentsByReference(1L, List.of("doc-1", "https://wiki.example.com/doc/x")))
                .isEmpty();
        assertThat(projection.searchDocuments(1L, "runbook", 10)).isEmpty();
    }

    @Test
    @DisplayName("a document review finds no subject, which its content source reports as unavailable")
    void hasNoSubjectForADocumentReview() {
        assertThat(projection.documentById(1L, 77L)).isEmpty();
    }
}
