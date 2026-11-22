package de.tum.cit.aet.hephaestus.practices.feedback.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasisRepository;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class FeedbackResponseSuppressionFilterTest {

    @Test
    void shouldReturnOnlyTheProblemsSuppressedByTheLatestCompleteResponse() {
        var basis = mock(FeedbackBasisRepository.class);
        var id = UUID.randomUUID();
        var other = UUID.randomUUID();
        var response = mock(FeedbackBasisRepository.SuppressedProblem.class);
        when(response.getId()).thenReturn(id);
        when(response.getResolution()).thenReturn(FeedbackResolution.DISAGREE);
        when(basis.findSuppressedProblems(7, List.of(id, other), true)).thenReturn(List.of(response));
        assertThat(new FeedbackResponseSuppressionFilter(basis)
                        .suppressedProblems(7, FeedbackDestination.ON_WORK, List.of(id, other)))
                .containsExactlyEntriesOf(Map.of(id, FeedbackStopReason.REACTED_DISAGREE));
    }

    @Test
    void shouldReturnNoSuppressionForEmptyProblemBasis() {
        assertThat(new FeedbackResponseSuppressionFilter(mock(FeedbackBasisRepository.class))
                        .suppressedProblems(7, FeedbackDestination.ON_WORK, List.of()))
                .isEmpty();
    }
}
