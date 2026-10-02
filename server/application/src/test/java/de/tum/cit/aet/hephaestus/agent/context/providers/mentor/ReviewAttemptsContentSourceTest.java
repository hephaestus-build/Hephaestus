package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.EvidenceCollectionException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository.ReviewAttemptRow;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunLookup;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import tools.jackson.databind.ObjectMapper;

class ReviewAttemptsContentSourceTest extends BaseUnitTest {

    @Mock
    AgentJobRepository jobRepository;

    @Mock
    ReviewRunLookup reviewRuns;

    @Mock
    WorkspaceActorSelector actorSelector;

    /** The optional-source failure the context builder skips, so the turn reads the list as unavailable, not empty. */
    @Test
    void shouldFailRatherThanDropAFoundReviewWhoseRunFactsAreMissing() {
        ReviewAttemptRow found = mock(ReviewAttemptRow.class);
        when(found.getReviewId()).thenReturn(UUID.randomUUID());
        when(actorSelector.connectedProviderId(1L)).thenReturn(Optional.of(3L));
        when(jobRepository.findOwnScmReviewAttempts(eq(1L), eq(2L), eq(3L), any(), anyInt()))
                .thenReturn(List.of(found));
        when(reviewRuns.findFacts(anyLong(), any())).thenReturn(Map.of());
        var source = new ReviewAttemptsContentSource(jobRepository, reviewRuns, actorSelector, new ObjectMapper());

        assertThatThrownBy(() -> source.overview(1L, 2L)).isInstanceOf(EvidenceCollectionException.class);
    }
}
