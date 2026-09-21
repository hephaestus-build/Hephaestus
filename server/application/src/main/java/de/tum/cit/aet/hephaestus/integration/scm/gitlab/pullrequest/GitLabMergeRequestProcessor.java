package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest;

import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryScopeFilter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScopeIdResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.Milestone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.MilestoneRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.BaseGitLabProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabUserLookup;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.dto.GitLabWebhookUser;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.dto.GitLabMergeRequestEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processor for GitLab merge requests.
 * <p>
 * Handles conversion of GitLab MR data (from webhooks and GraphQL sync) to PullRequest entities.
 * Follows the same patterns as {@link de.tum.cit.aet.hephaestus.integration.scm.gitlab.issue.GitLabIssueProcessor}.
 * <p>
 * GitLab approvals are mapped to PullRequestReview entities with state APPROVED.
 * Deterministic review IDs prevent collisions with GitHub review IDs.
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabMergeRequestProcessor extends BaseGitLabProcessor {

    private static final Logger log = LoggerFactory.getLogger(GitLabMergeRequestProcessor.class);

    private final PullRequestRepository pullRequestRepository;
    private final PullRequestReviewRepository reviewRepository;
    private final MilestoneRepository milestoneRepository;
    private final ApplicationEventPublisher eventPublisher;

    public GitLabMergeRequestProcessor(
            GitLabUserService gitLabUserService,
            PullRequestRepository pullRequestRepository,
            PullRequestReviewRepository reviewRepository,
            MilestoneRepository milestoneRepository,
            UserRepository userRepository,
            LabelRepository labelRepository,
            RepositoryRepository repositoryRepository,
            ScopeIdResolver scopeIdResolver,
            RepositoryScopeFilter repositoryScopeFilter,
            GitLabProperties gitLabProperties,
            ApplicationEventPublisher eventPublisher) {
        super(
                gitLabUserService,
                userRepository,
                labelRepository,
                repositoryRepository,
                scopeIdResolver,
                repositoryScopeFilter,
                gitLabProperties);
        this.pullRequestRepository = pullRequestRepository;
        this.reviewRepository = reviewRepository;
        this.milestoneRepository = milestoneRepository;
        this.eventPublisher = eventPublisher;
    }

    // Sync Data Records

    public record SyncLabelData(
            @Nullable String globalId,
            @Nullable String title,
            @Nullable String color) {}

    /** Shared record for user references in sync data (assignees, reviewers, approvers). */
    public record SyncUserData(
            @Nullable String globalId,
            @Nullable String username,
            @Nullable String name,
            @Nullable String avatarUrl,
            @Nullable String webUrl,
            @Nullable String publicEmail) {}

    public record SyncMergeRequestData(
            @Nullable String globalId,
            @Nullable String iid,
            @Nullable String title,
            @Nullable String description,
            @Nullable String state,
            boolean draft,
            @Nullable Boolean mergeable,
            @Nullable String detailedMergeStatus,
            boolean approved,
            @Nullable String webUrl,
            @Nullable String createdAt,
            @Nullable String updatedAt,
            @Nullable String closedAt,
            @Nullable String mergedAt,
            int commitCount,
            int additions,
            int deletions,
            int fileCount,
            @Nullable String sourceBranch,
            @Nullable String targetBranch,
            @Nullable String diffHeadSha,
            @Nullable String baseSha,
            @Nullable String mergeCommitSha,
            boolean discussionLocked,
            int commentsCount,
            @Nullable String authorGlobalId,
            @Nullable String authorUsername,
            @Nullable String authorName,
            @Nullable String authorAvatarUrl,
            @Nullable String authorWebUrl,
            @Nullable String authorPublicEmail,
            @Nullable String mergeUserGlobalId,
            @Nullable String mergeUserUsername,
            @Nullable String mergeUserName,
            @Nullable String mergeUserAvatarUrl,
            @Nullable String mergeUserWebUrl,
            @Nullable String mergeUserPublicEmail,
            @Nullable List<SyncLabelData> syncLabels,
            @Nullable List<SyncUserData> syncAssignees,
            @Nullable List<SyncUserData> syncReviewers,
            @Nullable List<SyncUserData> syncApprovers,
            @Nullable List<SyncUserData> syncParticipants,
            @Nullable Integer milestoneIid) {}

    // Webhook Processing

    /**
     * Process a GitLab merge request webhook event (open/update).
     * <p>
     * Returns the existing entity unchanged if the webhook is stale (event's
     * {@code updatedAt} is not newer than the stored value). This allows callers
     * to still publish lifecycle events while preventing stale data from
     * overwriting newer sync data or M:N relationships.
     * <p>
     * Detects draft-to-ready and ready-to-draft transitions on UPDATE events
     * and emits {@link ScmDomainEvent.PullRequestReady} or {@link ScmDomainEvent.PullRequestDrafted}.
     * GitLab does not send separate webhook actions for these transitions (unlike GitHub's
     * {@code ready_for_review} and {@code converted_to_draft}), so we compare the stored
     * draft state against the incoming value.
     */
    @Transactional
    @Nullable
    public PullRequest process(GitLabMergeRequestEventDTO event, ProcessingContext context) {
        return processInternal(event, context);
    }

    @Nullable
    private PullRequest processInternal(GitLabMergeRequestEventDTO event, ProcessingContext context) {
        var attrs = event.objectAttributes();
        if (attrs == null || attrs.id() == null || attrs.iid() == null) {
            log.warn("Skipped merge request event with missing object attributes or identifiers");
            return null;
        }
        if (event.isConfidential()) {
            log.debug("Skipped confidential merge request: iid={}", attrs.iid());
            return null;
        }

        // Stale webhook detection BEFORE upsert to avoid data regression.
        // Returns existing entity so callers can still publish lifecycle events.
        // Also determines isNew and captures old draft state for transition detection.
        boolean isNew = true;
        Boolean wasDraft = null;
        if (attrs.iid() != null) {
            Optional<PullRequest> existingOpt = pullRequestRepository.findByRepositoryIdAndNumber(
                    Objects.requireNonNull(context.repository()).getId(), attrs.iid());
            if (existingOpt.isPresent()) {
                isNew = false;
                PullRequest existing = existingOpt.get();
                wasDraft = existing.isDraft();
                Instant eventUpdatedAt = parseGitLabTimestamp(attrs.updatedAt());
                if (existing.getUpdatedAt() != null
                        && eventUpdatedAt != null
                        && !eventUpdatedAt.isAfter(existing.getUpdatedAt())) {
                    log.debug(
                            "Skipped stale MR webhook: nativeId={}, existingUpdatedAt={}, eventUpdatedAt={}",
                            attrs.id(),
                            existing.getUpdatedAt(),
                            eventUpdatedAt);
                    return existing;
                }
            }
        }

        User author = resolveWebhookAuthor(event, Objects.requireNonNull(context.providerId()));
        User mergedBy = resolveWebhookMergeUser(event, Objects.requireNonNull(context.providerId()));
        Long milestoneId = resolveWebhookMilestoneId(
                attrs.milestoneId(),
                Objects.requireNonNull(Objects.requireNonNull(context.repository())
                        .getProvider()
                        .getId()));

        String headRefOid = attrs.lastCommit() != null ? attrs.lastCommit().id() : null;

        PullRequest pr = upsertMergeRequest(
                attrs.id(),
                attrs.iid(),
                attrs.title(),
                attrs.description(),
                attrs.state(),
                attrs.sourceBranch(),
                attrs.targetBranch(),
                headRefOid,
                attrs.draft(),
                attrs.url(),
                attrs.createdAt(),
                attrs.updatedAt(),
                attrs.closedAt(),
                attrs.mergedAt(),
                author,
                mergedBy,
                milestoneId,
                Objects.requireNonNull(context.repository()),
                context,
                isNew);

        if (pr == null) return null;

        boolean changed = updateLabels(event.labels(), pr.getLabels(), Objects.requireNonNull(context.repository()));
        changed |= updateAssignees(event.assignees(), pr.getAssignees(), Objects.requireNonNull(context.providerId()));
        changed |= updateRequestedReviewers(
                event.reviewers(), pr.getRequestedReviewers(), Objects.requireNonNull(context.providerId()));
        if (changed) {
            pr = pullRequestRepository.save(pr);
        }

        // Detect draft transitions and emit lifecycle events.
        // For new non-draft MRs, PullRequestReady is emitted so the practice review gate
        // can trigger immediately (matching GitHub's behavior for non-draft PR creation).
        var prData = ScmEventPayload.PullRequestData.from(pr);
        var eventCtx = EventContext.from(context);
        if (isNew && !attrs.draft()) {
            eventPublisher.publishEvent(new ScmDomainEvent.PullRequestReady(prData, eventCtx));
            log.debug("New non-draft merge request ready: prId={}", pr.getId());
        } else if (!isNew && wasDraft != null) {
            if (wasDraft && !attrs.draft()) {
                eventPublisher.publishEvent(new ScmDomainEvent.PullRequestReady(prData, eventCtx));
                log.info("Merge request marked ready: prId={}, iid={}", pr.getId(), attrs.iid());
            } else if (!wasDraft && attrs.draft()) {
                eventPublisher.publishEvent(new ScmDomainEvent.PullRequestDrafted(prData, eventCtx));
                log.info("Merge request converted to draft: prId={}, iid={}", pr.getId(), attrs.iid());
            }
        }

        return pr;
    }

    /**
     * Process a closed event (not merged).
     */
    @Transactional
    @Nullable
    public PullRequest processClosed(GitLabMergeRequestEventDTO event, ProcessingContext context) {
        Issue.State before = getExistingState(event, context);
        PullRequest pr = processInternal(event, context);
        if (pr != null && before != Issue.State.CLOSED && before != Issue.State.MERGED) {
            eventPublisher.publishEvent(new ScmDomainEvent.PullRequestClosed(
                    ScmEventPayload.PullRequestData.from(pr), false, EventContext.from(context)));
            log.debug("Closed merge request: prId={}", pr.getId());
        }
        return pr;
    }

    /**
     * Process a reopened event.
     */
    @Transactional
    @Nullable
    public PullRequest processReopened(GitLabMergeRequestEventDTO event, ProcessingContext context) {
        Issue.State before = getExistingState(event, context);
        PullRequest pr = processInternal(event, context);
        if (pr != null && before != Issue.State.OPEN) {
            eventPublisher.publishEvent(new ScmDomainEvent.PullRequestReopened(
                    ScmEventPayload.PullRequestData.from(pr), EventContext.from(context)));
            log.debug("Reopened merge request: prId={}", pr.getId());
        }
        return pr;
    }

    /**
     * Process a merged event.
     */
    @Transactional
    @Nullable
    public PullRequest processMerged(GitLabMergeRequestEventDTO event, ProcessingContext context) {
        Issue.State before = getExistingState(event, context);
        PullRequest pr = processInternal(event, context);
        if (pr != null && before != Issue.State.MERGED) {
            var prData = ScmEventPayload.PullRequestData.from(pr);
            if (before != Issue.State.CLOSED) {
                eventPublisher.publishEvent(
                        new ScmDomainEvent.PullRequestClosed(prData, true, EventContext.from(context)));
            }
            eventPublisher.publishEvent(new ScmDomainEvent.PullRequestMerged(prData, EventContext.from(context)));
            log.debug("Merged merge request: prId={}", pr.getId());
        }
        return pr;
    }

    /**
     * Process an approval event.
     *
     * <p>Creates a new APPROVED review or updates an existing review (e.g., from
     * CHANGES_REQUESTED after a previous unapproval) to APPROVED state.
     */
    @Transactional
    @Nullable
    public PullRequest processApproved(GitLabMergeRequestEventDTO event, ProcessingContext context) {
        PullRequest pr = processInternal(event, context);
        if (pr == null || event.user() == null) return pr;

        User approver = findOrCreateUser(event.user(), Objects.requireNonNull(context.providerId()));
        if (approver == null) return pr;

        long approvalNativeId = generateApprovalNativeId(pr.getNativeId(), approver.getNativeId());
        var existingReview = reviewRepository.findByNativeIdAndProviderId(
                approvalNativeId, Objects.requireNonNull(context.providerId()));

        if (existingReview.isPresent()) {
            // Re-approval: update existing review (may be DISMISSED from unapproval or CHANGES_REQUESTED)
            PullRequestReview review = existingReview.get();
            if (review.getState() != PullRequestReview.State.APPROVED) {
                review.setState(PullRequestReview.State.APPROVED);
                review.setSubmittedAt(Instant.now());
                review.setUpdatedAt(Instant.now());
                reviewRepository.save(review);

                ScmEventPayload.ReviewData.from(review)
                        .ifPresent(reviewData -> eventPublisher.publishEvent(
                                new ScmDomainEvent.ReviewSubmitted(reviewData, EventContext.from(context))));
                log.debug("Updated review to APPROVED: prId={}, reviewerId={}", pr.getId(), approver.getLogin());
            }
        } else {
            // First approval: create new review
            PullRequestReview review = createApprovalReview(approvalNativeId, pr, approver);
            reviewRepository.save(review);
            pr.addReview(review);

            ScmEventPayload.ReviewData.from(review)
                    .ifPresent(reviewData -> eventPublisher.publishEvent(
                            new ScmDomainEvent.ReviewSubmitted(reviewData, EventContext.from(context))));
            log.debug("Created approval review: prId={}, reviewerId={}", pr.getId(), approver.getLogin());
        }

        return pr;
    }

    /**
     * Process an unapproval event.
     *
     * <p>Dismisses the existing approval review. Unapproval means "I retract my approval"
     * — it does NOT mean "I request changes." These are distinct actions in GitLab:
     * <ul>
     *   <li><b>Unapproval</b> ({@code unapproved} webhook): revokes an existing approval.
     *       Fires when the user clicks "Revoke approval" or when the system auto-revokes
     *       after new commits. The review transitions to DISMISSED.</li>
     *   <li><b>Request changes</b> (detected via note {@code detailed_merge_status}):
     *       explicitly blocks the MR. Handled by
     *       {@link #processRequestedChangesFromNote}.</li>
     * </ul>
     */
    @Transactional
    @Nullable
    public PullRequest processUnapproved(GitLabMergeRequestEventDTO event, ProcessingContext context) {
        PullRequest pr = processInternal(event, context);
        if (pr == null || event.user() == null) return pr;

        User approver = findOrCreateUser(event.user(), Objects.requireNonNull(context.providerId()));
        if (approver == null) return pr;

        long approvalNativeId = generateApprovalNativeId(pr.getNativeId(), approver.getNativeId());
        reviewRepository
                .findByNativeIdAndProviderId(approvalNativeId, Objects.requireNonNull(context.providerId()))
                .ifPresent(review -> {
                    if (review.getState() == PullRequestReview.State.DISMISSED) {
                        log.debug(
                                "Review already DISMISSED, skipping: prId={}, reviewerId={}",
                                pr.getId(),
                                approver.getLogin());
                        return;
                    }
                    review.setState(PullRequestReview.State.DISMISSED);
                    review.setDismissed(true);
                    review.setUpdatedAt(Instant.now());
                    reviewRepository.save(review);

                    ScmEventPayload.ReviewData.from(review)
                            .ifPresent(reviewData -> eventPublisher.publishEvent(
                                    new ScmDomainEvent.ReviewDismissed(reviewData, EventContext.from(context))));
                    log.debug("Dismissed review (unapproval): prId={}, reviewerId={}", pr.getId(), approver.getLogin());
                });

        return pr;
    }

    /**
     * Updates an existing review to CHANGES_REQUESTED when detected from a note event.
     *
     * <p>GitLab's "Request changes" feature (Premium, GA 17.3) does NOT fire a dedicated
     * MR webhook. Instead, note events from the batch review carry
     * {@code merge_request.detailed_merge_status = "requested_changes"}.
     * This method is called from the note handler when that signal is detected.
     *
     * <p><b>Important:</b> This method only UPDATES existing reviews — it does NOT create
     * new ones. The {@code detailed_merge_status} is an MR-level status that persists on
     * ALL subsequent note events (not just notes from the reviewer who requested changes).
     * Creating new reviews from this signal would cause false positives: any commenter on
     * an MR with active change requests would be falsely attributed. New CHANGES_REQUESTED
     * reviews without a prior approval are created by the GraphQL sync path instead.
     *
     * @param pr the pull request
     * @param reviewer the user who requested changes (note author)
     * @param context the processing context
     */
    @Transactional
    public void processRequestedChangesFromNote(PullRequest pr, User reviewer, ProcessingContext context) {
        if (pr.getNativeId() == null || reviewer.getNativeId() == null) return;

        long approvalNativeId = generateApprovalNativeId(pr.getNativeId(), reviewer.getNativeId());
        var existingReview = reviewRepository.findByNativeIdAndProviderId(
                approvalNativeId, Objects.requireNonNull(context.providerId()));

        if (existingReview.isEmpty()) {
            // No existing review for this reviewer — cannot safely attribute from note signal.
            // The sync path will create the review with correct attribution.
            log.debug(
                    "No existing review to update from note signal, deferring to sync: prId={}, reviewer={}",
                    pr.getId(),
                    reviewer.getLogin());
            return;
        }

        PullRequestReview review = existingReview.get();
        if (review.getState() == PullRequestReview.State.CHANGES_REQUESTED) {
            log.debug(
                    "Review already CHANGES_REQUESTED from note signal: prId={}, reviewer={}",
                    pr.getId(),
                    reviewer.getLogin());
            return;
        }
        review.setState(PullRequestReview.State.CHANGES_REQUESTED);
        review.setSubmittedAt(Instant.now());
        review.setUpdatedAt(Instant.now());
        reviewRepository.save(review);

        ScmEventPayload.ReviewData.from(review)
                .ifPresent(reviewData -> eventPublisher.publishEvent(
                        new ScmDomainEvent.ReviewSubmitted(reviewData, EventContext.from(context))));
        log.info(
                "Updated review to CHANGES_REQUESTED (from note signal): prId={}, reviewer={}",
                pr.getId(),
                reviewer.getLogin());
    }

    // Sync Processing

    /**
     * Looks up the current state of an existing PR before processing a webhook event.
     * Returns null if the PR does not exist yet.
     */
    private Issue.@Nullable State getExistingState(GitLabMergeRequestEventDTO event, ProcessingContext context) {
        if (event.objectAttributes() == null || event.objectAttributes().iid() == null) {
            return null;
        }
        return pullRequestRepository
                .findByRepositoryIdAndNumber(
                        Objects.requireNonNull(context.repository()).getId(),
                        event.objectAttributes().iid())
                .map(PullRequest::getState)
                .orElse(null);
    }

    /**
     * Process a GitLab merge request from GraphQL sync.
     */
    @Transactional
    @Nullable
    public PullRequest processFromSync(SyncMergeRequestData data, Repository repository, @Nullable Long scopeId) {
        if (data.globalId() == null || data.iid() == null || data.title() == null || data.state() == null) {
            log.warn("Skipped merge request processing: reason=missingRequiredData");
            return null;
        }
        long nativeId;
        try {
            nativeId = GitLabSyncConstants.extractNumericId(data.globalId());
        } catch (IllegalArgumentException e) {
            log.warn("Skipped MR processing: reason=invalidGlobalId, gid={}", data.globalId());
            return null;
        }

        int mrNumber;
        try {
            mrNumber = Integer.parseInt(data.iid());
        } catch (NumberFormatException e) {
            log.warn("Skipped MR processing: reason=invalidIid, iid={}", data.iid());
            return null;
        }

        Long providerId = Objects.requireNonNull(repository.getProvider().getId());

        Optional<PullRequest> existingOpt =
                pullRequestRepository.findByRepositoryIdAndNumber(repository.getId(), mrNumber);
        boolean isNew = existingOpt.isEmpty();
        // Read before the upsert below overwrites the row; it's the only place the prior draft state survives.
        Boolean wasDraft = existingOpt.map(PullRequest::isDraft).orElse(null);

        User author = findOrCreateUser(
                new GitLabUserLookup(
                        data.authorGlobalId(),
                        data.authorUsername(),
                        data.authorName(),
                        data.authorAvatarUrl(),
                        data.authorWebUrl(),
                        data.authorPublicEmail()),
                providerId);

        User mergeUser = findOrCreateUser(
                new GitLabUserLookup(
                        data.mergeUserGlobalId(),
                        data.mergeUserUsername(),
                        data.mergeUserName(),
                        data.mergeUserAvatarUrl(),
                        data.mergeUserWebUrl(),
                        data.mergeUserPublicEmail()),
                providerId);

        // Identity harvest: seed User rows for anyone who has interacted with the MR so later
        // events (notes, reviews, approvals) do not need to create identities on the hot path.
        // No relationship is attached — PullRequest has no participants column.
        if (data.syncParticipants() != null) {
            for (SyncUserData participant : data.syncParticipants()) {
                findOrCreateUser(
                        new GitLabUserLookup(
                                participant.globalId(),
                                participant.username(),
                                participant.name(),
                                participant.avatarUrl(),
                                participant.webUrl(),
                                participant.publicEmail()),
                        providerId);
            }
        }

        Issue.State mrState = convertState(data.state());
        boolean isMerged = "merged".equalsIgnoreCase(data.state());
        String reviewDecision = deriveReviewDecision(data.approved(), data.detailedMergeStatus());
        String mergeStateStatus = mapDetailedMergeStatus(data.detailedMergeStatus());

        // Resolve milestone by iid + repository (milestones are synced before MRs)
        Long milestoneId = null;
        if (data.milestoneIid() != null) {
            milestoneId = milestoneRepository
                    .findByNumberAndRepositoryId(data.milestoneIid(), repository.getId())
                    .map(Milestone::getId)
                    .orElse(null);
        }

        Instant now = Instant.now();
        // GitLab returns closedAt=null for merged MRs; fall back to mergedAt so closed_at
        // reflects the true terminal timestamp (needed for leaderboard issue-state windows).
        Instant closedAtTimestamp = parseGitLabTimestamp(data.closedAt());
        Instant mergedAtTimestamp = parseGitLabTimestamp(data.mergedAt());
        if (closedAtTimestamp == null && isMerged) {
            closedAtTimestamp = mergedAtTimestamp;
        }
        pullRequestRepository.upsertCore(
                nativeId,
                providerId,
                mrNumber,
                Objects.requireNonNullElse(sanitize(data.title()), ""),
                sanitize(data.description()),
                mrState.name(),
                null, // stateReason
                data.webUrl(),
                data.discussionLocked(),
                closedAtTimestamp,
                data.commentsCount(),
                now,
                parseGitLabTimestamp(data.createdAt()),
                parseGitLabTimestamp(data.updatedAt()),
                author != null ? author.getId() : null,
                repository.getId(),
                milestoneId,
                mergedAtTimestamp,
                data.draft(),
                isMerged,
                data.commitCount(),
                data.additions(),
                data.deletions(),
                data.fileCount(),
                reviewDecision,
                mergeStateStatus,
                data.mergeable(),
                data.sourceBranch(),
                data.targetBranch(),
                data.diffHeadSha(),
                data.baseSha(),
                mergeUser != null ? mergeUser.getId() : null,
                data.mergeCommitSha());

        PullRequest pr = pullRequestRepository
                .findByRepositoryIdAndNumber(repository.getId(), mrNumber)
                .orElseThrow(() -> new IllegalStateException(
                        "PullRequest not found after upsert: nativeId=" + nativeId + ", iid=" + data.iid()));

        pr.setProvider(repository.getProvider());

        boolean changed = updateSyncLabels(data.syncLabels(), pr.getLabels(), repository);
        changed |= updateSyncAssignees(data.syncAssignees(), pr.getAssignees(), providerId);
        changed |= updateSyncReviewers(data.syncReviewers(), pr.getRequestedReviewers(), providerId);
        if (changed) {
            pr = pullRequestRepository.save(pr);
        }

        // Reconcile approvals (needs ctx for activity event emission)
        ProcessingContext ctx = ProcessingContext.forSync(scopeId, repository);
        reconcileApprovals(data.syncApprovers(), pr, providerId, ctx);
        var prData = ScmEventPayload.PullRequestData.from(pr);
        var eventCtx = EventContext.from(ctx);

        if (isNew) {
            eventPublisher.publishEvent(new ScmDomainEvent.PullRequestCreated(prData, eventCtx));

            // Emit lifecycle events for MRs that are already in a terminal state
            // when first seen during sync (e.g. historical merged/closed MRs).
            if (isMerged) {
                eventPublisher.publishEvent(new ScmDomainEvent.PullRequestClosed(prData, true, eventCtx));
                eventPublisher.publishEvent(new ScmDomainEvent.PullRequestMerged(prData, eventCtx));
            } else if (mrState == Issue.State.CLOSED) {
                eventPublisher.publishEvent(new ScmDomainEvent.PullRequestClosed(prData, false, eventCtx));
            }

            log.debug("Created merge request from sync: nativeId={}, iid={}", nativeId, data.iid());
        } else {
            eventPublisher.publishEvent(new ScmDomainEvent.PullRequestUpdated(prData, Set.of(), eventCtx));
            log.debug("Updated merge request from sync: nativeId={}, iid={}", nativeId, data.iid());
        }

        // Sync raises the same draft transition a missed webhook would have, so reconciliation can't skip it.
        if (wasDraft != null) {
            if (wasDraft && !data.draft()) {
                eventPublisher.publishEvent(new ScmDomainEvent.PullRequestReady(prData, eventCtx));
                log.info("Merge request found ready during sync: prId={}, iid={}", pr.getId(), data.iid());
            } else if (!wasDraft && data.draft()) {
                eventPublisher.publishEvent(new ScmDomainEvent.PullRequestDrafted(prData, eventCtx));
                log.info("Merge request found converted to draft during sync: prId={}, iid={}", pr.getId(), data.iid());
            }
        }

        return pr;
    }

    // Private Helpers

    @Nullable
    private User resolveWebhookAuthor(GitLabMergeRequestEventDTO event, Long providerId) {
        var attrs = event.objectAttributes();
        if (attrs == null) return null;
        Long authorId = attrs.authorId();
        GitLabWebhookUser eventUser = event.user();

        // If the event user IS the author, use the webhook user data to upsert
        if (eventUser != null && authorId != null && authorId.equals(eventUser.id())) {
            return findOrCreateUser(eventUser, providerId);
        }

        // Otherwise, look up by authorId if available
        if (authorId != null) {
            return userRepository
                    .findByNativeIdAndProviderId(authorId, providerId)
                    .orElse(null);
        }

        // Fallback: try the event user
        return findOrCreateUser(eventUser, providerId);
    }

    @Nullable
    private User resolveWebhookMergeUser(GitLabMergeRequestEventDTO event, Long providerId) {
        var attrs = event.objectAttributes();
        if (attrs == null) return null;
        Long mergeUserId = attrs.mergeUserId();
        if (mergeUserId == null) return null;

        if (event.user() != null && mergeUserId.equals(event.user().id())) {
            return findOrCreateUser(event.user(), providerId);
        }

        return userRepository
                .findByNativeIdAndProviderId(mergeUserId, providerId)
                .orElse(null);
    }

    @Nullable
    private Long resolveWebhookMilestoneId(@Nullable Long gitlabMilestoneId, Long providerId) {
        if (gitlabMilestoneId == null) {
            return null;
        }
        return milestoneRepository
                .findByNativeIdAndProviderId(gitlabMilestoneId, providerId)
                .map(Milestone::getId)
                .orElse(null);
    }

    @Nullable
    private PullRequest upsertMergeRequest(
            Long rawId,
            Integer iid,
            @Nullable String title,
            @Nullable String description,
            @Nullable String state,
            @Nullable String sourceBranch,
            @Nullable String targetBranch,
            @Nullable String headRefOid,
            boolean draft,
            @Nullable String htmlUrl,
            @Nullable String createdAt,
            @Nullable String updatedAt,
            @Nullable String closedAt,
            @Nullable String mergedAt,
            @Nullable User author,
            @Nullable User mergedBy,
            @Nullable Long milestoneId,
            Repository repository,
            ProcessingContext context,
            boolean isNew) {
        if (rawId == null || iid == null) {
            log.warn("Skipped MR processing: reason=missingIdOrIid");
            return null;
        }

        long nativeId = rawId;
        int mrNumber = iid;
        Long providerId = Objects.requireNonNull(repository.getProvider().getId());

        Issue.State mrState = convertState(state);
        boolean isMerged = mrState == Issue.State.MERGED;

        Instant now = Instant.now();
        // GitLab webhooks also report closedAt=null for merged MRs; fall back to mergedAt.
        Instant closedAtTimestamp = parseGitLabTimestamp(closedAt);
        Instant mergedAtTimestamp = parseGitLabTimestamp(mergedAt);
        if (closedAtTimestamp == null && isMerged) {
            closedAtTimestamp = mergedAtTimestamp;
        }
        pullRequestRepository.upsertCore(
                nativeId,
                providerId,
                mrNumber,
                Objects.requireNonNullElse(sanitize(title), ""),
                sanitize(description),
                mrState.name(),
                null,
                htmlUrl,
                null, // isLocked: not in webhook — null lets COALESCE preserve existing or default
                closedAtTimestamp,
                null, // commentsCount: not in webhook — null lets COALESCE preserve existing or default
                now, // lastSyncAt
                parseGitLabTimestamp(createdAt),
                parseGitLabTimestamp(updatedAt),
                author != null ? author.getId() : null,
                repository.getId(),
                milestoneId,
                mergedAtTimestamp,
                draft,
                isMerged,
                null,
                null,
                null,
                null, // commits, additions, deletions, changedFiles — not in webhook, null preserves existing
                null,
                null,
                null, // reviewDecision, mergeStateStatus, mergeable — not in webhook
                sourceBranch,
                targetBranch,
                headRefOid,
                null, // baseRefOid — not in webhook, null preserves existing
                mergedBy != null ? mergedBy.getId() : null,
                null // mergeCommitSha — not in webhook, null preserves existing
                );

        PullRequest pr = pullRequestRepository
                .findByRepositoryIdAndNumber(repository.getId(), mrNumber)
                .orElseThrow(() -> new IllegalStateException(
                        "PullRequest not found after upsert: nativeId=" + nativeId + ", number=" + mrNumber));

        pr.setProvider(repository.getProvider());

        if (isNew) {
            eventPublisher.publishEvent(new ScmDomainEvent.PullRequestCreated(
                    ScmEventPayload.PullRequestData.from(pr), EventContext.from(context)));
            log.debug("Created merge request: nativeId={}, iid={}", nativeId, mrNumber);
        } else {
            eventPublisher.publishEvent(new ScmDomainEvent.PullRequestUpdated(
                    ScmEventPayload.PullRequestData.from(pr), Set.of(), EventContext.from(context)));
            log.debug("Updated merge request: nativeId={}, iid={}", nativeId, mrNumber);
        }

        return pr;
    }

    private static Issue.State convertState(@Nullable String state) {
        if (state == null) return Issue.State.OPEN;
        return switch (state.toLowerCase()) {
            case "opened" -> Issue.State.OPEN;
            case "closed" -> Issue.State.CLOSED;
            case "merged" -> Issue.State.MERGED;
            case "locked" -> Issue.State.CLOSED;
            default -> {
                log.warn("Unknown GitLab MR state '{}', defaulting to OPEN", state);
                yield Issue.State.OPEN;
            }
        };
    }

    /**
     * Derives the PR review decision from GitLab's binary approval flag plus detailed merge status.
     *
     * <p>GitLab's {@code approved} field is binary and cannot express CHANGES_REQUESTED. The
     * {@code detailed_merge_status == "requested_changes"} signal (Premium, GA 17.3) surfaces
     * active change requests on the MR, so we lift it into the three-state model that the
     * leaderboard/profile UI expects.
     */
    private static String deriveReviewDecision(boolean approved, @Nullable String detailedStatus) {
        if (approved) {
            return "APPROVED";
        }
        if (detailedStatus != null && "requested_changes".equalsIgnoreCase(detailedStatus)) {
            return "CHANGES_REQUESTED";
        }
        return "REVIEW_REQUIRED";
    }

    @Nullable
    private static String mapDetailedMergeStatus(@Nullable String detailedStatus) {
        if (detailedStatus == null) return null;
        return switch (detailedStatus.toLowerCase()) {
            case "mergeable" -> "CLEAN";
            case "broken_status", "ci_must_pass", "ci_still_running" -> "UNSTABLE";
            case "checking" -> "UNKNOWN";
            case "conflict", "need_rebase" -> "DIRTY";
            case "not_approved", "blocked_status", "policies_denied", "requested_changes" -> "BLOCKED";
            case "not_open" -> "BEHIND";
            default -> "UNKNOWN";
        };
    }

    /**
     * Generates a deterministic native ID for a GitLab approval review.
     * <p>
     * Layout: {@code [mrNativeId (31 bits)][userNativeId (32 bits)]}, with bit 63 cleared
     * to guarantee a positive result.
     * <p>
     * Collision-free when MR native IDs fit in 31 bits ({@code <= Integer.MAX_VALUE})
     * and user native IDs fit in 32 bits. When either exceeds its safe range,
     * collisions become possible due to bit truncation, and a warning is logged.
     */
    public static long generateApprovalNativeId(long mrNativeId, long userNativeId) {
        if (mrNativeId > Integer.MAX_VALUE || userNativeId > Integer.MAX_VALUE) {
            log.warn(
                    "Native IDs exceed safe range, review nativeId may collide: mrNativeId={}, userNativeId={}",
                    mrNativeId,
                    userNativeId);
        }
        long combined = ((mrNativeId & 0xFFFFFFFFL) << 32) | (userNativeId & 0xFFFFFFFFL);
        return combined & Long.MAX_VALUE; // ensure positive
    }

    private PullRequestReview createApprovalReview(long approvalNativeId, PullRequest pr, User approver) {
        PullRequestReview review = new PullRequestReview();
        review.setNativeId(approvalNativeId);
        review.setProvider(pr.getProvider());
        review.setState(PullRequestReview.State.APPROVED);
        review.setHtmlUrl(pr.getHtmlUrl() + "#approvals");
        // GitLab GraphQL exposes approvedBy as a plain UserCore connection without a
        // per-user approvedAt timestamp, so we use the MR-level merged/updated time
        // (deterministic — not Instant.now()) as the best-effort approval instant.
        Instant approvalInstant = resolveApprovalInstant(pr);
        review.setSubmittedAt(approvalInstant);
        review.setCreatedAt(approvalInstant);
        review.setUpdatedAt(approvalInstant);
        // Anchor the approval to the MR head commit so downstream consumers have a
        // commit SHA. Falls back to mergeCommitSha when the head is unavailable.
        review.setCommitId(resolveApprovalCommit(pr));
        review.setAuthor(approver);
        review.setPullRequest(pr);
        return review;
    }

    /**
     * Best-effort approval timestamp for a GitLab MR: prefers {@code mergedAt},
     * falls back to {@code updatedAt}, then {@code createdAt}, then
     * {@link Instant#EPOCH} as a final deterministic fallback.
     */
    private static Instant resolveApprovalInstant(PullRequest pr) {
        if (pr.getMergedAt() != null) return pr.getMergedAt();
        if (pr.getUpdatedAt() != null) return pr.getUpdatedAt();
        if (pr.getCreatedAt() != null) return pr.getCreatedAt();
        return Instant.EPOCH;
    }

    /**
     * Returns the commit SHA the approval should anchor to. Prefers the MR head
     * ({@code headRefOid}), falls back to the merge commit. May return {@code null}
     * when neither is populated (e.g., minimal PR stubs created from webhooks).
     */
    @Nullable
    private static String resolveApprovalCommit(PullRequest pr) {
        if (pr.getHeadRefOid() != null && !pr.getHeadRefOid().isBlank()) {
            return pr.getHeadRefOid();
        }
        if (pr.getMergeCommitSha() != null && !pr.getMergeCommitSha().isBlank()) {
            return pr.getMergeCommitSha();
        }
        return null;
    }

    private void reconcileApprovals(
            @Nullable List<SyncUserData> syncApprovers,
            PullRequest pr,
            Long providerId,
            @Nullable ProcessingContext ctx) {
        if (syncApprovers == null) return;

        Set<Long> expectedNativeIds = new HashSet<>();

        // Map existing reviews by nativeId for efficient lookup
        Map<Long, PullRequestReview> existingReviewsByNativeId = pr.getReviews().stream()
                .filter(r -> r.getProvider() != null
                        && Objects.requireNonNull(r.getProvider().getId()).equals(providerId))
                .collect(Collectors.toMap(PullRequestReview::getNativeId, r -> r, (a, b) -> a));

        for (SyncUserData approver : syncApprovers) {
            User user = findOrCreateUser(
                    new GitLabUserLookup(
                            approver.globalId(),
                            approver.username(),
                            approver.name(),
                            approver.avatarUrl(),
                            approver.webUrl(),
                            approver.publicEmail()),
                    providerId);
            if (user == null) continue;

            long approvalNativeId = generateApprovalNativeId(pr.getNativeId(), user.getNativeId());
            expectedNativeIds.add(approvalNativeId);

            PullRequestReview existingReview = existingReviewsByNativeId.get(approvalNativeId);
            if (existingReview != null) {
                boolean changed = false;
                // Review exists - update to APPROVED if it was CHANGES_REQUESTED
                if (existingReview.getState() != PullRequestReview.State.APPROVED) {
                    Instant approvalInstant = resolveApprovalInstant(pr);
                    existingReview.setState(PullRequestReview.State.APPROVED);
                    existingReview.setSubmittedAt(approvalInstant);
                    existingReview.setUpdatedAt(approvalInstant);
                    changed = true;
                    log.debug(
                            "Updated review to APPROVED from sync: prId={}, reviewerId={}",
                            pr.getId(),
                            user.getLogin());
                }
                // Backfill commit SHA on legacy rows that were created before we anchored
                // approvals to a commit.
                if (existingReview.getCommitId() == null) {
                    String commit = resolveApprovalCommit(pr);
                    if (commit != null) {
                        existingReview.setCommitId(commit);
                        changed = true;
                    }
                }
                if (changed) {
                    reviewRepository.save(existingReview);

                    if (ctx != null) {
                        ScmEventPayload.ReviewData.from(existingReview)
                                .ifPresent(reviewData -> eventPublisher.publishEvent(
                                        new ScmDomainEvent.ReviewSubmitted(reviewData, EventContext.from(ctx))));
                    }
                }
            } else {
                // No review exists - create new
                PullRequestReview review = createApprovalReview(approvalNativeId, pr, user);
                reviewRepository.save(review);
                pr.addReview(review);
                log.debug("Created approval review from sync: prId={}, reviewerId={}", pr.getId(), user.getLogin());

                if (ctx != null) {
                    ScmEventPayload.ReviewData.from(review)
                            .ifPresent(reviewData -> eventPublisher.publishEvent(
                                    new ScmDomainEvent.ReviewSubmitted(reviewData, EventContext.from(ctx))));
                }
            }
        }

        // Dismiss stale approval reviews (user no longer in approvedBy — approval was revoked)
        // Only target reviews from this provider with APPROVED state
        Set<PullRequestReview> staleReviews = pr.getReviews().stream()
                .filter(r -> r.getState() == PullRequestReview.State.APPROVED)
                .filter(r -> r.getProvider() != null
                        && Objects.requireNonNull(r.getProvider().getId()).equals(providerId))
                .filter(r -> !expectedNativeIds.contains(r.getNativeId()))
                .collect(Collectors.toSet());

        for (PullRequestReview stale : staleReviews) {
            stale.setState(PullRequestReview.State.DISMISSED);
            stale.setDismissed(true);
            stale.setUpdatedAt(Instant.now());
            reviewRepository.save(stale);
            log.debug("Dismissed stale review from sync: prId={}, nativeId={}", pr.getId(), stale.getNativeId());

            if (ctx != null) {
                ScmEventPayload.ReviewData.from(stale)
                        .ifPresent(reviewData -> eventPublisher.publishEvent(
                                new ScmDomainEvent.ReviewDismissed(reviewData, EventContext.from(ctx))));
            }
        }
    }

    private boolean updateRequestedReviewers(
            @Nullable List<GitLabWebhookUser> reviewerDtos, Set<User> currentReviewers, Long providerId) {
        if (reviewerDtos == null) return false;

        Set<User> newReviewers = new HashSet<>();
        for (var dto : reviewerDtos) {
            User user = findOrCreateUser(dto, providerId);
            if (user != null) newReviewers.add(user);
        }

        if (!currentReviewers.equals(newReviewers)) {
            currentReviewers.clear();
            currentReviewers.addAll(newReviewers);
            return true;
        }
        return false;
    }

    private boolean updateSyncLabels(
            @Nullable List<SyncLabelData> syncLabels, Collection<Label> currentLabels, Repository repository) {
        if (syncLabels == null) return false;

        Set<Label> newLabels = new HashSet<>();
        for (SyncLabelData data : syncLabels) {
            Label label = findOrCreateLabel(data.title(), data.color(), repository);
            if (label != null) newLabels.add(label);
        }

        if (!new HashSet<>(currentLabels).equals(newLabels)) {
            currentLabels.clear();
            currentLabels.addAll(newLabels);
            return true;
        }
        return false;
    }

    private boolean updateSyncAssignees(
            @Nullable List<SyncUserData> syncAssignees, Set<User> currentAssignees, Long providerId) {
        if (syncAssignees == null) return false;

        Set<User> newAssignees = new HashSet<>();
        for (SyncUserData data : syncAssignees) {
            User user = findOrCreateUser(
                    new GitLabUserLookup(
                            data.globalId(),
                            data.username(),
                            data.name(),
                            data.avatarUrl(),
                            data.webUrl(),
                            data.publicEmail()),
                    providerId);
            if (user != null) newAssignees.add(user);
        }

        if (!currentAssignees.equals(newAssignees)) {
            currentAssignees.clear();
            currentAssignees.addAll(newAssignees);
            return true;
        }
        return false;
    }

    private boolean updateSyncReviewers(
            @Nullable List<SyncUserData> syncReviewers, Set<User> currentReviewers, Long providerId) {
        if (syncReviewers == null) return false;

        Set<User> newReviewers = new HashSet<>();
        for (SyncUserData data : syncReviewers) {
            User user = findOrCreateUser(
                    new GitLabUserLookup(
                            data.globalId(),
                            data.username(),
                            data.name(),
                            data.avatarUrl(),
                            data.webUrl(),
                            data.publicEmail()),
                    providerId);
            if (user != null) newReviewers.add(user);
        }

        if (!currentReviewers.equals(newReviewers)) {
            currentReviewers.clear();
            currentReviewers.addAll(newReviewers);
            return true;
        }
        return false;
    }
}
