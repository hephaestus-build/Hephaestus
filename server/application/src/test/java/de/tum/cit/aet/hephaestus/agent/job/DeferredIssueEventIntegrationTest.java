package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.RepositoryRef;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignalRepository;
import de.tum.cit.aet.hephaestus.integration.core.signal.DiscoveredVia;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRecorder;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewDetectionGate;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceResolver;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Import(DeferredIssueEventIntegrationTest.Configuration.class)
class DeferredIssueEventIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private ArtifactSignalRepository signals;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private Fixture fixture;

    @Autowired
    private SignalRecorder recorder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Workspace workspace;

    @BeforeEach
    void setUp() {
        workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace("deferred-event-" + UUID.randomUUID()));
        when(fixture.resolver().resolveAllForRepository("owner/repo")).thenReturn(List.of(workspace));
    }

    @Test
    void shouldCommitTheOccasionBeforeThePublishingTransactionReturns() {
        transactions.executeWithoutResult(status -> events.publishEvent(event()));
        assertThat(signals.findForArtifact(workspace.getId(), ScmSignals.ISSUE.value(), 42L))
                .singleElement()
                .satisfies(signal -> {
                    assertThat(signal.getState()).isEqualTo(SignalState.DEFERRED);
                    assertThat(signal.getDiscoveredVia()).isEqualTo(DiscoveredVia.EVENT);
                    assertThat(signal.getJobId()).isNull();
                });
    }

    @Test
    void shouldNotLeaveAnOccasionWhenThePublishingTransactionRollsBack() {
        transactions.executeWithoutResult(status -> {
            events.publishEvent(event());
            status.setRollbackOnly();
        });
        assertThat(signals.findForArtifact(workspace.getId(), ScmSignals.ISSUE.value(), 42L))
                .isEmpty();
    }

    @Test
    void shouldCommitOtherWorkspaceWhenOneDatabaseWriteFails() {
        Workspace other = workspaces.save(WorkspaceTestFixtures.activeWorkspace("other-event-" + UUID.randomUUID()));
        when(fixture.resolver().resolveAllForRepository("owner/repo")).thenReturn(List.of(workspace, other));
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        when(fixture.issueRepository().findByIdWithRepositoryAndAssignees(anyLong()))
                .thenAnswer(invocation -> {
                    if (attempts.getAndIncrement() == 0) {
                        jdbcTemplate.execute("INSERT INTO missing_issue_event_test_table VALUES (1)");
                    }
                    return java.util.Optional.empty();
                });
        var listener = new IssueAgentJobEventListener(
                mock(AgentJobService.class),
                fixture.issueRepository(),
                mock(PracticeReviewDetectionGate.class),
                fixture.resolver(),
                recorder,
                transactionManager);
        var updated = event();

        listener.onIssueCreated(new ScmDomainEvent.IssueCreated(updated.issue(), updated.context()));

        assertThat(signals.findForArtifact(workspace.getId(), ScmSignals.ISSUE.value(), 42L))
                .isEmpty();
        assertThat(signals.findForArtifact(other.getId(), ScmSignals.ISSUE.value(), 42L))
                .hasSize(1);
    }

    private ScmDomainEvent.IssueUpdated event() {
        var repository = new RepositoryRef(1L, "owner/repo", "main");
        var issue = new ScmEventPayload.IssueData(
                42L,
                1,
                "Title",
                "Body",
                Issue.State.OPEN,
                null,
                null,
                false,
                repository,
                null,
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
                workspace.getId(),
                repository,
                DataSource.WEBHOOK,
                "edited",
                UUID.randomUUID().toString(),
                null);
        return new ScmDomainEvent.IssueUpdated(issue, Set.of("title"), context);
    }

    record Fixture(WorkspaceResolver resolver, IssueRepository issueRepository) {}

    @TestConfiguration
    static class Configuration {
        @Bean
        Fixture deferredIssueFixture() {
            return new Fixture(mock(WorkspaceResolver.class), mock(IssueRepository.class));
        }

        @Bean
        IssueAgentJobEventListener deferredIssueListener(
                Fixture fixture,
                SignalRecorder recorder,
                org.springframework.transaction.PlatformTransactionManager transactionManager) {
            return new IssueAgentJobEventListener(
                    mock(AgentJobService.class),
                    fixture.issueRepository(),
                    mock(PracticeReviewDetectionGate.class),
                    fixture.resolver(),
                    recorder,
                    transactionManager);
        }
    }
}
