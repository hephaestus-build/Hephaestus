package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class PullRequestReviewHandlerStaticMethodsTest extends BaseUnitTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Nested
    class ReadTheDiff {

        private PracticeDetectionResultParser.ValidatedObservation observation(String sourceKind) {
            ObjectNode evidence = objectMapper.createObjectNode();
            ArrayNode citations = objectMapper.createArrayNode();
            ObjectNode citation = objectMapper.createObjectNode();
            citation.put("sourceKind", sourceKind);
            citation.put("path", "src/Main.java");
            citation.put("startLine", 1);
            citations.add(citation);
            evidence.set("citations", citations);
            return new PracticeDetectionResultParser.ValidatedObservation(
                    "ships-tests-with-the-change",
                    "Nothing to say here",
                    AssessmentStatus.NOT_APPLICABLE,
                    null,
                    null,
                    null,
                    evidence,
                    "The practice has no subject in this change.");
        }

        @Test
        void aDiffCitationCountsAsHavingReadTheChange() {
            assertThat(PullRequestReviewHandler.readTheDiff(List.of(observation("scm.pull-request.diff"))))
                    .isTrue();
        }

        @Test
        void citingOnlyOtherSourcesDoesNot() {
            assertThat(PullRequestReviewHandler.readTheDiff(
                            List.of(observation("scm.pull-request.core"), observation("scm.linked-work-items"))))
                    .isFalse();
        }

        @Test
        void namingTheDiffAmongTheSourcesItWalkedAlsoCounts() {
            ObjectNode evidence = objectMapper.createObjectNode();
            ArrayNode citations = objectMapper.createArrayNode();
            ObjectNode citation = objectMapper.createObjectNode();
            citation.put("sourceKind", "scm.pull-request.core");
            citation.put("path", "metadata.json");
            citation.put("startLine", 1);
            citations.add(citation);
            evidence.set("citations", citations);
            ObjectNode inapplicability = objectMapper.createObjectNode();
            ArrayNode consulted = objectMapper.createArrayNode();
            consulted.add("scm.pull-request.core");
            consulted.add("scm.pull-request.diff");
            inapplicability.set("consulted", consulted);
            evidence.set("inapplicability", inapplicability);
            var observation = new PracticeDetectionResultParser.ValidatedObservation(
                    "describe-what-and-why",
                    "The change explains itself",
                    AssessmentStatus.NOT_APPLICABLE,
                    null,
                    null,
                    null,
                    evidence,
                    "The practice has no subject in this change.");

            assertThat(PullRequestReviewHandler.readTheDiff(List.of(observation)))
                    .isTrue();
        }

        @Test
        void noObservationAtAllDoesNot() {
            assertThat(PullRequestReviewHandler.readTheDiff(List.of())).isFalse();
        }

        @Test
        void anObservationCarryingNoEvidenceDoesNot() {
            var withoutEvidence = new PracticeDetectionResultParser.ValidatedObservation(
                    "ships-tests-with-the-change",
                    "Nothing to say here",
                    AssessmentStatus.NOT_APPLICABLE,
                    null,
                    null,
                    null,
                    null,
                    "The practice has no subject in this change.");

            assertThat(PullRequestReviewHandler.readTheDiff(List.of(withoutEvidence)))
                    .isFalse();
        }
    }
}
