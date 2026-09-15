package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.conversation.ConversationSourceLiveness;
import de.tum.cit.aet.hephaestus.agent.documentation.DocumentProjection;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountPreferencesQuery;
import de.tum.cit.aet.hephaestus.core.settings.spi.SilentModeQuery;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewSubject;
import de.tum.cit.aet.hephaestus.integration.scm.ReviewTargetQuery;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyEvaluationCommand;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyEvaluationRecorder;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyStage;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicySurface;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.feedback.approval.FeedbackApproval;
import de.tum.cit.aet.hephaestus.practices.feedback.approval.FeedbackApprovalDecision;
import de.tum.cit.aet.hephaestus.practices.feedback.approval.FeedbackApprovalRepository;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewCoverageService;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.practices.review.ReviewSubjectStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.settings.PracticeDeliveryStatus;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewPersonMode;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewRepositoryMode;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

class PracticeFeedbackDeliveryPolicyTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 3L;
    private static final long PULL_REQUEST_ID = 41L;
    private static final long REPOSITORY_ID = 42L;
    private static final long AUTHOR_ID = 43L;
    private static final long REVIEWER_ID = 44L;
    private static final long REVIEW_ID = 45L;

    @Mock
    private ConversationSourceLiveness conversationSourceLiveness;

    @Mock
    private DocumentProjection documentProjection;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private SilentModeQuery silentModeQuery;

    @Mock
    private DeliveryPolicyEvaluationRecorder evaluationRecorder;

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private PullRequestRepository pullRequestRepository;

    @Mock
    private ReviewTargetQuery reviewTargets;

    @Mock
    private RepositoryToMonitorRepository repositoryToMonitorRepository;

    @Mock
    private AccountPreferencesQuery accountPreferencesQuery;

    @Mock
    private PracticeReviewCoverageService coverageService;

    @Mock
    private PracticeRepository practiceRepository;

    @Mock
    private FeedbackApprovalRepository approvalRepository;

    @Test
    void compositionIsAllowedBeforeAnyPracticeSetIsKnown() {
        AgentJob job = conversationJob();

        assertThat(policy().allowsComposition(job, DeliveryPolicySurface.IN_APP))
                .isTrue();
        assertThat(policy().allowsComposition(job, DeliveryPolicySurface.CONVERSATION))
                .isTrue();
    }

    @Test
    void repositorylessCompositionStillRequiresCurrentCoverageAndConsent() {
        AgentJob job = conversationJob();
        when(coverageService.assessRepositoryless(any(), any())).thenReturn(coverage(false));

        assertThat(policy().allowsComposition(job, DeliveryPolicySurface.CONVERSATION))
                .isFalse();
        assertThat(recordedRefusal()).isEqualTo(FeedbackSuppressionReason.OUTSIDE_CURRENT_COVERAGE);
    }

    @Test
    void repositorylessCompositionHonorsTheRecipientsCurrentPreference() {
        AgentJob job = conversationJob();
        when(accountPreferencesQuery.practiceFeedbackDeliveryEnabled(AUTHOR_ID)).thenReturn(false);

        assertThat(policy().allowsComposition(job, DeliveryPolicySurface.CONVERSATION))
                .isFalse();
        assertThat(recordedRefusal()).isEqualTo(FeedbackSuppressionReason.RECIPIENT_OPTED_OUT);
    }

    @Test
    void silentModeStopsWhatLeavesTheInstanceAndLeavesTheDevelopersOwnPageAlone() {
        AgentJob job = conversationJob();
        when(silentModeQuery.isSilentModeEngaged()).thenReturn(true);

        assertThat(policy().allowsComposition(job, DeliveryPolicySurface.CONVERSATION))
                .isFalse();
        assertThat(policy().allowsComposition(job, DeliveryPolicySurface.IN_APP))
                .isTrue();
    }

    @ParameterizedTest
    @CsvSource({"scm.issue,true", "scm.issue,false", "scm.pull_request,true", "scm.pull_request,false"})
    void shouldEvaluateScmInAppCompositionWithoutExternalSilentModeDenial(String kind, boolean consent) {
        var job = pullRequestJob();
        job.setArtifactKind(ArtifactKind.of(kind));
        var work = openPullRequest();
        if ("scm.issue".equals(kind)) {
            var metadata =
                    tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode();
            metadata.put("issue_id", PULL_REQUEST_ID);
            metadata.put("issue_number", 17);
            metadata.put("repository_id", REPOSITORY_ID);
            metadata.put("repository_full_name", "owner/repo");
            job.setMetadata(metadata);
            Issue issue = new Issue();
            issue.setId(work.getId());
            issue.setNumber(work.getNumber());
            issue.setAuthor(work.getAuthor());
            issue.setRepository(work.getRepository());
            issue.setState(work.getState());
            when(issueRepository.findByIdWithAuthorAndRepository(PULL_REQUEST_ID))
                    .thenReturn(Optional.of(issue));
            when(repositoryToMonitorRepository.existsByWorkspaceIdAndNameWithOwner(WORKSPACE_ID, "owner/repo"))
                    .thenReturn(true);
            when(coverageService.assess(any(), eq("owner/repo"), eq(null), any(), eq(false)))
                    .thenReturn(coverage(true));
        } else {
            stubPullRequestEvaluation(work, coverage(true));
        }
        when(silentModeQuery.isSilentModeEngaged()).thenReturn(true);
        when(accountPreferencesQuery.practiceFeedbackDeliveryEnabled(AUTHOR_ID)).thenReturn(consent);
        assertThat(policy().allowsComposition(job, DeliveryPolicySurface.IN_APP))
                .isEqualTo(consent);
        if (!consent) assertThat(recordedRefusal()).isEqualTo(FeedbackSuppressionReason.RECIPIENT_OPTED_OUT);
    }

    @Test
    void shouldStopConversationWithPauseReasonButKeepInAppReadableWhenSendingIsPaused() {
        AgentJob job = conversationJob();
        job.getWorkspace().getReviewSettings().setDeliveryStatus(PracticeDeliveryStatus.PAUSED);
        job.setPracticeRolloutRevision(job.getWorkspace().getReviewSettings().getRolloutRevision());

        assertThat(policy().allowsComposition(job, DeliveryPolicySurface.CONVERSATION))
                .isFalse();
        assertThat(recordedRefusal()).isEqualTo(FeedbackSuppressionReason.WORKSPACE_DELIVERY_PAUSED);
        assertThat(policy().allowsComposition(job, DeliveryPolicySurface.IN_APP))
                .isTrue();
    }

    @Test
    void artifactCompositionMustUseTheTypedEntryPoint() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                        () -> policy().allowsComposition(conversationJob(), DeliveryPolicySurface.ARTIFACT)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aJobFromAnOlderRolloutIsRefusedForTheRevisionAndNotForThePause() {
        AgentJob job = conversationJob();
        job.getWorkspace().getReviewSettings().setDeliveryStatus(PracticeDeliveryStatus.ACTIVE);
        job.setPracticeRolloutRevision(job.getWorkspace().getReviewSettings().getRolloutRevision() - 1);

        assertThat(policy().allowsComposition(job, DeliveryPolicySurface.CONVERSATION))
                .isFalse();
        assertThat(recordedRefusal()).isEqualTo(FeedbackSuppressionReason.STALE_ROLLOUT_REVISION);
    }

    @Test
    void explicitApprovalMayReleaseAnOlderProposalAfterResume() {
        AgentJob job = pullRequestJob();
        job.setPracticeRolloutRevision(job.getWorkspace().getReviewSettings().getRolloutRevision() - 1);
        PullRequest pullRequest = openPullRequest();
        stubPullRequestEvaluation(pullRequest, coverage(true));
        when(accountPreferencesQuery.practiceFeedbackDeliveryEnabled(AUTHOR_ID)).thenReturn(true);
        UUID feedbackId = UUID.randomUUID();
        Practice practice = new Practice();
        practice.setSlug("review-quality");
        practice.setAutonomy(PracticeAutonomy.HUMAN_APPROVAL);
        when(practiceRepository.findByWorkspaceIdAndSlugIn(WORKSPACE_ID, java.util.Set.of("review-quality")))
                .thenReturn(java.util.List.of(practice));
        when(approvalRepository.findByFeedbackIdAndWorkspaceId(feedbackId, WORKSPACE_ID))
                .thenReturn(Optional.of(FeedbackApproval.builder()
                        .feedbackId(feedbackId)
                        .workspaceId(WORKSPACE_ID)
                        .decision(FeedbackApprovalDecision.APPROVED)
                        .build()));

        var decision = policy().evaluatePullRequest(
                        job, DeliveryPolicyStage.EGRESS, feedbackId, java.util.Set.of("review-quality"));

        assertThat(decision.allowed()).isTrue();
        assertThat(recordedEvaluation().result().checks()).anySatisfy(check -> {
            assertThat(check.check().name()).isEqualTo("ROLLOUT_REVISION");
            assertThat(check.status().name()).isEqualTo("NOT_APPLICABLE");
        });
    }

    @Test
    void repositorylessFeedbackIdentityDoesNotGrantHumanApproval() {
        AgentJob job = conversationJob();
        UUID feedbackId = UUID.randomUUID();
        Practice practice = new Practice();
        practice.setSlug("review-quality");
        practice.setAutonomy(PracticeAutonomy.HUMAN_APPROVAL);
        when(practiceRepository.findByWorkspaceIdAndSlugIn(WORKSPACE_ID, java.util.Set.of("review-quality")))
                .thenReturn(java.util.List.of(practice));

        var decision = policy().evaluateForRecipient(
                        job,
                        DeliveryPolicyStage.EGRESS,
                        feedbackId,
                        DeliveryPolicySurface.CONVERSATION,
                        AUTHOR_ID,
                        java.util.Set.of("review-quality"));

        assertThat(decision.allowed()).isFalse();
        assertThat(recordedRefusal()).isEqualTo(FeedbackSuppressionReason.PRACTICE_REQUIRES_APPROVAL);
    }

    @Test
    void staleAutomaticRepositorylessFeedbackIsNotReleasedAsAnApproval() {
        AgentJob job = conversationJob();
        job.setPracticeRolloutRevision(job.getPracticeRolloutRevision() - 1);
        UUID feedbackId = UUID.randomUUID();
        Practice practice = new Practice();
        practice.setSlug("review-quality");
        practice.setAutonomy(PracticeAutonomy.AUTOMATIC);
        when(practiceRepository.findByWorkspaceIdAndSlugIn(WORKSPACE_ID, java.util.Set.of("review-quality")))
                .thenReturn(java.util.List.of(practice));

        var decision = policy().evaluateForRecipient(
                        job,
                        DeliveryPolicyStage.EGRESS,
                        feedbackId,
                        DeliveryPolicySurface.CONVERSATION,
                        AUTHOR_ID,
                        java.util.Set.of("review-quality"));

        assertThat(decision.allowed()).isFalse();
        assertThat(recordedRefusal()).isEqualTo(FeedbackSuppressionReason.STALE_ROLLOUT_REVISION);
    }

    @Test
    void pullRequestMapsRecipientPreferenceIntoTheDecisiveConsentRefusal() {
        AgentJob job = pullRequestJob();
        PullRequest pullRequest = openPullRequest();
        stubPullRequestEvaluation(pullRequest, coverage(true));
        when(accountPreferencesQuery.practiceFeedbackDeliveryEnabled(AUTHOR_ID)).thenReturn(false);

        PracticeFeedbackDeliveryPolicy.Decision<PullRequest> decision = policy().evaluatePullRequest(job);

        assertThat(decision.allowed()).isFalse();
        DeliveryPolicyEvaluationCommand recorded = recordedEvaluation();
        assertThat(recorded.result().refusal()).isEqualTo(FeedbackSuppressionReason.RECIPIENT_OPTED_OUT);
        assertThat(recorded.facts().recipientConsent()).isFalse();
        assertThat(recorded.facts().subject()).isEqualTo(ReviewSubjectStatus.RESOLVED_LINKED_HUMAN);
        assertThat(recorded.facts().repository()).isEqualTo("owner/repo");
        assertThat(recorded.facts().baseBranch()).isEqualTo("main");
    }

    @Test
    void pullRequestMapsCurrentCoverageIntoTheDecisiveCoverageRefusal() {
        AgentJob job = pullRequestJob();
        PullRequest pullRequest = openPullRequest();
        stubPullRequestEvaluation(pullRequest, coverage(false));
        when(accountPreferencesQuery.practiceFeedbackDeliveryEnabled(AUTHOR_ID)).thenReturn(true);

        PracticeFeedbackDeliveryPolicy.Decision<PullRequest> decision = policy().evaluatePullRequest(job);

        assertThat(decision.allowed()).isFalse();
        DeliveryPolicyEvaluationCommand recorded = recordedEvaluation();
        assertThat(recorded.result().refusal()).isEqualTo(FeedbackSuppressionReason.OUTSIDE_CURRENT_COVERAGE);
        assertThat(recorded.facts().repositoryMatched()).isTrue();
        assertThat(recorded.facts().branchMatched()).isTrue();
        assertThat(recorded.facts().personMatched()).isFalse();
        assertThat(recorded.facts().recipientConsent()).isTrue();
    }

    @Test
    void reviewerFeedbackUsesTheReviewerForCoverageAndConsent() {
        AgentJob job = pullRequestJob();
        var metadata = org.junit.jupiter.api.Assertions.assertInstanceOf(
                tools.jackson.databind.node.ObjectNode.class, job.getMetadata());
        metadata.put("subject_role", "REVIEWER");
        metadata.put("review_id", REVIEW_ID);
        metadata.put("about_user_id", REVIEWER_ID);
        PullRequest pullRequest = openPullRequest();
        when(reviewTargets.reviewMatchesTarget(REVIEW_ID, pullRequest.getId(), REVIEWER_ID))
                .thenReturn(true);
        stubPullRequestEvaluation(pullRequest, coverage(true));
        when(accountPreferencesQuery.practiceFeedbackDeliveryEnabled(REVIEWER_ID))
                .thenReturn(false);

        PracticeFeedbackDeliveryPolicy.Decision<PullRequest> decision = policy().evaluatePullRequest(job);

        assertThat(decision.allowed()).isFalse();
        assertThat(recordedRefusal()).isEqualTo(FeedbackSuppressionReason.RECIPIENT_OPTED_OUT);
        org.mockito.Mockito.verify(coverageService)
                .assess(any(), eq("owner/repo"), eq("main"), eq(new ReviewSubject(REVIEWER_ID, true)), eq(true));

        when(accountPreferencesQuery.practiceFeedbackDeliveryEnabled(REVIEWER_ID))
                .thenReturn(true);
        Practice practice = new Practice();
        practice.setSlug("review-quality");
        practice.setAutonomy(PracticeAutonomy.AUTOMATIC);
        when(practiceRepository.findByWorkspaceIdAndSlugIn(WORKSPACE_ID, java.util.Set.of("review-quality")))
                .thenReturn(java.util.List.of(practice));

        assertThat(policy().evaluateForRecipient(
                                job,
                                DeliveryPolicyStage.EGRESS,
                                UUID.randomUUID(),
                                DeliveryPolicySurface.CONVERSATION,
                                REVIEWER_ID,
                                java.util.Set.of("review-quality"))
                        .allowed())
                .isTrue();
        assertThat(policy().evaluateForRecipient(
                                job,
                                DeliveryPolicyStage.EGRESS,
                                UUID.randomUUID(),
                                DeliveryPolicySurface.CONVERSATION,
                                AUTHOR_ID,
                                java.util.Set.of("review-quality"))
                        .refusal())
                .isEqualTo(FeedbackSuppressionReason.ARTIFACT_GONE);
    }

    private FeedbackSuppressionReason recordedRefusal() {
        return recordedEvaluation().result().refusal();
    }

    private DeliveryPolicyEvaluationCommand recordedEvaluation() {
        ArgumentCaptor<DeliveryPolicyEvaluationCommand> recorded =
                ArgumentCaptor.forClass(DeliveryPolicyEvaluationCommand.class);
        org.mockito.Mockito.verify(evaluationRecorder).record(recorded.capture());
        return recorded.getValue();
    }

    private AgentJob conversationJob() {
        Workspace workspace = WorkspaceTestFixtures.activeWorkspace("compose");
        workspace.setId(WORKSPACE_ID);
        workspace.getFeatures().setPracticesEnabled(true);
        lenient().when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace));

        AgentJob job = new AgentJob();
        job.setId(UUID.randomUUID());
        job.setWorkspace(workspace);
        job.setArtifactKind(ArtifactKind.of("chat.conversation_thread"));
        job.setPracticeRolloutRevision(workspace.getReviewSettings().getRolloutRevision());
        var metadata = tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode();
        metadata.put("about_user_id", AUTHOR_ID);
        metadata.put("slack_thread_id", 50L);
        metadata.put("slack_channel_id", "C123");
        metadata.put("slack_thread_ts", "123.456");
        lenient()
                .when(conversationSourceLiveness.isDeliverableThread(WORKSPACE_ID, 50L, "C123", "123.456", AUTHOR_ID))
                .thenReturn(true);
        job.setMetadata(metadata);
        lenient().when(coverageService.assessRepositoryless(any(), any())).thenReturn(coverage(true));
        lenient()
                .when(accountPreferencesQuery.practiceFeedbackDeliveryEnabled(AUTHOR_ID))
                .thenReturn(true);
        return job;
    }

    private AgentJob pullRequestJob() {
        AgentJob job = conversationJob();
        job.setArtifactKind(ArtifactKind.of("scm.pull_request"));
        var metadata = tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode();
        metadata.put("pull_request_id", PULL_REQUEST_ID);
        metadata.put("repository_id", REPOSITORY_ID);
        metadata.put("repository_full_name", "owner/repo");
        metadata.put("pr_number", 17);
        job.setMetadata(metadata);
        return job;
    }

    private PullRequest openPullRequest() {
        PullRequest pullRequest = new PullRequest();
        pullRequest.setId(PULL_REQUEST_ID);
        pullRequest.setNumber(17);
        pullRequest.setState(Issue.State.OPEN);
        pullRequest.setBaseRefName("main");
        pullRequest.setBaseRefOid("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        Repository repository = new Repository();
        repository.setId(REPOSITORY_ID);
        repository.setNameWithOwner("owner/repo");
        pullRequest.setRepository(repository);
        User author = new User();
        author.setId(AUTHOR_ID);
        author.setType(User.Type.USER);
        pullRequest.setAuthor(author);
        return pullRequest;
    }

    private void stubPullRequestEvaluation(
            PullRequest pullRequest, PracticeReviewCoverageService.CoverageAssessment coverage) {
        when(pullRequestRepository.findByIdWithAuthorAndRepository(PULL_REQUEST_ID))
                .thenReturn(Optional.of(pullRequest));
        when(repositoryToMonitorRepository.existsByWorkspaceIdAndNameWithOwner(WORKSPACE_ID, "owner/repo"))
                .thenReturn(true);
        when(coverageService.assess(any(), eq("owner/repo"), eq("main"), any(), eq(true)))
                .thenReturn(coverage);
    }

    private static PracticeReviewCoverageService.CoverageAssessment coverage(boolean admitted) {
        return new PracticeReviewCoverageService.CoverageAssessment(
                ReviewRepositoryMode.ALL_MONITORED,
                ReviewPersonMode.SELECTED,
                ReviewSubjectStatus.RESOLVED_LINKED_HUMAN,
                true,
                true,
                admitted,
                admitted);
    }

    private PracticeFeedbackDeliveryPolicy policy() {
        return new PracticeFeedbackDeliveryPolicy(
                issueRepository,
                pullRequestRepository,
                reviewTargets,
                repositoryToMonitorRepository,
                workspaceRepository,
                accountPreferencesQuery,
                mock(PracticeReviewProperties.class),
                silentModeQuery,
                coverageService,
                evaluationRecorder,
                practiceRepository,
                approvalRepository,
                conversationSourceLiveness,
                documentProjection);
    }

    @ParameterizedTest
    @CsvSource({
        "scm.pull_request, false, 43, true",
        "scm.pull_request, true, 43, false",
        "scm.pull_request, false, 44, false",
        "scm.issue, false, 43, true",
        "scm.issue, true, 43, false",
        "scm.issue, false, 44, false"
    })
    void shouldRequireLiveWorkAndItsRecipientForPreparedScmFeedback(
            String artifactKind, boolean deleted, long recipientId, boolean allowed) {
        var job = pullRequestJob();
        job.setArtifactKind(ArtifactKind.of(artifactKind));
        var artifact = openPullRequest();
        if (deleted) artifact.setDeletedAt(java.time.Instant.now());
        if ("scm.issue".equals(artifactKind)) {
            var metadata =
                    tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode();
            metadata.put("issue_id", PULL_REQUEST_ID);
            metadata.put("issue_number", 17);
            metadata.put("repository_id", REPOSITORY_ID);
            metadata.put("repository_full_name", "owner/repo");
            job.setMetadata(metadata);
            Issue issue = new Issue();
            issue.setId(artifact.getId());
            issue.setNumber(artifact.getNumber());
            issue.setAuthor(artifact.getAuthor());
            issue.setRepository(artifact.getRepository());
            issue.setState(artifact.getState());
            issue.setDeletedAt(artifact.getDeletedAt());
            when(issueRepository.findByIdWithAuthorAndRepository(PULL_REQUEST_ID))
                    .thenReturn(Optional.of(issue));
            when(coverageService.assess(any(), eq("owner/repo"), eq(null), any(), eq(false)))
                    .thenReturn(coverage(true));
        } else {
            stubPullRequestEvaluation(artifact, coverage(true));
        }
        lenient()
                .when(repositoryToMonitorRepository.existsByWorkspaceIdAndNameWithOwner(WORKSPACE_ID, "owner/repo"))
                .thenReturn(true);
        Practice practice = new Practice();
        practice.setSlug("review-quality");
        practice.setAutonomy(PracticeAutonomy.AUTOMATIC);
        when(practiceRepository.findByWorkspaceIdAndSlugIn(WORKSPACE_ID, java.util.Set.of("review-quality")))
                .thenReturn(java.util.List.of(practice));
        var decision = policy().evaluateForRecipient(
                        job,
                        DeliveryPolicyStage.EGRESS,
                        UUID.randomUUID(),
                        DeliveryPolicySurface.CONVERSATION,
                        recipientId,
                        java.util.Set.of("review-quality"));
        assertThat(decision.allowed()).isEqualTo(allowed);
        if (!allowed) assertThat(decision.refusal()).isEqualTo(FeedbackSuppressionReason.ARTIFACT_GONE);
    }

    @ParameterizedTest
    @EnumSource(
            value = DeliveryPolicySurface.class,
            names = {"IN_APP", "CONVERSATION"})
    void shouldRefuseNewConversationFeedbackWhenThreadVanishes(DeliveryPolicySurface surface) {
        var job = conversationJob();
        when(conversationSourceLiveness.isDeliverableThread(WORKSPACE_ID, 50L, "C123", "123.456", AUTHOR_ID))
                .thenReturn(false);
        var decision = policy().evaluateForRecipient(
                        job, DeliveryPolicyStage.COMPOSITION, null, surface, AUTHOR_ID, java.util.Set.of());
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.refusal()).isEqualTo(FeedbackSuppressionReason.ARTIFACT_GONE);
    }

    @ParameterizedTest
    @EnumSource(
            value = DeliveryPolicySurface.class,
            names = {"IN_APP", "CONVERSATION"})
    void shouldRefuseDeletedDocumentAndAllowResurrection(DeliveryPolicySurface surface) {
        var job = conversationJob();
        job.setArtifactKind(ArtifactKind.of("docs.document"));
        var metadata = tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode();
        metadata.put("about_user_id", AUTHOR_ID);
        metadata.put("docs_document_id", 51L);
        job.setMetadata(metadata);
        when(documentProjection.documentById(WORKSPACE_ID, 51L))
                .thenReturn(Optional.of(DocumentProjection.ProjectedDocument.withoutAuthors(
                        "collection", "document", "Title", null, true)));
        var decision = policy().evaluateForRecipient(
                        job, DeliveryPolicyStage.COMPOSITION, null, surface, AUTHOR_ID, java.util.Set.of());
        assertThat(decision.refusal()).isEqualTo(FeedbackSuppressionReason.ARTIFACT_GONE);
        when(documentProjection.documentById(WORKSPACE_ID, 51L))
                .thenReturn(Optional.of(DocumentProjection.ProjectedDocument.withoutAuthors(
                        "collection", "document", "Title", "Body", false)));
        assertThat(policy().evaluateForRecipient(
                                job, DeliveryPolicyStage.COMPOSITION, null, surface, AUTHOR_ID, java.util.Set.of())
                        .allowed())
                .isTrue();
    }
}
