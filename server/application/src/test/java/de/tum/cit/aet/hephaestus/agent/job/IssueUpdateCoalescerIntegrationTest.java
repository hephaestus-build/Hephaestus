package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignalRepository;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalKey;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRecorder;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRevision;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalState;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.review.GateDecision;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewDetectionGate;
import de.tum.cit.aet.hephaestus.practices.review.TriggerMode;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceResolver;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(OutputCaptureExtension.class)
@Import(IssueUpdateCoalescerIntegrationTest.Configuration.class)
// Bound lock waits so incorrect transaction propagation fails instead of hanging the suite.
@TestPropertySource(properties = "spring.datasource.hikari.connection-init-sql=SET lock_timeout = '2s'")
class IssueUpdateCoalescerIntegrationTest extends BaseIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");

    @Autowired
    private ArtifactSignalRepository signals;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private SignalRecorder recorder;

    @Autowired
    private IssueSignalResubmitter submitter;

    @Autowired
    private Fixture fixture;

    @Autowired
    private AgentJobRepository jobs;

    @Autowired
    private WorkspaceAgentBindingRepository bindings;

    @Autowired
    private LlmConnectionRepository connections;

    @Autowired
    private LlmModelRepository models;

    @Autowired
    private PracticeRepository practices;

    @Autowired
    private de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository scmConnections;

    private Workspace workspace;
    private IssueUpdateCoalescer coalescer;
    private SignalKey current;
    private Issue issue;

    @BeforeEach
    void setUp() {
        String slug = "coalescer-" + UUID.randomUUID();
        workspace = WorkspaceTestFixtures.persistInstallationWorkspace(
                workspaces,
                scmConnections,
                WorkspaceTestFixtures.installationWorkspace(42L, slug).withSlug(slug),
                42L);
        workspace.getFeatures().setPracticesEnabled(true);
        workspace = workspaces.save(workspace);

        Practice practice = new Practice();
        practice.setWorkspace(workspace);
        practice.setSlug(slug);
        practice.setName("Issue metadata review");
        practice.setCriteria("Review the issue");
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.forArtifact(ScmSignals.ISSUE));
        practice.setBindings(PracticeTestEvidence.bindings(ScmSignals.ISSUE_UPDATED));
        practices.save(practice);

        var connection = connections.save(LlmCatalogTestFixtures.connection(slug));
        var model = models.save(LlmCatalogTestFixtures.model(connection, slug, "test-model"));
        WorkspaceAgentBinding binding = new WorkspaceAgentBinding();
        binding.setWorkspace(workspace);
        binding.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        binding.setEnabled(true);
        binding.setInstanceModel(model);
        binding.setTimeoutSeconds(300);
        bindings.save(binding);
        Repository repository = new Repository();
        repository.setId(1L);
        repository.setNameWithOwner("owner/repo");
        issue = new Issue();
        issue.setId(workspace.getId());
        issue.setNumber(1);
        issue.setTitle("Current title");
        issue.setState(Issue.State.OPEN);
        issue.setRepository(repository);
        when(fixture.issues().findByIdWithRepositoryAndAssignees(issue.getId())).thenReturn(Optional.of(issue));
        when(fixture.gate().evaluateIssue(issue, ScmSignals.ISSUE_UPDATED, TriggerMode.AUTO))
                .thenReturn(new GateDecision.Detect(workspace, List.of(), 0L, TriggerMode.AUTO));
        when(fixture.workspaceResolver().resolveForRepository("owner/repo")).thenReturn(Optional.of(workspace));
        current = ScmSignals.issueKey(
                        workspace.getId(), ScmSignals.ISSUE_UPDATED, ScmEventPayload.IssueData.from(issue))
                .orElseThrow();
        SignalKey intermediate =
                new SignalKey(workspace.getId(), issue.getId(), ScmSignals.ISSUE_UPDATED, new SignalRevision("before"));
        transactions.executeWithoutResult(status -> {
            signals.insertDeferred(intermediate, UUID.randomUUID(), NOW.minusSeconds(60), NOW.minusSeconds(60));
            signals.insertDeferred(current, UUID.randomUUID(), NOW.minusSeconds(30), NOW.minusSeconds(30));
        });
        coalescer = new IssueUpdateCoalescer(
                signals, fixture.issues(), recorder, submitter, fixture.workspaceResolver(), transactions);
    }

    @Test
    void shouldCommitIntermediateSuppressionAndTheSubmittedJobTogether(CapturedOutput output) {
        transactions.executeWithoutResult(status -> {
            coalescer.drain(workspace.getId(), current.artifactId(), NOW);
            assertThat(output).doesNotContain("agent.job.queued");
        });
        assertThat(output).containsOnlyOnce("agent.job.queued");

        assertThat(signals.findForArtifact(workspace.getId(), ScmSignals.ISSUE.value(), current.artifactId()))
                .hasSize(2)
                .allSatisfy(signal -> {
                    if (signal.key().equals(current)) {
                        assertThat(signal.getState()).isEqualTo(SignalState.TRIGGERED);
                        AgentJob job = jobs.findByIdAndWorkspaceId(
                                        Objects.requireNonNull(signal.getJobId()), workspace.getId())
                                .orElseThrow();
                        assertThat(job.getStatus()).isEqualTo(AgentJobStatus.QUEUED);
                        assertThat(Objects.requireNonNull(job.getMetadata())
                                        .get(AgentJob.SIGNAL_REVISION_METADATA_KEY)
                                        .asString())
                                .isEqualTo(current.revision().value());
                    } else {
                        assertThat(signal.getState()).isEqualTo(SignalState.SUPPRESSED);
                        assertThat(signal.getStateReason()).isEqualTo(SignalStateReason.COALESCED);
                    }
                });
    }

    @Test
    void shouldRefuseTheWholeGroupRatherThanReviewUnderAWorkspaceThatNoLongerOwnsTheRepository() {
        Workspace other = WorkspaceTestFixtures.activeWorkspace("coalescer2-" + UUID.randomUUID());
        other.getFeatures().setPracticesEnabled(true);
        other = workspaces.save(other);
        // The repository was re-keyed to another workspace inside the quiet window (ADR 0024 § re-keying).
        when(fixture.workspaceResolver().resolveForRepository("owner/repo")).thenReturn(Optional.of(other));

        transactions.executeWithoutResult(status -> coalescer.drain(workspace.getId(), current.artifactId(), NOW));

        assertThat(jobs.findListRows(workspace.getId(), null, Pageable.unpaged()))
                .isEmpty();
        assertThat(jobs.findListRows(other.getId(), null, Pageable.unpaged())).isEmpty();
        assertThat(signals.findForArtifact(workspace.getId(), ScmSignals.ISSUE.value(), current.artifactId()))
                .hasSize(2)
                .allSatisfy(signal -> {
                    assertThat(signal.getState()).isEqualTo(SignalState.SUPPRESSED);
                    assertThat(signal.getStateReason()).isEqualTo(SignalStateReason.OUT_OF_REVIEW_SCOPE);
                });
    }

    @Test
    void shouldHoldTheWholeGroupRatherThanReviewATombstonedIssue() {
        // Sync can lift the tombstone before the next occasion, so the group is held, not retired.
        issue.setDeletedAt(NOW);

        transactions.executeWithoutResult(status -> coalescer.drain(workspace.getId(), current.artifactId(), NOW));

        assertThat(jobs.findListRows(workspace.getId(), null, Pageable.unpaged()))
                .isEmpty();
        assertThat(signals.findForArtifact(workspace.getId(), ScmSignals.ISSUE.value(), current.artifactId()))
                .hasSize(2)
                .allSatisfy(signal -> {
                    assertThat(signal.getState()).isEqualTo(SignalState.PENDING);
                    assertThat(signal.getStateReason()).isEqualTo(SignalStateReason.ARTIFACT_NOT_VISIBLE);
                });
    }

    @Test
    void shouldRollBackTheWholeBurstWhenSettlementFailsAfterSubmissionReturns(CapturedOutput output) {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                    coalescer.drain(workspace.getId(), current.artifactId(), NOW);
                    assertThat(jobs.findListRows(workspace.getId(), null, Pageable.unpaged()))
                            .hasSize(1);
                    throw new IllegalStateException("Settlement failed after admission");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Settlement failed after admission");
        assertThat(output).doesNotContain("agent.job.queued");
        assertThat(jobs.findListRows(workspace.getId(), null, Pageable.unpaged()))
                .isEmpty();

        assertThat(signals.findForArtifact(workspace.getId(), ScmSignals.ISSUE.value(), current.artifactId()))
                .hasSize(2)
                .allSatisfy(signal -> {
                    assertThat(signal.getState()).isEqualTo(SignalState.DEFERRED);
                    assertThat(signal.getStateReason()).isNull();
                    assertThat(signal.getJobId()).isNull();
                });

        transactions.executeWithoutResult(status -> coalescer.drain(workspace.getId(), current.artifactId(), NOW));
        transactions.executeWithoutResult(status -> coalescer.drain(workspace.getId(), current.artifactId(), NOW));
        assertThat(output).containsOnlyOnce("agent.job.queued");
        assertThat(jobs.findListRows(workspace.getId(), null, Pageable.unpaged()))
                .singleElement()
                .satisfies(job -> assertThat(signals.findForArtifact(
                                workspace.getId(), ScmSignals.ISSUE.value(), current.artifactId()))
                        .filteredOn(signal -> signal.key().equals(current))
                        .singleElement()
                        .satisfies(signal -> {
                            assertThat(signal.getState()).isEqualTo(SignalState.TRIGGERED);
                            assertThat(signal.getJobId()).isEqualTo(job.getId());
                        }));
    }

    record Fixture(IssueRepository issues, PracticeReviewDetectionGate gate, WorkspaceResolver workspaceResolver) {}

    @TestConfiguration
    static class Configuration {
        @Bean
        Fixture coalescerFixture() {
            return new Fixture(
                    mock(IssueRepository.class),
                    mock(PracticeReviewDetectionGate.class),
                    mock(WorkspaceResolver.class));
        }

        @Bean
        IssueSignalResubmitter coalescerSubmitter(AgentJobService jobs, Fixture fixture, SignalRecorder recorder) {
            return new IssueSignalResubmitter(jobs, fixture.issues(), fixture.gate(), recorder);
        }
    }
}
