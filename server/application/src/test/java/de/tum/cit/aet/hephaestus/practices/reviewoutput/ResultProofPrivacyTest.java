package de.tum.cit.aet.hephaestus.practices.reviewoutput;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackVisibilityService;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ProblemRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultCommunicationRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultMarkRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultPresentation;
import de.tum.cit.aet.hephaestus.practices.review.ProductReferenceQuery;
import de.tum.cit.aet.hephaestus.practices.review.dto.DeliveredWorkFeedbackCopyDTO;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ResultProofPrivacyTest extends BaseUnitTest {
    @Test
    void inaccessibleProofHasAnExplicitNullWireValue() {
        var presentation = new ResultPresentation(
                mock(ResultMarkRepository.class),
                mock(ProblemRepository.class),
                mock(ProductReferenceQuery.class),
                mock(ResultCommunicationRepository.class),
                mock(FeedbackVisibilityService.class),
                new JsonMapper(),
                Clock.systemUTC());
        var result =
                Result.builder().evidence(new JsonMapper().createObjectNode()).build();
        assertThat(presentation.evidence(result, false)).isNull();
    }

    @Test
    void missingFeedbackBodyIsNotRecoveredFromRuntimeOutput() {
        var copy = new DeliveredWorkFeedbackCopyDTO(
                UUID.randomUUID(), FeedbackCopyKind.SUMMARY, "copy-1", null, null, null, null, null, Instant.EPOCH);
        var tree = new JsonMapper().valueToTree(copy);
        assertThat(tree.has("body")).isFalse();
        assertThat(tree.path("externalRef").asString()).isEqualTo("copy-1");
    }
}
