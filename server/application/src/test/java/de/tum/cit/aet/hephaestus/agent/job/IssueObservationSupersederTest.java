package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.RepositoryRef;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class IssueObservationSupersederTest extends BaseUnitTest {
    private final IssueRepository issues = mock(IssueRepository.class);
    private final ObservationRepository observations = mock(ObservationRepository.class);
    private final IssueObservationSuperseder superseder = new IssueObservationSuperseder(issues, observations);

    @Test
    void shouldRetireEarlierClaimsOnEachTransitionIncludingReturnToTheSameContent() {
        when(issues.advanceReviewSnapshot(eq(42L), any(), any())).thenReturn(1);

        superseder.onUpdated(update("first", Set.of("title")));
        superseder.onUpdated(update("second", Set.of("title")));
        superseder.onUpdated(update("first", Set.of("title")));

        ArgumentCaptor<UUID> versions = ArgumentCaptor.forClass(UUID.class);
        verify(issues, org.mockito.Mockito.times(3)).advanceReviewSnapshot(eq(42L), versions.capture(), any());
        assertThat(versions.getAllValues()).doesNotHaveDuplicates();
        verify(observations, org.mockito.Mockito.times(3)).supersedeIssueObservations(eq(42L), any(Instant.class));
    }

    @Test
    void shouldNotRetireClaimsForAnUnrelatedMirrorEdit() {
        superseder.onUpdated(update("first", Set.of("commentsCount")));
        verify(issues, never()).advanceReviewSnapshot(any(Long.class), any(), any());
        verify(observations, never()).supersedeIssueObservations(any(Long.class), any());
    }

    private static ScmDomainEvent.IssueUpdated update(String title, Set<String> fields) {
        RepositoryRef repository = new RepositoryRef(1L, "owner/repo", "main");
        var issue = new ScmEventPayload.IssueData(
                42L,
                1,
                title,
                "body",
                Issue.State.OPEN,
                null,
                null,
                false,
                repository,
                123L,
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                null);
        var context = new EventContext(
                UUID.randomUUID(),
                Instant.now(),
                7L,
                repository,
                de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource.WEBHOOK,
                "edited",
                "test",
                null);
        return new ScmDomainEvent.IssueUpdated(issue, fields, context);
    }
}
