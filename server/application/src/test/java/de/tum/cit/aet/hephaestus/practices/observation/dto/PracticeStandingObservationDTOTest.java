package de.tum.cit.aet.hephaestus.practices.observation.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PracticeStandingObservationDTOTest extends BaseUnitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Observation observation(@Nullable String evidenceJson) {
        return Observation.builder()
                .id(UUID.randomUUID())
                .summary("Distance-warning logic ships with no test")
                .severity(Severity.MAJOR)
                .assessmentStatus(AssessmentStatus.ASSESSED)
                .presence(Presence.ABSENT)
                .assessment(Assessment.GOOD)
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(575L)
                .evidence(evidenceJson == null ? null : MAPPER.readTree(evidenceJson))
                .build();
    }

    private static String citation(String path, int line) {
        return citation(path, line, "scm.pull-request.diff");
    }

    private static String citation(String path, int line, String sourceKind) {
        String artifactPath = sourceKind.equals("scm.pull-request.diff")
                ? "inputs/context/diff.patch"
                : "inputs/context/metadata.json";
        String side = sourceKind.equals("scm.pull-request.diff") ? ",\"side\":\"NEW\"" : "";
        return """
        {"citations":[{"sourceKind":"%s",\
        "artifactPath":"%s","path":"%s"%s,\
        "startLine":%d,"endLine":%d,"quote":"line","quoteRedacted":false}]}
        """.formatted(sourceKind, artifactPath, path, side, line, line);
    }

    @Test
    @DisplayName("a real source location renders as path:line")
    void realSourceLocation() {
        var result = PracticeStandingObservationDTO.from(
                observation(citation("client/App/Services/AR/FrameRecorder.swift", 212)), null);
        assertThat(result.locator()).isEqualTo("client/App/Services/AR/FrameRecorder.swift:212");
    }

    @Test
    @DisplayName("an agent-internal context file is NOT leaked as a locator")
    void internalContextPathSuppressed() {
        assertThat(PracticeStandingObservationDTO.from(
                                observation(citation("test_presence.json", 1, "scm.pull-request.core")), null)
                        .locator())
                .isNull();
        assertThat(PracticeStandingObservationDTO.from(
                                observation(citation("review_threads.json", 1, "scm.pull-request.core")), null)
                        .locator())
                .isNull();
        assertThat(PracticeStandingObservationDTO.from(
                                observation(citation("metadata.json", 1, "scm.pull-request.core")), null)
                        .locator())
                .isNull();
    }

    @Test
    @DisplayName("a repository file named metadata.json remains a valid code locator")
    void repositoryMetadataFileIsNotMistakenForInternalContext() {
        var result = PracticeStandingObservationDTO.from(observation(citation("metadata.json", 12)), null);
        assertThat(result.locator()).isEqualTo("metadata.json:12");
    }

    @Test
    @DisplayName("C2: inputs/practices and the input manifest stay suppressed as internal plumbing")
    void practicesAndManifestStillSuppressed() {
        assertThat(PracticeStandingObservationDTO.from(
                                observation(citation("index.json", 1, "scm.pull-request.core")), null)
                        .locator())
                .isNull();
        assertThat(PracticeStandingObservationDTO.from(
                                observation(citation("manifest.json", 1, "scm.pull-request.core")), null)
                        .locator())
                .isNull();
    }

    @Test
    @DisplayName("no evidence / no location → no locator (not an error)")
    void noLocation() {
        assertThat(PracticeStandingObservationDTO.from(observation(null), null).locator())
                .isNull();
        assertThatThrownBy(() -> PracticeStandingObservationDTO.from(observation("{\"citations\":[]}"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("deliveredFeedback is the delivered feedback body passed in — null when nothing was delivered")
    void deliveredFeedbackComesFromDeliveredBody() {
        assertThat(PracticeStandingObservationDTO.from(observation(null), "Add a unit test for evaluateDistance.")
                        .deliveredFeedback())
                .isEqualTo("Add a unit test for evaluateDistance.");
        assertThat(PracticeStandingObservationDTO.from(observation(null), null).deliveredFeedback())
                .isNull();
    }
}
