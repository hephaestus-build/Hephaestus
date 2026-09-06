package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class ReviewCoverageTest extends BaseUnitTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private PracticeDetectionResultParser.ValidatedObservation observation(Assessment assessment) {
        return new PracticeDetectionResultParser.ValidatedObservation(
                "ships-tests-with-the-change",
                "A summary of what was seen",
                Presence.PRESENT,
                assessment,
                assessment == Assessment.BAD ? Severity.MINOR : Severity.INFO,
                objectMapper.createObjectNode(),
                "The evidence warrants it.");
    }

    private JsonNode outputWithCoverage(int eligible, int evaluated) {
        ObjectNode output = objectMapper.createObjectNode();
        ObjectNode coverage = output.putObject("practiceCoverage");
        coverage.put("eligible", eligible);
        coverage.put("evaluated", evaluated);
        return output;
    }

    @Test
    void shouldAllowAnAllClearWhenTheRunReachedEveryPractice() {
        assertThat(ReviewCoverage.withholdsAllClear(outputWithCoverage(4, 4), List.of(observation(Assessment.GOOD))))
                .isFalse();
    }

    @Test
    void shouldWithholdAnAllClearWhenAPracticeWentUnevaluated() {
        assertThat(ReviewCoverage.withholdsAllClear(outputWithCoverage(4, 2), List.of(observation(Assessment.GOOD))))
                .isTrue();
    }

    @Test
    void shouldStillReportWhatAPartialReviewFound() {
        assertThat(ReviewCoverage.withholdsAllClear(
                        outputWithCoverage(4, 2), List.of(observation(Assessment.GOOD), observation(Assessment.BAD))))
                .isFalse();
    }

    @Test
    void shouldWithholdAnAllClearWhenTheRunLeftNoCoverageLedger() {
        assertThat(ReviewCoverage.withholdsAllClear(
                        objectMapper.createObjectNode(), List.of(observation(Assessment.GOOD))))
                .isTrue();
        assertThat(ReviewCoverage.withholdsAllClear(null, List.of(observation(Assessment.GOOD))))
                .isTrue();
    }
}
