package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.DeliveryContent;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.ValidatedObservation;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountPreferencesQuery;
import de.tum.cit.aet.hephaestus.core.settings.InstanceSettingsService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.github.feedback.ScriptedGithubComments;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback.ScriptedGitlabNotes;
import de.tum.cit.aet.hephaestus.practices.AbstractPracticeReviewIntegrationTest;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatch;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.feedback.approval.FeedbackApprovalDecision;
import de.tum.cit.aet.hephaestus.practices.feedback.approval.dto.DecideFeedbackProposalRequestDTO;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.ObservationKind;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.testconfig.AdmittedReviewJobFixtures;
import de.tum.cit.aet.hephaestus.testconfig.ScriptedCommentThreads;
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
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * An administrator approves a proposal on a merge request or an issue, and the note is posted from the approval
 * itself. The proposal is recorded the way a review records it, so the stored body carries its disclosure footer.
 * From the approval endpoint through the delivery gate, the dispatch ledger and the real GitLab and GitHub summary
 * channels everything is the production path; only the providers' GraphQL answers are scripted.
 */
class ApprovedFeedbackDeliveryIntegrationTest extends AbstractPracticeReviewIntegrationTest {

    private static final String NOTE = "The description says why the migration is split into two steps.";
    private static final String HEAD = "reviewed-head";
    private static final int ISSUE = 5;

    @Autowired
    private PullRequestCommentPoster commentPoster;

    @Autowired
    private AccountPreferencesQuery accountPreferences;

    @Autowired
    private FeedbackLedgerRecorder ledgerRecorder;

    @Autowired
    private FeedbackDispatchRepository dispatchRepository;

    @Autowired
    private ApprovedFeedbackRecovery recovery;

    @Autowired
    private PracticeFeedbackDispatchService dispatchService;

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
    private IssueRepository issueRepository;

    @Autowired
    private OutboundEgressGuard egressGuard;

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
    private ScriptedGitlabNotes gitlab;
    private ScriptedGithubComments github;

    @BeforeEach
    void seedMergeRequest() {
        reset(commentPoster, accountPreferences);
        gitlab = new ScriptedGitlabNotes(egressGuard);
        github = new ScriptedGithubComments(egressGuard);
        var poster = new PullRequestCommentPoster(List.of(gitlab.channel(), github.channel()));
        doAnswer(delegatesTo(poster)).when(commentPoster).summaryWrite(any(), anyBoolean(), anyString(), anyString());
        doAnswer(delegatesTo(poster)).when(commentPoster).findExisting(any());
        doAnswer(delegatesTo(poster)).when(commentPoster).post(any());
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

        approve(proposal);

        String mergeRequest = ScriptedGitlabNotes.mergeRequest(1);
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertDelivered(proposal, onlyNote(gitlab.threads(), mergeRequest, proposal)));
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
        gitlab.threads().loseNextWriteResponse();

        approve(proposal);

        assertThat(stored(proposal).getDeliveryState()).isEqualTo(FeedbackDeliveryState.PREPARED);
        assertThat(dispatch(proposal).getWriteStarted()).isTrue();
        assertThat(dispatch(proposal).getDeliveredExternalRef()).isNull();

        // GitLab kept the note; its marker now answers the lookup.
        recoverNow(proposal);

        assertDelivered(proposal, onlyNote(gitlab.threads(), ScriptedGitlabNotes.mergeRequest(1), proposal));
    }

    @Test
    @WithAdminUser
    void shouldKeepTheProposalRetryableWhenTheMarkerLookupIsInconclusive() {
        Feedback proposal = propose(HEAD);
        gitlab.threads().failLookups(true);

        approve(proposal);

        assertThat(stored(proposal).getDeliveryState()).isEqualTo(FeedbackDeliveryState.PREPARED);
        assertThat(dispatch(proposal).getState()).isEqualTo(FeedbackDispatchState.UNCERTAIN);
        assertThat(dispatch(proposal).getWriteStarted()).isFalse();
        assertThat(gitlab.threads().on(ScriptedGitlabNotes.mergeRequest(1))).isEmpty();

        gitlab.threads().failLookups(false);
        recoverNow(proposal);

        assertDelivered(proposal, onlyNote(gitlab.threads(), ScriptedGitlabNotes.mergeRequest(1), proposal));
    }

    @Test
    @WithAdminUser
    void shouldPostApprovedFeedbackOnceOnTheGitLabIssue() {
        shouldPostApprovedFeedbackOnceOnTheIssue(onGitLab());
    }

    @Test
    @WithAdminUser
    void shouldPostApprovedFeedbackOnceOnTheGitHubIssue() {
        shouldPostApprovedFeedbackOnceOnTheIssue(onGitHub());
    }

    @Test
    @WithAdminUser
    void shouldRecordALostGitLabIssueNoteWithoutPostingAgain() {
        shouldRecordALostIssueNoteWithoutPostingAgain(onGitLab());
    }

    @Test
    @WithAdminUser
    void shouldRecordALostGitHubIssueCommentWithoutPostingAgain() {
        shouldRecordALostIssueNoteWithoutPostingAgain(onGitHub());
    }

    private void shouldPostApprovedFeedbackOnceOnTheIssue(IssueHost host) {
        Feedback proposal = proposeOnIssue(host);

        approve(proposal);

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertDelivered(proposal, onlyNote(host.threads(), host.thread(), proposal)));
        recovery.recover();
        assertDelivered(proposal, onlyNote(host.threads(), host.thread(), proposal));
    }

    private void shouldRecordALostIssueNoteWithoutPostingAgain(IssueHost host) {
        Feedback proposal = proposeOnIssue(host);
        host.threads().loseNextWriteResponse();

        approve(proposal);

        assertThat(stored(proposal).getDeliveryState()).isEqualTo(FeedbackDeliveryState.PREPARED);
        assertThat(dispatch(proposal).getState()).isEqualTo(FeedbackDispatchState.UNCERTAIN);
        assertThat(dispatch(proposal).getWriteStarted()).isTrue();

        recoverNow(proposal);

        assertDelivered(proposal, onlyNote(host.threads(), host.thread(), proposal));
    }

    @Test
    @WithAdminUser
    void shouldPostApprovedGitLabIssueFeedbackOnceAfterTheIssueFailedToResolve() {
        shouldPostApprovedFeedbackOnceAfterTheIssueFailedToResolve(onGitLab());
    }

    @Test
    @WithAdminUser
    void shouldPostApprovedGitHubIssueFeedbackOnceAfterTheIssueFailedToResolve() {
        shouldPostApprovedFeedbackOnceAfterTheIssueFailedToResolve(onGitHub());
    }

    @Test
    void shouldPostAnAutomaticGitLabIssuePackageOnceAfterTheIssueFailedToResolve() {
        shouldPostAnAutomaticPackageOnceAfterTheIssueFailedToResolve(onGitLab());
    }

    @Test
    void shouldPostAnAutomaticGitHubIssuePackageOnceAfterTheIssueFailedToResolve() {
        shouldPostAnAutomaticPackageOnceAfterTheIssueFailedToResolve(onGitHub());
    }

    private void shouldPostApprovedFeedbackOnceAfterTheIssueFailedToResolve(IssueHost host) {
        Feedback proposal = proposeOnIssue(host);
        host.threads().failNextResolution();

        approve(proposal);

        assertThat(stored(proposal).getDeliveryState()).isEqualTo(FeedbackDeliveryState.PREPARED);
        assertThat(dispatch(proposal).getState()).isEqualTo(FeedbackDispatchState.UNCERTAIN);
        assertThat(dispatch(proposal).getWriteStarted()).isFalse();
        assertThat(host.threads().on(host.thread())).isEmpty();

        recoverNow(proposal);

        assertDelivered(proposal, onlyNote(host.threads(), host.thread(), proposal));
    }

    private void shouldPostAnAutomaticPackageOnceAfterTheIssueFailedToResolve(IssueHost host) {
        practice.setAutonomy(PracticeAutonomy.AUTOMATIC);
        practiceRepository.saveAndFlush(practice);
        AgentJob review = reviewIssue(host);
        var reviewPackage = new DeliveryContent(NOTE, List.of(), List.of());
        Set<String> practices = Set.of(practice.getSlug());
        host.threads().failNextResolution();

        var held = dispatchService.dispatchAutomaticPackage(review, reviewPackage, practices);

        assertThat(held.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.UNCERTAIN);
        FeedbackDispatch unsent = dispatchService.automaticPackage(review);
        assertThat(unsent.getWriteStarted()).isFalse();
        assertThat(host.threads().on(host.thread())).isEmpty();

        jdbcTemplate.update(
                "UPDATE feedback_dispatch SET next_attempt_at = CURRENT_TIMESTAMP WHERE id = ?", unsent.getId());
        // A scheduled recovery pass may claim the due dispatch first; either way it converges on one note.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            dispatchService.dispatchAutomaticPackage(review, reviewPackage, practices);
            assertThat(dispatchService.automaticPackage(review).getState()).isEqualTo(FeedbackDispatchState.SENT);
        });
        assertThat(dispatchService.automaticPackage(review).getDeliveredExternalRef())
                .isEqualTo(onlyNote(host.threads(), host.thread(), PullRequestCommentPoster.summaryMarkerFor(review)));
    }

    private static String onlyNote(ScriptedCommentThreads threads, String thread, Feedback proposal) {
        return onlyNote(threads, thread, PullRequestCommentPoster.approvedFeedbackMarker(proposal.getId()));
    }

    private static String onlyNote(ScriptedCommentThreads threads, String thread, String marker) {
        assertThat(threads.on(thread)).hasSize(1);
        ScriptedCommentThreads.Comment note = threads.on(thread).getFirst();
        assertThat(note.body()).startsWith(NOTE).endsWith(marker);
        return note.id();
    }

    /** Records the proposal a review of the merge request at {@code reviewedCommit} leaves for approval. */
    private Feedback propose(String reviewedCommit) {
        Instant now = Instant.now();
        AgentJob job = persistPullRequestReview(workspace, 1, now);
        job.setIntegrationKind(repository.getProvider().getType().kind());
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
        return recordProposal(review, observation);
    }

    /** The issue a review is about, on the provider hosting its repository, and the thread its feedback lands on. */
    private record IssueHost(Repository repository, User author, ScriptedCommentThreads threads, String thread) {}

    private IssueHost onGitLab() {
        return new IssueHost(repository, developer, gitlab.threads(), ScriptedGitlabNotes.issue(ISSUE));
    }

    private IssueHost onGitHub() {
        IdentityProvider provider = ensureGitHubProvider();
        User author = userRepository.save(TestUserFactory.createUser(701L, "approval-github-developer", provider));
        ensureWorkspaceMembership(workspace, author, WorkspaceMembership.WorkspaceRole.MEMBER);
        Repository hosted = new Repository();
        hosted.setNativeId(7002L);
        hosted.setProvider(provider);
        hosted.setName("web");
        hosted.setNameWithOwner("acme/web");
        hosted.setHtmlUrl("https://github.com/acme/web");
        hosted.setDefaultBranch("main");
        hosted = repositoryRepository.save(hosted);
        repositoryToMonitorRepository.save(WorkspaceTestFixtures.repositoryMonitor(workspace, "acme/web"));
        return new IssueHost(hosted, author, github.threads(), ScriptedGithubComments.issue(ISSUE));
    }

    private Feedback proposeOnIssue(IssueHost host) {
        AgentJob review = reviewIssue(host);
        UUID observation = observe(
                practice,
                review,
                ArtifactKinds.ISSUE.value(),
                Objects.requireNonNull(review.getMetadata()).path("issue_id").asLong(),
                host.author(),
                null,
                ObservationKind.DEMONSTRATED_STRENGTH,
                null,
                Instant.now(),
                DIFF_EVIDENCE_JSON,
                null);
        return recordProposal(review, observation);
    }

    private AgentJob reviewIssue(IssueHost host) {
        Instant now = Instant.now();
        Repository hosted = host.repository();
        issueRepository.upsertCore(
                7201L,
                Objects.requireNonNull(hosted.getProvider().getId()),
                ISSUE,
                "Why is the migration split?",
                "",
                "OPEN",
                null,
                hosted.getHtmlUrl() + "/issues/" + ISSUE,
                false,
                null,
                0,
                now,
                now,
                now,
                host.author().getId(),
                hosted.getId(),
                null,
                null,
                null,
                null,
                null,
                null);
        long issueId = issueRepository
                .findByRepositoryIdAndNumber(hosted.getId(), ISSUE)
                .orElseThrow()
                .getId();
        UUID snapshot = UUID.randomUUID();
        jdbcTemplate.update("UPDATE issue SET review_snapshot_id = ? WHERE id = ?", snapshot, issueId);

        AgentJob job = new AgentJob();
        job.setWorkspace(workspace);
        job.setJobType(AgentJobType.ISSUE_REVIEW);
        // Production takes the kind from the workspace connection to the provider that hosts the repository.
        job.setIntegrationKind(hosted.getProvider().getType().kind());
        job.setCompletedAt(now);
        job.setEvidenceSnapshot(objectMapper.valueToTree(Map.of("manifest", Map.of("contractVersion", "1.2.0"))));
        job.setConfigSnapshot(AdmittedReviewJobFixtures.snapshot(
                workspace,
                llmConnectionRepository,
                llmModelRepository,
                workspaceAgentBindingRepository,
                llmModelResolver,
                objectMapper));
        job.setMetadata(JsonNodeFactory.instance
                .objectNode()
                .put("artifact_kind", ArtifactKinds.ISSUE.value())
                .put("repository_id", hosted.getId())
                .put("repository_full_name", hosted.getNameWithOwner())
                .put("issue_id", issueId)
                .put("issue_number", ISSUE)
                .put("state", "open")
                .put("review_snapshot_id", snapshot.toString()));
        return agentJobRepository.save(job);
    }

    private Feedback recordProposal(AgentJob review, UUID observation) {
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
