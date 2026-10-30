package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeliveredGuidanceIntegrationTest extends AbstractFeedbackIntegrationTest {

    @Test
    void shouldGiveTheGuidanceOfFeedbackThatReachedTheDeveloperAlsoWhenOnlyPartOfItAppeared() {
        var delivered = result();
        bind(piece(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, AT), delivered);
        var partly = result();
        bind(piece(FeedbackDestination.ON_WORK, FeedbackState.FAILED, AT), partly);
        var neverShown = result();
        bind(piece(FeedbackDestination.ON_WORK, FeedbackState.FAILED, null), neverShown);

        assertThat(results
                        .deliveredGuidance(
                                workspace.getId(), List.of(delivered.getId(), partly.getId(), neverShown.getId()))
                        .stream()
                        .map(ResultRepository.DeliveredGuidance::getResultId))
                .containsExactlyInAnyOrder(delivered.getId(), partly.getId());
    }
}
