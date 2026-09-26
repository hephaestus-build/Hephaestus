package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.DeliveryContent;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.ValidatedObservation;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ExistingDeliveryLookup;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountPreferencesQuery;
import de.tum.cit.aet.hephaestus.core.settings.InstanceSettingsService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.AbstractPracticeReviewIntegrationTest;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatch;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.feedback.approval.ApprovedFeedbackReadyEvent;
import de.tum.cit.aet.hephaestus.practices.feedback.approval.FeedbackApprovalDecision;
import de.tum.cit.aet.hephaestus.practices.feedback.approval.dto.DecideFeedbackProposalRequestDTO;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.ObservationKind;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.testconfig.AdmittedReviewJobFixtures;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * An administrator approves a proposal on a GitLab merge request, and the note is posted from the approval itself.
 * The proposal is recorded the way a review records it, so the stored body carries its disclosure footer. From
 * the approval endpoint through the delivery gate and the dispatch ledger everything is the production path; the
 * provider write is the shared {@link PullRequestCommentPoster} double.
 */
class ApprovedFeedbackDeliveryIntegrationTest extends AbstractPracticeReviewIntegrationTest {

    private static final String NOTE = "The description says why the migration is split into two steps.";
    private static final String HEAD = "reviewed-head";

    @Autowired
    private PullRequestCommentPoster commentPoster;

    @Autowired
    private AccountPreferencesQuery accountPreferences;

    @Autowired
    private FeedbackLedgerRecorder ledgerRecorder;

    @Autowired
    private FeedbackDispatchRepository dispatchRepository;

    @Autowired
    private ApprovedFeedbackDeliveryListener delivery;

    @Autowired
    private ApprovedFeedbackRecovery recovery;

    @Autowired
    private InstanceSettingsService instanceSettings;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private RepositoryToMonitorRepository repositoryToMonitorRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private LlmConnectionRepository llmConnectionRepository;

    @Autowired
    private LlmModelRepository llmModelRepository;

    @Autowired
    private WorkspaceAgentBindingRepository workspaceAgentBindingRepository;

    @Autowired
    private LlmModelResolver llmModelResolver;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private boolean silenced;
    private Workspace workspace;
    private User developer;
    private Practice practice;
    private Repository repository;
    private long mergeRequestId;

    @BeforeEach
    void seedMergeRequest() {
        reset(commentPoster, accountPreferences);
        silenced = instanceSettings.get().isSilentModeEngaged();
        setSilentMode(false);

        workspace = createWorkspace(
                "approved-delivery", "Approved delivery", "approved-org", AccountType.ORG, persistUser("owner"));
        workspace.getFeatures().setPracticesEnabled(true);
        workspace = workspaceRepository.save(workspace);
        ensureAdminMembership(workspace);

        IdentityProvider gitlab = ensureGitLabProvider();
        developer = userRepository.save(TestUserFactory.createUser(700L, "approval-developer", gitlab));
        ensureWorkspaceMembership(workspace, developer, WorkspaceMembership.WorkspaceRole.MEMBER);
        repository = new Repository();
        repository.setNativeId(7001L);
        repository.setProvider(gitlab);
        repository.setName("api");
        repository.setNameWithOwner("acme/api");
        repository.setHtmlUrl("https://gitlab.com/acme/api");
        repository.setDefaultBranch("main");
        repository = repositoryRepository.save(repository);
        repositoryToMonitorRepository.save(WorkspaceTestFixtures.repositoryMonitor(workspace, "acme/api"));
        Instant now = Instant.now();
        pullRequestRepository.upsertCore(
                7101L,
                Objects.requireNonNull(gitlab.getId()),
                1,
                "Split the migration",
                "",
                "OPEN",
                null,
                "https://gitlab.com/acme/api/-/merge_requests/1",
                false,
                null,
                0,
                now,
                now,
                now,
                developer.getId(),
                repository.getId(),
                null,
                null,
                false,
                false,
                1,
                10,
                5,
                3,
                null,
                null,
                null,
                "feature/migration",
                "main",
                HEAD,
                "base",
                null,
                null);
        mergeRequestId = pullRequestRepository
                .findByRepositoryIdAndNumber(repository.getId(), 1)
                .orElseThrow()
                .getId();
        practice = persistPractice(workspace, null, "migration-rationale", "Explain the migration", null);
        practice.setAutonomy(PracticeAutonomy.HUMAN_APPROVAL);
        practiceRepository.saveAndFlush(practice);

        when(accountPreferences.practiceFeedbackDeliveryEnabled(anyLong())).thenReturn(true);
    }

    @AfterEach
    void restoreSilentMode() {
        setSilentMode(silenced);
    }

    @Test
    @WithAdminUser
    void shouldPostTheDisclosedNoteOnceWhenTheProposalIsApproved() {
        Feedback proposal = propose(HEAD);
        String body = Objects.requireNonNull(proposal.getBody());
        assertThat(body).startsWith(NOTE).endsWith("</sub>\n");
        when(commentPoster.findApprovedProposal(any(), eq(proposal.getId())))
                .thenReturn(ExistingDeliveryLookup.absent());
        when(commentPoster.postApprovedProposal(any(), eq(proposal.getId()), eq(body)))
                .thenReturn("gid://gitlab/Note/1");

        approve(proposal);

        verify(commentPoster).postApprovedProposal(any(), eq(proposal.getId()), eq(body));
        assertDelivered(proposal, "gid://gitlab/Note/1");
    }

    @Test
    @WithAdminUser
    void shouldSuppressAsStaleWithoutPostingWhenTheMergeRequestMovedAfterTheReview() {
        Feedback proposal = propose("earlier-head");

        approve(proposal);

        Feedback stored = stored(proposal);
        assertThat(stored.getDeliveryState()).isEqualTo(FeedbackDeliveryState.SUPPRESSED);
        assertThat(stored.getSuppressionReason()).isEqualTo(FeedbackSuppressionReason.APPROVAL_STALE);
        assertThat(dispatchRepository.findByDestinationKeyAndWorkspaceId(
                        "approved:" + proposal.getId(), workspace.getId()))
                .isEmpty();
        verifyNoInteractions(commentPoster);
    }

    @Test
    @WithAdminUser
    void shouldRecordTheLandedNoteWithoutPostingAgainWhenItsResponseWasLost() {
        Feedback proposal = propose(HEAD);
        when(commentPoster.findApprovedProposal(any(), eq(proposal.getId())))
                .thenReturn(ExistingDeliveryLookup.absent());
        when(commentPoster.postApprovedProposal(any(), eq(proposal.getId()), any()))
                .thenThrow(new JobDeliveryException("createNote transport error"));

        approve(proposal);

        assertThat(stored(proposal).getDeliveryState()).isEqualTo(FeedbackDeliveryState.PREPARED);
        assertThat(dispatch(proposal).getWriteStarted()).isTrue();
        assertThat(dispatch(proposal).getDeliveredExternalRef()).isNull();

        // GitLab kept the note; its marker now answers the lookup.
        when(commentPoster.findApprovedProposal(any(), eq(proposal.getId())))
                .thenReturn(ExistingDeliveryLookup.found("gid://gitlab/Note/1"));
        recoverNow(proposal);

        verify(commentPoster).postApprovedProposal(any(), eq(proposal.getId()), any());
        assertDelivered(proposal, "gid://gitlab/Note/1");
    }

    @Test
    @WithAdminUser
    void shouldKeepTheProposalRetryableWhenTheMarkerLookupIsInconclusive() {
        Feedback proposal = propose(HEAD);
        when(commentPoster.findApprovedProposal(any(), eq(proposal.getId())))
                .thenReturn(ExistingDeliveryLookup.unknown());

        approve(proposal);

        assertThat(stored(proposal).getDeliveryState()).isEqualTo(FeedbackDeliveryState.PREPARED);
        assertThat(dispatch(proposal).getState()).isEqualTo(FeedbackDispatchState.UNCERTAIN);
        assertThat(dispatch(proposal).getWriteStarted()).isFalse();
        verify(commentPoster, never()).postApprovedProposal(any(), any(), any());

        when(commentPoster.findApprovedProposal(any(), eq(proposal.getId())))
                .thenReturn(ExistingDeliveryLookup.absent());
        when(commentPoster.postApprovedProposal(any(), eq(proposal.getId()), any()))
                .thenReturn("gid://gitlab/Note/2");
        Workspace other = createWorkspace(
                "other-delivery", "Other delivery", "other-org", AccountType.ORG, persistUser("other-owner"));
        makeRetryDue(proposal);

        delivery.deliver(new ApprovedFeedbackReadyEvent(other.getId(), proposal.getId()));

        assertThat(dispatch(proposal).getAttemptCount()).isEqualTo(1);
        verify(commentPoster, never()).postApprovedProposal(any(), any(), any());

        recoverNow(proposal);

        assertDelivered(proposal, "gid://gitlab/Note/2");
    }

    /** Records the proposal a review of the merge request at {@code reviewedCommit} leaves for approval. */
    private Feedback propose(String reviewedCommit) {
        Instant now = Instant.now();
        AgentJob job = persistPullRequestReview(workspace, 1, now);
        job.setIntegrationKind(IntegrationKind.GITLAB);
        job.setConfigSnapshot(AdmittedReviewJobFixtures.snapshot(
                workspace,
                llmConnectionRepository,
                llmModelRepository,
                workspaceAgentBindingRepository,
                llmModelResolver,
                objectMapper));
        job.setMetadata(JsonNodeFactory.instance
                .objectNode()
                .put("pull_request_id", mergeRequestId)
                .put("repository_id", repository.getId())
                .put("pr_number", 1)
                .put("repository_full_name", "acme/api")
                .put("commit_sha", reviewedCommit));
        AgentJob review = agentJobRepository.save(job);
        UUID observation =
                observe(practice, review, mergeRequestId, developer, ObservationKind.DEMONSTRATED_STRENGTH, null, now);

        ledgerRecorder.recordProposal(
                review,
                new DeliveryContent(NOTE, List.of(), List.of()),
                List.of(new ValidatedObservation(
                        practice.getSlug(),
                        "Explains the split",
                        AssessmentStatus.ASSESSED,
                        Presence.PRESENT,
                        Assessment.GOOD,
                        null,
                        null,
                        null,
                        new ObservationKeys("occ-" + observation, null))));

        UUID proposalId = Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT id FROM feedback WHERE agent_job_id = ? AND workspace_id = ? AND channel = 'IN_CONTEXT'",
                UUID.class,
                review.getId(),
                workspace.getId()));
        return stored(proposalId);
    }

    private void approve(Feedback proposal) {
        webTestClient
                .put()
                .uri(
                        "/workspaces/{slug}/practices/reviews/feedback/{feedbackId}/approval",
                        workspace.getWorkspaceSlug(),
                        proposal.getId())
                .headers(TestAuthUtils.withCurrentUser())
                .bodyValue(new DecideFeedbackProposalRequestDTO(FeedbackApprovalDecision.APPROVED, null, null))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
    }

    private void makeRetryDue(Feedback proposal) {
        jdbcTemplate.update(
                "UPDATE feedback_dispatch SET next_attempt_at = CURRENT_TIMESTAMP WHERE id = ?",
                dispatch(proposal).getId());
    }

    /** Runs recovery with the retry due; a scheduled run may hold the lock briefly, and a skipped call is retried. */
    private void recoverNow(Feedback proposal) {
        makeRetryDue(proposal);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            recovery.recover();
            assertThat(stored(proposal).getDeliveryState()).isEqualTo(FeedbackDeliveryState.DELIVERED);
        });
    }

    private void assertDelivered(Feedback proposal, String noteRef) {
        assertThat(stored(proposal).getDeliveryState()).isEqualTo(FeedbackDeliveryState.DELIVERED);
        FeedbackDispatch dispatch = dispatch(proposal);
        assertThat(dispatch.getState()).isEqualTo(FeedbackDispatchState.SENT);
        assertThat(dispatch.getDeliveredExternalRef()).isEqualTo(noteRef);
        assertThat(jdbcTemplate.queryForList(
                        "SELECT placement_type, posted_comment_ref FROM feedback_placement WHERE feedback_id = ?",
                        proposal.getId()))
                .containsExactly(Map.of("placement_type", "SUMMARY", "posted_comment_ref", noteRef));
    }

    private void setSilentMode(boolean engaged) {
        var current = instanceSettings.get();
        if (current.isSilentModeEngaged() != engaged) {
            instanceSettings.updateSilentMode(
                    engaged,
                    engaged ? "approved delivery test" : null,
                    "approved-delivery-test",
                    EntityTagPrecondition.parse("\"" + current.getVersion() + "\""));
        }
    }

    private Feedback stored(Feedback proposal) {
        return stored(proposal.getId());
    }

    private Feedback stored(UUID feedbackId) {
        return feedbackRepository
                .findByIdAndWorkspaceId(feedbackId, workspace.getId())
                .orElseThrow();
    }

    private FeedbackDispatch dispatch(Feedback proposal) {
        return dispatchRepository
                .findByDestinationKeyAndWorkspaceId("approved:" + proposal.getId(), workspace.getId())
                .orElseThrow();
    }
}
