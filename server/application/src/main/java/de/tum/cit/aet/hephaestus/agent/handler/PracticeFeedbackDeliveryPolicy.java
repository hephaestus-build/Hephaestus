package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.context.providers.DocumentContentSource;
import de.tum.cit.aet.hephaestus.agent.conversation.ConversationSourceLiveness;
import de.tum.cit.aet.hephaestus.agent.documentation.DocumentProjection;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountPreferencesQuery;
import de.tum.cit.aet.hephaestus.core.settings.spi.SilentModeQuery;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewSubject;
import de.tum.cit.aet.hephaestus.integration.scm.ReviewTargetQuery;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyEvaluationCommand;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyEvaluationRecorder;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyFactsSnapshot;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyResolver;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyResolver.FactAnswer;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyStage;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicySurface;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.feedback.approval.FeedbackApprovalDecision;
import de.tum.cit.aet.hephaestus.practices.feedback.approval.FeedbackApprovalRepository;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewCoverageService;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewCoverageService.CoverageAssessment;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaults;
import de.tum.cit.aet.hephaestus.practices.review.autonomy.AutonomyResolver;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.settings.PracticeDeliveryStatus;
import de.tum.cit.aet.hephaestus.workspace.settings.PracticeReviewSettings;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class PracticeFeedbackDeliveryPolicy {

    private final ConversationSourceLiveness conversationSourceLiveness;
    private final DocumentProjection documentProjection;
    private final IssueRepository issueRepository;
    private final PullRequestRepository pullRequestRepository;
    private final ReviewTargetQuery reviewTargets;
    private final RepositoryToMonitorRepository repositoryToMonitorRepository;
    private final WorkspaceRepository workspaceRepository;
    private final AccountPreferencesQuery accountPreferencesQuery;
    private final PracticeReviewProperties reviewProperties;
    private final SilentModeQuery silentModeQuery;
    private final PracticeReviewCoverageService coverageService;
    private final DeliveryPolicyEvaluationRecorder evaluationRecorder;
    private final PracticeRepository practiceRepository;
    private final FeedbackApprovalRepository approvalRepository;

    PracticeFeedbackDeliveryPolicy(
            IssueRepository issueRepository,
            PullRequestRepository pullRequestRepository,
            ReviewTargetQuery reviewTargets,
            RepositoryToMonitorRepository repositoryToMonitorRepository,
            WorkspaceRepository workspaceRepository,
            AccountPreferencesQuery accountPreferencesQuery,
            PracticeReviewProperties reviewProperties,
            SilentModeQuery silentModeQuery,
            PracticeReviewCoverageService coverageService,
            DeliveryPolicyEvaluationRecorder evaluationRecorder,
            PracticeRepository practiceRepository,
            FeedbackApprovalRepository approvalRepository,
            ConversationSourceLiveness conversationSourceLiveness,
            DocumentProjection documentProjection) {
        this.conversationSourceLiveness = conversationSourceLiveness;
        this.documentProjection = documentProjection;
        this.issueRepository = issueRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.reviewTargets = reviewTargets;
        this.repositoryToMonitorRepository = repositoryToMonitorRepository;
        this.workspaceRepository = workspaceRepository;
        this.accountPreferencesQuery = accountPreferencesQuery;
        this.reviewProperties = reviewProperties;
        this.silentModeQuery = silentModeQuery;
        this.coverageService = coverageService;
        this.evaluationRecorder = evaluationRecorder;
        this.practiceRepository = practiceRepository;
        this.approvalRepository = approvalRepository;
    }

    @Transactional(readOnly = true)
    public Decision<Issue> evaluateIssue(AgentJob job) {
        return evaluateIssue(job, DeliveryPolicyStage.AUTOMATIC, null, DeliveryPolicySurface.ARTIFACT, Set.of(), null);
    }

    @Transactional(readOnly = true)
    public Decision<Issue> evaluateIssue(AgentJob job, DeliveryPolicyStage stage, @Nullable UUID feedbackId) {
        return evaluateIssue(job, stage, feedbackId, DeliveryPolicySurface.ARTIFACT, Set.of(), null);
    }

    @Transactional(readOnly = true)
    public Decision<Issue> evaluateIssue(
            AgentJob job,
            DeliveryPolicyStage stage,
            @Nullable UUID feedbackId,
            Collection<String> contributingPracticeSlugs) {
        return evaluateIssue(job, stage, feedbackId, DeliveryPolicySurface.ARTIFACT, contributingPracticeSlugs, null);
    }

    private Decision<Issue> evaluateIssue(
            AgentJob job,
            DeliveryPolicyStage stage,
            @Nullable UUID feedbackId,
            DeliveryPolicySurface surface,
            Collection<String> contributingPracticeSlugs,
            @Nullable Long recipientUserId) {
        long workspaceId = requireWorkspaceId(job);
        Workspace workspace = activePracticeWorkspace(workspaceId);
        boolean instanceMayDeliver = !silentModeQuery.isSilentModeEngaged();
        if (!instanceMayDeliver) {
            Resolution resolution = resolve(
                    job,
                    surface,
                    instanceMayDeliver,
                    workspace,
                    null,
                    FactAnswer.NOT_APPLICABLE,
                    FactAnswer.NOT_APPLICABLE,
                    null,
                    stage,
                    feedbackId,
                    contributingPracticeSlugs,
                    null,
                    null,
                    "scm.issue");
            record(job, workspaceId, feedbackId, surface, stage, resolution);
            return Decision.suppressed(resolution.result().refusal());
        }
        if (workspace == null) {
            Resolution resolution = resolve(
                    job,
                    surface,
                    instanceMayDeliver,
                    null,
                    null,
                    FactAnswer.NOT_APPLICABLE,
                    FactAnswer.NOT_APPLICABLE,
                    null,
                    stage,
                    feedbackId,
                    contributingPracticeSlugs,
                    null,
                    null,
                    "scm.issue");
            record(job, workspaceId, feedbackId, surface, stage, resolution);
            return Decision.suppressed(resolution.result().refusal());
        }
        JsonNode metadata = job.getMetadata();
        Issue issue = integralId(metadata, "issue_id")
                .flatMap(issueRepository::findByIdWithAuthorAndRepository)
                .orElse(null);
        Issue target = workspace != null
                        && isEligibleTarget(issue, metadata, "issue_number", workspaceId)
                        && issue != null
                        && issue.getAuthor() != null
                        && (recipientUserId == null
                                || recipientUserId.equals(issue.getAuthor().getId()))
                ? issue
                : null;
        boolean closedWhenQueued = metadata != null
                && "closed".equalsIgnoreCase(metadata.path("state").asString(""));
        FeedbackSuppressionReason artifactRefusal = target == null
                ? FeedbackSuppressionReason.ARTIFACT_GONE
                : closedWhenQueued || target.getState() == Issue.State.CLOSED
                        ? FeedbackSuppressionReason.ARTIFACT_CLOSED
                        : null;
        String repositoryName = issue == null || issue.getRepository() == null
                ? null
                : issue.getRepository().getNameWithOwner();
        CoverageAssessment coverage = workspace == null
                ? null
                : coverageService.assess(
                        workspace, repositoryName, null, issue == null ? null : issue.reviewSubject(), false);
        Resolution resolution = resolve(
                job,
                surface,
                instanceMayDeliver,
                workspace,
                coverage,
                target == null ? FactAnswer.NOT_APPLICABLE : FactAnswer.of(recipientAllowsDelivery(target)),
                FactAnswer.of(artifactRefusal == null),
                artifactRefusal,
                stage,
                feedbackId,
                contributingPracticeSlugs,
                repositoryName,
                null,
                "scm.issue");
        record(job, workspaceId, feedbackId, surface, stage, resolution);
        return resolution.result().allowed() && target != null
                ? Decision.allowed(target)
                : Decision.suppressed(resolution.result().refusal());
    }

    @Transactional(readOnly = true)
    public Decision<PullRequest> evaluatePullRequest(AgentJob job) {
        return evaluatePullRequest(
                job, DeliveryPolicyStage.AUTOMATIC, null, DeliveryPolicySurface.ARTIFACT, Set.of(), null);
    }

    @Transactional(readOnly = true)
    public Decision<PullRequest> evaluatePullRequest(
            AgentJob job, DeliveryPolicyStage stage, @Nullable UUID feedbackId) {
        return evaluatePullRequest(job, stage, feedbackId, DeliveryPolicySurface.ARTIFACT, Set.of(), null);
    }

    @Transactional(readOnly = true)
    public Decision<PullRequest> evaluatePullRequest(
            AgentJob job,
            DeliveryPolicyStage stage,
            @Nullable UUID feedbackId,
            Collection<String> contributingPracticeSlugs) {
        return evaluatePullRequest(
                job, stage, feedbackId, DeliveryPolicySurface.ARTIFACT, contributingPracticeSlugs, null);
    }

    private Decision<PullRequest> evaluatePullRequest(
            AgentJob job,
            DeliveryPolicyStage stage,
            @Nullable UUID feedbackId,
            DeliveryPolicySurface surface,
            Collection<String> contributingPracticeSlugs,
            @Nullable Long recipientUserId) {
        long workspaceId = requireWorkspaceId(job);
        Workspace workspace = activePracticeWorkspace(workspaceId);
        boolean instanceMayDeliver = !silentModeQuery.isSilentModeEngaged();
        if (!instanceMayDeliver) {
            Resolution resolution = resolve(
                    job,
                    surface,
                    instanceMayDeliver,
                    workspace,
                    null,
                    FactAnswer.NOT_APPLICABLE,
                    FactAnswer.NOT_APPLICABLE,
                    null,
                    stage,
                    feedbackId,
                    contributingPracticeSlugs,
                    null,
                    null,
                    "scm.pull_request");
            record(job, workspaceId, feedbackId, surface, stage, resolution);
            return Decision.suppressed(resolution.result().refusal());
        }
        if (workspace == null) {
            Resolution resolution = resolve(
                    job,
                    surface,
                    instanceMayDeliver,
                    null,
                    null,
                    FactAnswer.NOT_APPLICABLE,
                    FactAnswer.NOT_APPLICABLE,
                    null,
                    stage,
                    feedbackId,
                    contributingPracticeSlugs,
                    null,
                    null,
                    "scm.pull_request");
            record(job, workspaceId, feedbackId, surface, stage, resolution);
            return Decision.suppressed(resolution.result().refusal());
        }
        JsonNode metadata = job.getMetadata();
        PullRequest pullRequest = integralId(metadata, "pull_request_id")
                .flatMap(pullRequestRepository::findByIdWithAuthorAndRepository)
                .orElse(null);
        ReviewSubject subject = pullRequest == null ? null : pullRequestSubject(pullRequest, metadata);
        Long subjectUserId = subject == null ? null : subject.actorId();
        PullRequest target = workspace != null
                        && isEligibleTarget(pullRequest, metadata, "pr_number", workspaceId)
                        && pullRequest != null
                        && subjectUserId != null
                        && (recipientUserId == null || recipientUserId.equals(subjectUserId))
                ? pullRequest
                : null;
        PracticeReviewSettings settings = workspace == null ? null : workspace.getReviewSettings();
        FeedbackSuppressionReason artifactRefusal = target == null || settings == null
                ? FeedbackSuppressionReason.ARTIFACT_GONE
                : target.getState() == Issue.State.CLOSED
                        ? FeedbackSuppressionReason.ARTIFACT_CLOSED
                        : target.getState() == Issue.State.MERGED
                                        && !settings.resolveDeliverToMerged(reviewProperties.deliverToMerged())
                                ? FeedbackSuppressionReason.ARTIFACT_MERGED
                                : null;
        String repositoryName = pullRequest == null || pullRequest.getRepository() == null
                ? null
                : pullRequest.getRepository().getNameWithOwner();
        CoverageAssessment coverage = workspace == null
                ? null
                : coverageService.assess(
                        workspace,
                        repositoryName,
                        pullRequest == null ? null : pullRequest.getBaseRefName(),
                        subject,
                        true);
        Resolution resolution = resolve(
                job,
                surface,
                instanceMayDeliver,
                workspace,
                coverage,
                target == null || subjectUserId == null
                        ? FactAnswer.NOT_APPLICABLE
                        : FactAnswer.of(accountPreferencesQuery.practiceFeedbackDeliveryEnabled(subjectUserId)),
                FactAnswer.of(artifactRefusal == null),
                artifactRefusal,
                stage,
                feedbackId,
                contributingPracticeSlugs,
                repositoryName,
                pullRequest == null ? null : pullRequest.getBaseRefName(),
                "scm.pull_request");
        record(job, workspaceId, feedbackId, surface, stage, resolution);
        return resolution.result().allowed() && target != null
                ? Decision.allowed(target)
                : Decision.suppressed(resolution.result().refusal());
    }

    @Transactional(readOnly = true)
    public boolean allowsComposition(AgentJob job, DeliveryPolicySurface surface) {
        if (surface == DeliveryPolicySurface.ARTIFACT) {
            throw new IllegalArgumentException("Artifact composition must use its typed policy entry point");
        }
        JsonNode metadata = job.getMetadata();
        if (metadata != null && metadata.path("issue_id").isIntegralNumber()) {
            return evaluateIssue(job, DeliveryPolicyStage.COMPOSITION, null, surface, Set.of(), null)
                    .allowed();
        }
        if (metadata != null && metadata.path("pull_request_id").isIntegralNumber()) {
            return evaluatePullRequest(job, DeliveryPolicyStage.COMPOSITION, null, surface, Set.of(), null)
                    .allowed();
        }
        Long aboutUserId = integralId(metadata, "about_user_id").orElse(null);
        if (aboutUserId != null) {
            return evaluateRepositorylessWithinTransaction(
                            job, DeliveryPolicyStage.COMPOSITION, null, surface, aboutUserId, Set.of())
                    .allowed();
        }
        long workspaceId = requireWorkspaceId(job);
        Workspace workspace = activePracticeWorkspace(workspaceId);
        Resolution resolution = resolve(
                job,
                surface,
                !silentModeQuery.isSilentModeEngaged(),
                workspace,
                null,
                FactAnswer.NOT_APPLICABLE,
                FactAnswer.NOT_APPLICABLE,
                null,
                DeliveryPolicyStage.COMPOSITION,
                null,
                Set.of(),
                null,
                null,
                job.getArtifactKind() == null ? null : job.getArtifactKind().value());
        record(job, workspaceId, null, surface, DeliveryPolicyStage.COMPOSITION, resolution);
        return resolution.result().allowed();
    }

    /** Decides one recipient's non-artifact delivery, whatever kind of work the feedback is about. */
    @Transactional(readOnly = true)
    public DeliveryDecision evaluateForRecipient(
            AgentJob job,
            DeliveryPolicyStage stage,
            @Nullable UUID feedbackId,
            DeliveryPolicySurface surface,
            long aboutUserId,
            Collection<String> contributingPracticeSlugs) {
        if (surface == DeliveryPolicySurface.ARTIFACT) {
            throw new IllegalArgumentException(
                    "ARTIFACT delivery is decided by evaluateIssue/evaluatePullRequest, not by recipient");
        }
        if (ArtifactKinds.ISSUE.equals(job.getArtifactKind())) {
            return evaluateIssue(job, stage, feedbackId, surface, contributingPracticeSlugs, aboutUserId)
                    .verdict();
        }
        if (ArtifactKinds.PULL_REQUEST.equals(job.getArtifactKind())) {
            return evaluatePullRequest(job, stage, feedbackId, surface, contributingPracticeSlugs, aboutUserId)
                    .verdict();
        }
        return evaluateRepositorylessWithinTransaction(
                        job, stage, feedbackId, surface, aboutUserId, contributingPracticeSlugs)
                .verdict();
    }

    private Decision<Long> evaluateRepositorylessWithinTransaction(
            AgentJob job,
            DeliveryPolicyStage stage,
            @Nullable UUID feedbackId,
            DeliveryPolicySurface surface,
            long aboutUserId,
            Collection<String> contributingPracticeSlugs) {
        long workspaceId = requireWorkspaceId(job);
        Workspace workspace = activePracticeWorkspace(workspaceId);
        JsonNode metadata = job.getMetadata();
        boolean subjectMatches = metadata != null
                && metadata.path("about_user_id").isIntegralNumber()
                && metadata.path("about_user_id").asLong() == aboutUserId;
        CoverageAssessment coverage = workspace == null
                ? null
                : coverageService.assessRepositoryless(workspace, new ReviewSubject(aboutUserId, true));
        boolean targetEligible = subjectMatches && repositorylessTargetEligible(job, workspaceId, aboutUserId);
        Resolution resolution = resolve(
                job,
                surface,
                !silentModeQuery.isSilentModeEngaged(),
                workspace,
                coverage,
                subjectMatches
                        ? FactAnswer.of(accountPreferencesQuery.practiceFeedbackDeliveryEnabled(aboutUserId))
                        : FactAnswer.NOT_APPLICABLE,
                FactAnswer.of(targetEligible),
                targetEligible ? null : FeedbackSuppressionReason.ARTIFACT_GONE,
                stage,
                feedbackId,
                contributingPracticeSlugs,
                null,
                null,
                job.getArtifactKind() == null ? null : job.getArtifactKind().value());
        record(job, workspaceId, feedbackId, surface, stage, resolution);
        return resolution.result().allowed()
                ? Decision.allowed(aboutUserId)
                : Decision.suppressed(resolution.result().refusal());
    }

    private boolean repositorylessTargetEligible(AgentJob job, long workspaceId, long aboutUserId) {
        JsonNode metadata = job.getMetadata();
        if (metadata == null) return false;
        if (ArtifactKinds.DOCUMENT.equals(job.getArtifactKind())) {
            return integralId(metadata, DocumentContentSource.DOCUMENT_ID_METADATA_KEY)
                    .flatMap(id -> documentProjection.documentById(workspaceId, id))
                    .filter(document -> !document.deleted())
                    .isPresent();
        }
        if (ArtifactKinds.CONVERSATION_THREAD.equals(job.getArtifactKind())) {
            Long threadId = integralId(metadata, "slack_thread_id").orElse(null);
            String channelId = metadata.path("slack_channel_id").asString("");
            String threadTs = metadata.path("slack_thread_ts").asString("");
            return threadId != null
                    && !channelId.isBlank()
                    && !threadTs.isBlank()
                    && conversationSourceLiveness.isDeliverableThread(
                            workspaceId, threadId, channelId, threadTs, aboutUserId);
        }
        return false;
    }

    static boolean matchesArtifact(Issue artifact, @Nullable JsonNode metadata, String numberKey) {
        var repository = artifact.getRepository();
        return artifact.getDeletedAt() == null
                && repository != null
                && repository.getId() != null
                && matchesArtifactIdentity(
                        repository.getId(), repository.getNameWithOwner(), artifact.getNumber(), metadata, numberKey);
    }

    static boolean matchesArtifact(ReviewTargetQuery.Target artifact, @Nullable JsonNode metadata, String numberKey) {
        return !artifact.deleted()
                && matchesArtifactIdentity(
                        artifact.repositoryId(), artifact.repositoryFullName(), artifact.number(), metadata, numberKey);
    }

    private static boolean matchesArtifactIdentity(
            long repositoryId, String repositoryFullName, int number, @Nullable JsonNode metadata, String numberKey) {
        return metadata != null
                && metadata.path("repository_id").isIntegralNumber()
                && metadata.path("repository_id").asLong() == repositoryId
                && metadata.path("repository_full_name").isString()
                && metadata.path("repository_full_name").asString().equals(repositoryFullName)
                && metadata.path(numberKey).isIntegralNumber()
                && metadata.path(numberKey).asInt() == number;
    }

    private boolean isEligibleTarget(
            @Nullable Issue artifact, @Nullable JsonNode metadata, String numberKey, long workspaceId) {
        return (artifact != null
                && artifact.getRepository() != null
                && matchesArtifact(artifact, metadata, numberKey)
                && repositoryToMonitorRepository.existsByWorkspaceIdAndNameWithOwner(
                        workspaceId, artifact.getRepository().getNameWithOwner()));
    }

    private boolean recipientAllowsDelivery(Issue artifact) {
        return accountPreferencesQuery.practiceFeedbackDeliveryEnabled(
                java.util.Objects.requireNonNull(artifact.getAuthor(), "a target artifact always has an author")
                        .getId());
    }

    private @Nullable ReviewSubject pullRequestSubject(PullRequest pullRequest, @Nullable JsonNode metadata) {
        if (metadata == null || !"REVIEWER".equals(metadata.path("subject_role").asString())) {
            return pullRequest.reviewSubject();
        }
        long reviewId = metadata.path("review_id").asLong(-1);
        long aboutUserId = metadata.path("about_user_id").asLong(-1);
        boolean matches = reviewId >= 0
                && aboutUserId >= 0
                && reviewTargets.reviewMatchesTarget(reviewId, pullRequest.getId(), aboutUserId);
        return matches ? new ReviewSubject(aboutUserId, true) : null;
    }

    private @Nullable Workspace activePracticeWorkspace(long workspaceId) {
        return workspaceRepository
                .findById(workspaceId)
                .filter(workspace -> workspace.getStatus() == Workspace.WorkspaceStatus.ACTIVE)
                .filter(workspace -> Boolean.TRUE.equals(workspace.getFeatures().getPracticesEnabled()))
                .orElse(null);
    }

    private Resolution resolve(
            AgentJob job,
            DeliveryPolicySurface surface,
            boolean instanceMayDeliver,
            @Nullable Workspace workspace,
            @Nullable CoverageAssessment coverage,
            FactAnswer consent,
            FactAnswer artifactEligible,
            @Nullable FeedbackSuppressionReason artifactRefusal,
            DeliveryPolicyStage stage,
            @Nullable UUID feedbackId,
            Collection<String> contributingPracticeSlugs,
            @Nullable String repository,
            @Nullable String baseBranch,
            @Nullable String artifactKind) {
        Long admittedRevision = job.getPracticeRolloutRevision();
        Long evaluatedRevision =
                workspace == null ? null : workspace.getReviewSettings().getRolloutRevision();
        boolean approvedAttempt = isApprovedAttempt(workspace, stage, feedbackId);
        AutonomyAssessment autonomy = autonomy(workspace, feedbackId, contributingPracticeSlugs, approvedAttempt);
        DeliveryPolicyResolver.Result result = DeliveryPolicyResolver.resolve(new DeliveryPolicyResolver.Facts(
                isExternalSurface(surface) ? FactAnswer.of(instanceMayDeliver) : FactAnswer.NOT_APPLICABLE,
                workspace != null,
                workspace == null
                        ? FactAnswer.NOT_APPLICABLE
                        : approvedAttempt
                                ? FactAnswer.NOT_APPLICABLE
                                : FactAnswer.of(admittedRevision != null
                                        && evaluatedRevision != null
                                        && admittedRevision.longValue() == evaluatedRevision.longValue()),
                workspace == null || !isExternalSurface(surface)
                        ? FactAnswer.NOT_APPLICABLE
                        : FactAnswer.of(
                                workspace.getReviewSettings().getDeliveryStatus() == PracticeDeliveryStatus.ACTIVE),
                coverage == null ? FactAnswer.NOT_APPLICABLE : FactAnswer.of(coverage.admitted()),
                autonomy.authorized(),
                consent,
                artifactEligible,
                artifactRefusal));
        DeliveryPolicyFactsSnapshot snapshot = new DeliveryPolicyFactsSnapshot(
                artifactKind,
                repository,
                baseBranch,
                coverage == null ? null : coverage.subjectStatus(),
                coverage == null ? null : coverage.repositoryMode(),
                coverage == null ? null : coverage.personMode(),
                coverage == null ? null : coverage.repositoryMatched(),
                coverage == null ? null : coverage.branchMatched(),
                coverage == null ? null : coverage.personMatched(),
                recordedConsent(consent),
                workspace == null ? null : workspace.getReviewSettings().getDeliveryStatus(),
                job.getPracticeTriggerMode(),
                autonomy.facts());
        return new Resolution(result, evaluatedRevision, snapshot);
    }

    private AutonomyAssessment autonomy(
            @Nullable Workspace workspace,
            @Nullable UUID feedbackId,
            Collection<String> contributingPracticeSlugs,
            boolean approvedAttempt) {
        if (workspace == null) return new AutonomyAssessment(FactAnswer.NOT_APPLICABLE, List.of());
        if (feedbackId == null && contributingPracticeSlugs.isEmpty()) {
            return new AutonomyAssessment(FactAnswer.NOT_APPLICABLE, List.of());
        }
        List<Practice> practices;
        int expected;
        Set<String> slugs = Set.copyOf(contributingPracticeSlugs);
        if (!slugs.isEmpty()) {
            practices = practiceRepository.findByWorkspaceIdAndSlugIn(workspace.getId(), slugs);
            expected = slugs.size();
        } else if (feedbackId != null) {
            practices = practiceRepository.findContributingPractices(workspace.getId(), feedbackId);
            expected = practices.size();
        } else {
            practices = List.of();
            expected = 0;
        }
        PracticeAutonomy workspaceDefault =
                WorkspaceReviewDefaults.of(workspace).defaultAutonomy();
        List<DeliveryPolicyFactsSnapshot.PracticeFact> facts = practices.stream()
                .map(practice -> new DeliveryPolicyFactsSnapshot.PracticeFact(
                        practice.getSlug(), AutonomyResolver.effectiveAutonomyOf(practice, workspaceDefault)))
                .sorted(java.util.Comparator.comparing(DeliveryPolicyFactsSnapshot.PracticeFact::slug))
                .toList();
        boolean authorized = !facts.isEmpty() && facts.size() == expected;
        if (approvedAttempt) {
            authorized = authorized
                    && facts.stream().noneMatch(fact -> fact.autonomy() == PracticeAutonomy.OFF)
                    && facts.stream().anyMatch(fact -> fact.autonomy() == PracticeAutonomy.HUMAN_APPROVAL);
        } else {
            authorized = authorized && facts.stream().allMatch(fact -> fact.autonomy() == PracticeAutonomy.AUTOMATIC);
        }
        return new AutonomyAssessment(FactAnswer.of(authorized), facts);
    }

    private boolean isApprovedAttempt(
            @Nullable Workspace workspace, DeliveryPolicyStage stage, @Nullable UUID feedbackId) {
        if (workspace == null
                || feedbackId == null
                || (stage != DeliveryPolicyStage.APPROVED && stage != DeliveryPolicyStage.EGRESS)) {
            return false;
        }
        return approvalRepository
                .findByFeedbackIdAndWorkspaceId(feedbackId, workspace.getId())
                .filter(approval -> approval.getDecision() == FeedbackApprovalDecision.APPROVED)
                .isPresent();
    }

    private static @Nullable Boolean recordedConsent(FactAnswer consent) {
        return switch (consent) {
            case PASSES -> Boolean.TRUE;
            case DENIES -> Boolean.FALSE;
            case NOT_APPLICABLE -> null;
        };
    }

    private static boolean isExternalSurface(DeliveryPolicySurface surface) {
        return surface != DeliveryPolicySurface.IN_APP;
    }

    private void record(
            AgentJob job,
            long workspaceId,
            @Nullable UUID feedbackId,
            DeliveryPolicySurface surface,
            DeliveryPolicyStage stage,
            Resolution resolution) {
        evaluationRecorder.record(new DeliveryPolicyEvaluationCommand(
                workspaceId,
                job.getId(),
                feedbackId,
                job.getPracticeRolloutRevision() == null ? -1 : job.getPracticeRolloutRevision(),
                resolution.evaluatedRevision(),
                surface,
                stage,
                resolution.result(),
                resolution.facts()));
    }

    private record AutonomyAssessment(FactAnswer authorized, List<DeliveryPolicyFactsSnapshot.PracticeFact> facts) {}

    private record Resolution(
            DeliveryPolicyResolver.Result result,
            @Nullable Long evaluatedRevision,
            DeliveryPolicyFactsSnapshot facts) {}

    private static java.util.Optional<Long> integralId(@Nullable JsonNode metadata, String key) {
        if (metadata == null || !metadata.path(key).isIntegralNumber()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(metadata.path(key).asLong());
    }

    private static long requireWorkspaceId(AgentJob job) {
        if (job.getWorkspace() == null || job.getWorkspace().getId() == null) {
            throw new JobDeliveryException("Job has no workspace: jobId=" + job.getId());
        }
        return job.getWorkspace().getId();
    }

    public record Decision<T>(
            @Nullable T artifact, @Nullable FeedbackSuppressionReason suppressionReason) {
        static <T> Decision<T> allowed(T artifact) {
            return new Decision<>(artifact, null);
        }

        public T target() {
            return java.util.Objects.requireNonNull(artifact, "an allowed decision always carries its artifact");
        }

        public FeedbackSuppressionReason refusal() {
            return java.util.Objects.requireNonNull(suppressionReason, "a suppressed decision always names its reason");
        }

        static <T> Decision<T> suppressed(FeedbackSuppressionReason reason) {
            return new Decision<>(null, reason);
        }

        public boolean allowed() {
            return artifact != null;
        }

        DeliveryDecision verdict() {
            return new DeliveryDecision(allowed(), suppressionReason);
        }
    }

    /** A delivery verdict for a caller that needs the answer rather than the work it was decided about. */
    public record DeliveryDecision(
            boolean allowed, @Nullable FeedbackSuppressionReason suppressionReason) {
        public FeedbackSuppressionReason refusal() {
            return java.util.Objects.requireNonNull(suppressionReason, "a suppressed decision always names its reason");
        }
    }
}
