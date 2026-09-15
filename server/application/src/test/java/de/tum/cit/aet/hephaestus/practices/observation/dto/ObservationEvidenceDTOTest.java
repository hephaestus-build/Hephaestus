package de.tum.cit.aet.hephaestus.practices.observation.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ObservationEvidenceDTOTest extends BaseUnitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldPreserveTheVerifiedCommitForHistoricalEvidence() {
        var evidence = MAPPER.createObjectNode();
        evidence.putArray("citations")
                .addObject()
                .put("sourceKind", "scm.repository.tree")
                .put("artifactPath", "repo/.git/HEAD")
                .put("path", "deleted.java")
                .put("revision", "a".repeat(40))
                .put("startLine", 3)
                .put("quote", "earlier source");
        var mapped = ObservationEvidenceDTO.from(evidence);
        assertThat(mapped).isNotNull();
        assertThat(mapped.citations().getFirst().revision()).isEqualTo("a".repeat(40));
    }

    @Test
    void mapsCanonicalRedactedCitation() {
        var evidence = ObservationEvidenceDTO.from(MAPPER.readTree("""
                {"detector":"secret-scan","citations":[{"sourceKind":"scm.repository.tree",\
                "artifactPath":"inputs/sources/scm/repo","path":"config.env","startLine":3,\
                "endLine":3,"quoteRedacted":true}]}
                """));

        assertThat(evidence).isNotNull();
        assertThat(evidence.detector()).isEqualTo("secret-scan");
        assertThat(evidence.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.path()).isEqualTo("config.env");
            assertThat(citation.quote()).isNull();
            assertThat(citation.quoteRedacted()).isTrue();
        });
    }

    @Test
    void rejectsMalformedCitationMember() {
        assertThatThrownBy(() -> ObservationEvidenceDTO.from(MAPPER.readTree("{\"citations\":[\"invalid\"]}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyCitations() {
        assertThatThrownBy(() -> ObservationEvidenceDTO.from(MAPPER.readTree("{\"citations\":[]}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresSideExactlyForDiffCitations() {
        assertThatThrownBy(() -> ObservationEvidenceDTO.from(MAPPER.readTree("""
                    {"citations":[{"sourceKind":"scm.pull-request.diff",\
                    "artifactPath":"inputs/context/diff.patch","path":"src/Auth.java",\
                    "startLine":10,"endLine":10,"quote":"+ insecure();"}]}
                    """)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> ObservationEvidenceDTO.from(MAPPER.readTree("""
                    {"citations":[{"sourceKind":"scm.pull-request.core",\
                    "artifactPath":"inputs/context/pull_request.json","path":"pull_request.json",\
                    "side":"NEW","startLine":1,"endLine":1,"quote":"{}"}]}
                    """)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
