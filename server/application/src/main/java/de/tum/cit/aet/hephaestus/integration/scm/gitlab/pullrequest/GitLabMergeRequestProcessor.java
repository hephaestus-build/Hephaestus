package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryScopeFilter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScopeIdResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.Milestone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.MilestoneRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.CheckState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.MergeStateStatus;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.RequestedReviewer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.ReviewDecision;
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
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.dto.GitLabMergeRequestReviewerDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
    private final IssueRepository issueRepository;
    private final ApplicationEventPublisher eventPublisher;

    public GitLabMergeRequestProcessor(
            GitLabUserService gitLabUserService,
            PullRequestRepository pullRequestRepository,
            PullRequestReviewRepository reviewRepository,
            MilestoneRepository milestoneRepository,
            IssueRepository issueRepository,
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
        this.issueRepository = issueRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Whether the sync has to read what the merge request closes: on first sight, and whenever the
     * merge request moved since the stored record — the link set changes only with the merge request.
     */
    @Transactional(readOnly = true)
    public boolean closingIssuesStale(Repository repository, int iid, @Nullable String updatedAt) {
        Instant seen = parseGitLabTimestamp(updatedAt);
        return pullRequestRepository
                .findByRepositoryIdAndNumber(repository.getId(), iid)
                .map(stored -> seen == null || !seen.equals(stored.getUpdatedAt()))
                .orElse(true);
    }

    /**
     * The version of merge request {@code iid} stored when a webhook's transaction ends: what a read made after it,
     * outside that transaction, still has to describe. Empty when nothing is stored.
     */
    @Transactional(readOnly = true)
    public Optional<StoredVersion> storedVersion(Repository repository, int iid) {
        return pullRequestRepository
                .findByRepositoryIdAndNumber(repository.getId(), iid)
                .map(pr -> new StoredVersion(pr.getHeadRefOid(), pr.getUpdatedAt()));
    }

    /** A stored merge request's head and GitLab {@code updated_at}, as {@link #storedVersion} captured them. */
    public record StoredVersion(
            @Nullable String head, @Nullable Instant updatedAt) {}

    /**
     * Replaces what the record says a merge request closes with GitLab's current statement, read from
     * the closes-issues route after a webhook: the sync compares {@code updatedAt} against the value
     * that webhook stored, so it would not read the links for this change. A statement read for {@code readFor}
     * changes nothing once another event or a sync stored a newer version, which reads the links itself.
     */
    @Transactional
    public void replaceClosingIssues(
            Repository repository, int iid, List<Integer> closingIssueNumbers, StoredVersion readFor) {
        pullRequestRepository
                .findForUpdateByRepositoryIdAndNumber(repository.getId(), iid)
                .filter(pr -> Objects.equals(pr.getHeadRefOid(), readFor.head())
                        && Objects.equals(pr.getUpdatedAt(), readFor.updatedAt()))
                .ifPresent(pr -> {
                    if (pr.replaceClosingIssues(resolveLocalIssues(repository, closingIssueNumbers))) {
                        pullRequestRepository.save(pr);
                    }
                });
    }

    /**
     * Records what GitLab said about merging merge request {@code iid}, read after a webhook with the read begun at
     * {@code requestedAt}, where it still describes what is stored. Runs after the read, in its own transaction, with
     * the merge request's row locked; the caller checks first that the delivery may still write.
     *
     * <p>Nothing is recorded unless GitLab's answer is about this repository's project and this merge request, both
     * still open, at the head stored now, and not at a version older than the stored one: a push stored meanwhile is
     * read again after its own event, and the answer never moves the head. The head pipeline is recorded as observed
     * ({@link GitLabHeadPipeline}) under its own clock. The reviewers, approvals, review decision, mergeability and
     * merge status — which follow the approvals — are recorded together, only when no hook or read received after
     * {@code requestedAt} is stored ({@link PullRequest#takesReviewSnapshotAt}); mergeability and approvals stay unknown
     * while GitLab is still settling them ({@link #isSettling}), and the approvals come only from a whole list.
     *
     * @return whether the facts were recorded
     */
    @Transactional
    public boolean applyReadiness(
            Repository repository,
            int iid,
            GitLabMergeRequestReadinessReader.Facts facts,
            Instant requestedAt,
            ProcessingContext context) {
        PullRequest pr = pullRequestRepository
                .findForUpdateByRepositoryIdAndNumber(repository.getId(), iid)
                .orElse(null);
        if (pr == null) {
            return false;
        }
        String reason = readinessMismatch(repository, pr, facts, Issue.State.OPEN);
        if (reason != null) {
            log.debug("Skipped merge request readiness: prId={}, reason={}", pr.getId(), reason);
            return false;
        }
        Long providerId = Objects.requireNonNull(repository.getProvider().getId());
        boolean settling = isSettling(facts.detailedMergeStatus());
        facts.headPipeline().observeOn(pr, requestedAt);
        if (pr.takesReviewSnapshotAt(requestedAt)) {
            ProcessingContext read = context.withObservedAt(requestedAt);
            updateSyncReviewers(facts.reviewers(), pr, providerId, read);
            recordReviewSnapshot(
                    pr,
                    reviewDecision(facts.detailedMergeStatus(), facts.approved(), facts.reviewers(), facts.approvers()),
                    settling ? null : facts.mergeable(),
                    mapDetailedMergeStatus(facts.detailedMergeStatus()));
            pr = pullRequestRepository.save(pr);
            if (!settling) {
                reconcileApprovals(facts.approvers(), pr, providerId, read);
            }
        } else {
            log.debug("Kept reviews stored after the readiness read began: prId={}", pr.getId());
            pullRequestRepository.save(pr);
        }
        return true;
    }

    /** Why GitLab's answer does not describe the stored merge request, or {@code null} when it does. */
    private static @Nullable String readinessMismatch(
            Repository repository,
            PullRequest pr,
            GitLabMergeRequestReadinessReader.Facts facts,
            Issue.State expected) {
        if (facts.projectNativeId() != repository.getNativeId()) {
            return "otherProject";
        }
        if (facts.mergeRequestNativeId() != pr.getNativeId()) {
            return "otherMergeRequest";
        }
        if (pr.getState() != expected || convertState(facts.state()) != expected) {
            return "not" + expected;
        }
        if (!facts.headSha().equals(pr.getHeadRefOid())) {
            return "otherHead";
        }
        if (pr.getUpdatedAt() != null && facts.updatedAt().isBefore(pr.getUpdatedAt())) {
            return "olderVersion";
        }
        return null;
    }

    /**
     * Records who merged merge request {@code iid}, when, and the merge commit, read from GitLab after its merge hook
     * where the hook named none of them. Runs after the read, in its own transaction, with the merge request's row
     * locked; the caller checks first that the delivery may still write.
     *
     * <p>Nothing is recorded unless GitLab's answer is about this repository's project and this merge request, both
     * merged, at the stored head and not at a version older than the stored one. Only what is unknown is filled in, and
     * only with what GitLab named: a merger or commit already stored stays, and one GitLab does not name stays
     * unknown.
     *
     * @return whether anything was recorded
     */
    @Transactional
    public boolean applyTerminalFacts(Repository repository, int iid, GitLabMergeRequestReadinessReader.Facts facts) {
        PullRequest pr = pullRequestRepository
                .findForUpdateByRepositoryIdAndNumber(repository.getId(), iid)
                .orElse(null);
        if (pr == null) {
            return false;
        }
        String reason = readinessMismatch(repository, pr, facts, Issue.State.MERGED);
        if (reason != null) {
            log.debug("Skipped merge facts: prId={}, reason={}", pr.getId(), reason);
            return false;
        }
        GitLabMergeRequestReadinessReader.Merge merge = facts.merge();
        boolean changed = false;
        if (pr.getMergedBy() == null && merge.user() != null) {
            SyncUserData user = merge.user();
            User merger = findOrCreateUser(
                    new GitLabUserLookup(
                            user.globalId(),
                            user.username(),
                            user.name(),
                            user.avatarUrl(),
                            user.webUrl(),
                            user.publicEmail()),
                    Objects.requireNonNull(repository.getProvider().getId()));
            if (merger != null) {
                pr.setMergedBy(merger);
                changed = true;
            }
        }
        if (pr.getMergeCommitSha() == null && merge.commitSha() != null) {
            pr.setMergeCommitSha(merge.commitSha());
            changed = true;
        }
        if (pr.getMergedAt() == null && merge.mergedAt() != null) {
            pr.setMergedAt(merge.mergedAt());
            if (pr.getClosedAt() == null) {
                pr.setClosedAt(merge.mergedAt());
            }
            changed = true;
        }
        if (changed) {
            pullRequestRepository.save(pr);
            log.debug("Recorded merge facts from GitLab: prId={}", pr.getId());
        }
        return changed;
    }

    private Set<Issue> resolveLocalIssues(Repository repository, List<Integer> numbers) {
        Set<Issue> issues = new HashSet<>();
        for (Integer number : numbers) {
            issueRepository
                    .findByRepositoryIdAndNumber(repository.getId(), number)
                    .ifPresent(issues::add);
        }
        return issues;
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

    /** A reviewer in sync data, with GitLab's {@code MergeRequestReviewState} for them when it gave one. */
    public record SyncReviewerData(
            SyncUserData user, @Nullable String reviewState) {}

    public record SyncMergeRequestData(
            @Nullable String globalId,
            @Nullable String iid,
            @Nullable String title,
            @Nullable String description,
            @Nullable String state,
            boolean draft,
            @Nullable Boolean mergeable,
            @Nullable String detailedMergeStatus,
            /** GitLab's {@code approved}; null where the read did not capture it. */
            @Nullable Boolean approved,
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
            @Nullable List<SyncReviewerData> syncReviewers,
            @Nullable List<SyncUserData> syncApprovers,
            @Nullable List<SyncUserData> syncParticipants,
            @Nullable Integer milestoneIid,
            GitLabHeadPipeline headPipeline,
            /**
             * The iids of GitLab's closing candidates for the MR, from the REST closes-issues route;
             * null when this sync did not read them, which leaves the stored set alone.
             */
            @Nullable List<Integer> closingIssueNumbers) {}

    // Webhook Processing

    /**
     * Process a GitLab merge request webhook event (open/update).
     * <p>
     * Returns the existing entity if the webhook is stale (event's {@code updatedAt}
     * is not newer than the stored value), changing at most its reviewers. This allows callers
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
        String previousHead = null;
        if (attrs.iid() != null) {
            Optional<PullRequest> existingOpt = pullRequestRepository.findForUpdateByRepositoryIdAndNumber(
                    Objects.requireNonNull(context.repository()).getId(), attrs.iid());
            if (existingOpt.isPresent()) {
                isNew = false;
                PullRequest existing = existingOpt.get();
                wasDraft = existing.isDraft();
                previousHead = existing.getHeadRefOid();
                Instant eventUpdatedAt = parseGitLabTimestamp(attrs.updatedAt());
                if (existing.getUpdatedAt() != null
                        && eventUpdatedAt != null
                        && !eventUpdatedAt.isAfter(existing.getUpdatedAt())) {
                    // The reviewer list still applies unless the payload is older, as sync-lifecycle.md § Reviewer
                    // lists are dated snapshots explains.
                    if (!eventUpdatedAt.isBefore(existing.getUpdatedAt())
                            && updateRequestedReviewers(event.currentReviewers(), existing, context)) {
                        existing = pullRequestRepository.save(existing);
                    }
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
                        .getId()),
                Objects.requireNonNull(context.repository()));

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
                attrs.mergeCommitSha(),
                author,
                mergedBy,
                milestoneId,
                Objects.requireNonNull(context.repository()),
                context,
                isNew);

        if (pr == null) return null;

        boolean changed = updateLabels(event.labels(), pr.getLabels(), Objects.requireNonNull(context.repository()));
        changed |= updateAssignees(
                event.currentAssignees(), pr.getAssignees(), Objects.requireNonNull(context.providerId()));
        changed |= updateRequestedReviewers(event.currentReviewers(), pr, context);
        // GitLab names the previous head only when the update pushed commits, and not always then, so a
        // moved head counts too; a sync that stored the new head first still leaves oldrev to say so.
        boolean pushed = !isNew
                && headRefOid != null
                && (attrs.oldrev() != null || (previousHead != null && !previousHead.equals(headRefOid)));
        if (pushed) {
            changed |= forgetReadiness(pr);
        }
        if (changed) {
            pr = pullRequestRepository.save(pr);
        }

        // Detect draft transitions and pushes. A new merge request is Created only, as a GitHub pull
        // request opened ready is: raising Ready as well would review the same head twice.
        var prData = ScmEventPayload.PullRequestData.from(pr);
        var eventCtx = EventContext.from(context);
        if (pushed) {
            eventPublisher.publishEvent(new ScmDomainEvent.PullRequestSynchronized(prData, eventCtx));
            log.debug("Merge request received new commits: prId={}, iid={}", pr.getId(), attrs.iid());
        }
        if (!isNew && wasDraft != null) {
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
     * Process a merged event: stores the merge and announces the close. The merge is offered for review by
     * {@link #offerMerge}, after the read that follows the hook.
     */
    @Transactional
    @Nullable
    public PullRequest processMerged(GitLabMergeRequestEventDTO event, ProcessingContext context) {
        Issue.State before = getExistingState(event, context);
        PullRequest pr = processInternal(event, context);
        // Only the close is announced here. The merge itself, the occasion a review is judged on, is offered by
        // offerMerge once the read after this hook has had its chance to record who merged.
        if (pr != null
                && pr.getState() == Issue.State.MERGED
                && before != Issue.State.CLOSED
                && before != Issue.State.MERGED) {
            eventPublisher.publishEvent(new ScmDomainEvent.PullRequestClosed(
                    ScmEventPayload.PullRequestData.from(pr), true, EventContext.from(context)));
            log.debug("Merged merge request: prId={}", pr.getId());
        }
        return pr;
    }

    /**
     * Offers the merge of merge request {@code iid} for review, as a merge hook received in {@code context} reported
     * it: after the hook was stored and the read after it recorded what it could, successful or not, in the caller's
     * transaction under the delivery's still-active route. The merge request is read with its row locked and offered
     * only while it is stored as merged, also when a sync stored it so first — that sync only recorded the merge, and
     * this delivery is what may review it; the signal ledger settles a redelivery. A merger still unknown then holds
     * the review pending rather than running it without them.
     *
     * @return whether the merge was offered
     */
    @Transactional
    public boolean offerMerge(Repository repository, int iid, ProcessingContext context) {
        PullRequest pr = pullRequestRepository
                .findForUpdateByRepositoryIdAndNumber(repository.getId(), iid)
                .orElse(null);
        if (pr == null || pr.getState() != Issue.State.MERGED) {
            return false;
        }
        eventPublisher.publishEvent(new ScmDomainEvent.PullRequestMerged(
                ScmEventPayload.PullRequestData.from(pr), EventContext.from(context)));
        return true;
    }

    /**
     * Process an {@code approved} or {@code approval} event: the hook's user approved. GitLab sends {@code approved}
     * when the approval meets the merge request's approval rules and {@code approval} when approvals are still missing
     * (<a href="https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/ee/app/services/ee/merge_requests/execute_approval_hooks_service.rb">execute_approval_hooks_service.rb</a>),
     * so both are the same act by one person.
     *
     * <p>Creates a new APPROVED review or gives a dismissed one again, anchored to the head the hook names and dated by
     * when Hephaestus received it. It applies only to the merge request as stored now ({@link #actsOnStoredHead}) and
     * only where no later read of the reviews is stored ({@link PullRequest#takesReviewSnapshotAt}); otherwise it
     * changes nothing and announces nothing.
     */
    @Transactional
    @Nullable
    public PullRequest processApproved(GitLabMergeRequestEventDTO event, ProcessingContext context) {
        PullRequest pr = processInternal(event, context);
        if (pr == null || event.user() == null) return pr;
        if (!actsOnStoredHead(event, pr)) {
            log.debug("Skipped approval of another head than the stored one: prId={}", pr.getId());
            return pr;
        }
        if (!pr.takesReviewSnapshotAt(context.observedAt())) {
            log.debug("Skipped approval older than the stored reviews: prId={}", pr.getId());
            return pr;
        }

        User approver = findOrCreateUser(event.user(), Objects.requireNonNull(context.providerId()));
        if (approver == null) return pr;

        long approvalNativeId = generateApprovalNativeId(pr.getNativeId(), approver.getNativeId());
        var existingReview = reviewRepository.findByNativeIdAndProviderId(
                approvalNativeId, Objects.requireNonNull(context.providerId()));
        Instant approvedAt = context.observedAt();
        String approvedCommit = approvedCommit(event, pr);

        if (existingReview.isPresent()) {
            // Re-approval: the approval row was dismissed by an unapproval or a reset
            PullRequestReview review = existingReview.get();
            if (review.getState() != PullRequestReview.State.APPROVED || review.isDismissed()) {
                review.setState(PullRequestReview.State.APPROVED);
                review.setDismissed(false);
                review.setSubmittedAt(approvedAt);
                review.setUpdatedAt(approvedAt);
                review.setCommitId(approvedCommit);
                reviewRepository.save(review);
                forgetReviewReadiness(pr);

                ScmEventPayload.ReviewData.from(review)
                        .ifPresent(reviewData -> eventPublisher.publishEvent(
                                new ScmDomainEvent.ReviewSubmitted(reviewData, EventContext.from(context))));
                log.debug("Updated review to APPROVED: prId={}, reviewerId={}", pr.getId(), approver.getLogin());
            }
        } else {
            // First approval: create new review
            PullRequestReview review = createApprovalReview(approvalNativeId, pr, approver);
            review.setSubmittedAt(approvedAt);
            review.setCreatedAt(approvedAt);
            review.setUpdatedAt(approvedAt);
            review.setCommitId(approvedCommit);
            reviewRepository.save(review);
            pr.addReview(review);
            forgetReviewReadiness(pr);

            ScmEventPayload.ReviewData.from(review)
                    .ifPresent(reviewData -> eventPublisher.publishEvent(
                            new ScmDomainEvent.ReviewSubmitted(reviewData, EventContext.from(context))));
            log.debug("Created approval review: prId={}, reviewerId={}", pr.getId(), approver.getLogin());
        }

        return pr;
    }

    /**
     * Whether one person's approval act, or GitLab's reset, in {@code event} is about the merge request as stored now:
     * the version it describes, GitLab's {@code updated_at}, is not older than the stored one, and the head it names is
     * the stored head. A hook delayed past a later event still arrives after it, so when it came says nothing about
     * either: a stored newer version was written by an event whose readiness read, or by a sync, that read the approvals
     * after this act, and an act on another head is not about this one. A version equal to the stored one applies. A
     * hook whose version cannot be read does not apply over a stored one; one that names no head is judged by its
     * version alone. {@link #processInternal} returning the stored merge request says neither: it returns it for an
     * older event too.
     */
    private static boolean actsOnStoredHead(GitLabMergeRequestEventDTO event, PullRequest pr) {
        var attrs = event.objectAttributes();
        if (attrs == null) {
            return false;
        }
        Instant eventUpdatedAt = parseGitLabTimestamp(attrs.updatedAt());
        Instant storedUpdatedAt = pr.getUpdatedAt();
        if (storedUpdatedAt != null && (eventUpdatedAt == null || eventUpdatedAt.isBefore(storedUpdatedAt))) {
            return false;
        }
        var lastCommit = attrs.lastCommit();
        return lastCommit == null
                || lastCommit.id().isBlank()
                || lastCommit.id().equals(pr.getHeadRefOid());
    }

    /** The commit an approval hook approved: the head it names, or the stored head where it names none. */
    private static @Nullable String approvedCommit(GitLabMergeRequestEventDTO event, PullRequest pr) {
        var attrs = event.objectAttributes();
        if (attrs != null
                && attrs.lastCommit() != null
                && !attrs.lastCommit().id().isBlank()) {
            return attrs.lastCommit().id();
        }
        return resolveApprovalCommit(pr);
    }

    /**
     * Process an {@code unapproved} or {@code unapproval} event: the hook's user withdrew their approval. GitLab sends
     * {@code unapproved} when the merge request stops meeting its approval rules and {@code unapproval} otherwise
     * (<a href="https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/ee/app/services/ee/merge_requests/remove_approval_service.rb">remove_approval_service.rb</a>).
     *
     * <p>Dismisses the existing approval review. Withdrawing an approval is not a request for changes: that is its
     * own system note, recorded by
     * {@link de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreview.GitLabReviewReconciler}.
     *
     * <p>GitLab sends the same actions, marked {@code system}, when it resets approvals itself after a push: all of
     * them ({@code approvals_reset_on_push}) or only Code Owners' ({@code code_owner_approvals_reset_on_push}). Its user
     * is whoever pushed, not someone withdrawing an approval, and the hook does not say whose approvals went. So a reset
     * leaves the review decision unknown and dismisses no one; the readiness read after the event reconciles the
     * approvals with GitLab's whole approver list.
     */
    @Transactional
    @Nullable
    public PullRequest processUnapproved(GitLabMergeRequestEventDTO event, ProcessingContext context) {
        PullRequest pr = processInternal(event, context);
        if (pr == null) return pr;
        if (!actsOnStoredHead(event, pr)) {
            log.debug("Skipped unapproval of another head than the stored one: prId={}", pr.getId());
            return pr;
        }
        var attrs = event.objectAttributes();
        if (attrs != null && attrs.isSystemInitiated()) {
            if (pr.takesReviewSnapshotAt(context.observedAt())) {
                forgetReviewReadiness(pr);
            }
            log.info(
                    "GitLab reset approvals: prId={}, systemAction={}",
                    pr.getId(),
                    sanitizeForLog(attrs.systemAction()));
            return pr;
        }
        if (event.user() == null) return pr;
        if (!pr.takesReviewSnapshotAt(context.observedAt())) {
            log.debug("Skipped unapproval older than the stored reviews: prId={}", pr.getId());
            return pr;
        }

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
                    review.setUpdatedAt(context.observedAt());
                    reviewRepository.save(review);
                    forgetReviewReadiness(pr);

                    ScmEventPayload.ReviewData.from(review)
                            .ifPresent(reviewData -> eventPublisher.publishEvent(
                                    new ScmDomainEvent.ReviewDismissed(reviewData, EventContext.from(context))));
                    log.debug("Dismissed review (unapproval): prId={}, reviewerId={}", pr.getId(), approver.getLogin());
                });

        return pr;
    }

    /**
     * Leaves the merge request's review decision unknown once a webhook changed where one person's review stands, with
     * the mergeability and merge status GitLab derives from the approvals. The hook names that one act; what every
     * reviewer's acts now add up to is the readiness read's or the sync's to read ({@link #reviewDecision}), so what is
     * stored would otherwise outlive the act that changed it, also when that read fails. Runs in the caller's
     * transaction.
     */
    public void forgetReviewReadiness(PullRequest pr) {
        if (forgetReadiness(pr)) {
            pullRequestRepository.save(pr);
        }
    }

    /**
     * Records one read's review decision with the mergeability and merge status GitLab computed from the same approvals:
     * the caller has accepted the read as the newest about the reviews ({@link PullRequest#takesReviewSnapshotAt}).
     *
     * @param mergeStateStatus the {@link MergeStateStatus} name, as {@link #mapDetailedMergeStatus} gives it
     * @return whether anything changed
     */
    private static boolean recordReviewSnapshot(
            PullRequest pr,
            @Nullable ReviewDecision decision,
            @Nullable Boolean mergeable,
            @Nullable String mergeStateStatus) {
        MergeStateStatus status = mergeStateStatus == null ? null : MergeStateStatus.valueOf(mergeStateStatus);
        boolean changed = pr.getReviewDecision() != decision
                || !Objects.equals(pr.getMergeable(), mergeable)
                || pr.getMergeStateStatus() != status;
        pr.setReviewDecision(decision);
        pr.setMergeable(mergeable);
        pr.setMergeStateStatus(status);
        return changed;
    }

    /**
     * Leaves what GitLab said about merging unknown: the review decision, and the mergeability and merge status that
     * follow the approvals. For new commits, or an approval, withdrawal or reset of approvals. The approvals themselves
     * stay as recorded — a project can keep them across a push, and a reset does not say whose went; the readiness
     * read after the event, or the next sync, says which still stand.
     *
     * @return whether anything changed
     */
    private static boolean forgetReadiness(PullRequest pr) {
        boolean changed =
                pr.getReviewDecision() != null || pr.getMergeable() != null || pr.getMergeStateStatus() != null;
        pr.setReviewDecision(null);
        pr.setMergeable(null);
        pr.setMergeStateStatus(null);
        return changed;
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
                .findForUpdateByRepositoryIdAndNumber(
                        Objects.requireNonNull(context.repository()).getId(),
                        event.objectAttributes().iid())
                .map(PullRequest::getState)
                .orElse(null);
    }

    /**
     * Process a GitLab merge request from GraphQL sync.
     *
     * @param context the sync of {@code context.repository()}, observed when it asked for the page the merge request
     *     came from
     */
    @Transactional
    @Nullable
    public PullRequest processFromSync(SyncMergeRequestData data, ProcessingContext context) {
        Repository repository = Objects.requireNonNull(context.repository());
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

        // Locked before the users below, in the order the webhook path takes the same locks.
        Optional<PullRequest> existingOpt =
                pullRequestRepository.findForUpdateByRepositoryIdAndNumber(repository.getId(), mrNumber);
        boolean isNew = existingOpt.isEmpty();
        // A page that describes an older version than the one stored changes nothing: a later webhook or read stored
        // it, and the page's head, checks and approvals would take it back.
        Instant fetchedUpdatedAt = parseGitLabTimestamp(data.updatedAt());
        if (existingOpt.isPresent() && fetchedUpdatedAt == null) {
            // A page that did not capture the version cannot be ordered against the stored merge request.
            log.debug("Skipped merge request read without its version: iid={}", data.iid());
            return existingOpt.get();
        }
        if (existingOpt.isPresent()
                && existingOpt.get().getUpdatedAt() != null
                && fetchedUpdatedAt != null
                && fetchedUpdatedAt.isBefore(existingOpt.get().getUpdatedAt())) {
            log.debug(
                    "Skipped merge request older than the stored one: iid={}, storedUpdatedAt={}, readUpdatedAt={}",
                    data.iid(),
                    existingOpt.get().getUpdatedAt(),
                    fetchedUpdatedAt);
            return existingOpt.get();
        }
        // Read before the upsert below overwrites the row; it's the only place the prior draft state survives.
        Boolean wasDraft = existingOpt.map(PullRequest::isDraft).orElse(null);
        Issue.State previousState = existingOpt.map(PullRequest::getState).orElse(null);
        String previousHead = existingOpt.map(PullRequest::getHeadRefOid).orElse(null);

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
        ReviewDecision reviewDecision =
                reviewDecision(data.detailedMergeStatus(), data.approved(), data.syncReviewers(), data.syncApprovers());
        boolean settling = isSettling(data.detailedMergeStatus());
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
        // reflects the true terminal timestamp (needed for activity time windows).
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
                fetchedUpdatedAt,
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
                null, // reviewDecision: set below, as upsertCore keeps a stored one where it is given none
                null, // mergeStateStatus: recorded with the review snapshot below, as upsertCore keeps a stored one
                null, // mergeable: likewise
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

        // The decision, the approvals and the mergeability and merge status that follow them are part of what was read
        // about the reviews at context.observedAt(): what a hook or read received after the page was asked for stored
        // stands. Where it does and the page moved the head, what is stored describes the old head and is unknown now.
        // What holds for a head — the decision, mergeability, approvals and a missing pipeline — needs the page to have
        // captured which head, and which version, it read: the upsert keeps a stored head where the page gives none, so
        // they would otherwise land on a head the page did not read. A pipeline the page names with its SHA stands on
        // its own.
        boolean headCaptured = fetchedUpdatedAt != null
                && data.diffHeadSha() != null
                && !data.diffHeadSha().isBlank();
        boolean headMoved = headCaptured && previousHead != null && !previousHead.equals(pr.getHeadRefOid());
        boolean reviewsCurrent = headCaptured && pr.takesReviewSnapshotAt(context.observedAt());
        boolean changed = false;
        if (reviewsCurrent) {
            changed |= recordReviewSnapshot(pr, reviewDecision, settling ? null : data.mergeable(), mergeStateStatus);
        } else if (headMoved) {
            changed |= forgetReadiness(pr);
        }
        changed |= updateSyncLabels(data.syncLabels(), pr.getLabels(), repository);
        changed |= updateSyncAssignees(data.syncAssignees(), pr.getAssignees(), providerId);
        changed |= updateSyncReviewers(data.syncReviewers(), pr, providerId, context);
        if (headCaptured || data.headPipeline().kind() == GitLabHeadPipeline.Kind.REPORTED) {
            changed |= data.headPipeline().observeOn(pr, context.observedAt());
        }
        if (data.closingIssueNumbers() != null) {
            changed |= pr.replaceClosingIssues(resolveLocalIssues(repository, data.closingIssueNumbers()));
        }
        if (changed) {
            pr = pullRequestRepository.save(pr);
        }

        // Approvers GitLab is still recomputing after a push are not a settled list.
        if (reviewsCurrent && !settling) {
            reconcileApprovals(data.syncApprovers(), pr, providerId, context);
        }
        var prData = ScmEventPayload.PullRequestData.from(pr);
        var eventCtx = EventContext.from(context);

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
            // A merge the webhook missed is recorded as the sync found it, so a later delivery of it can still
            // claim it; a sync records it without starting a review.
            if (isMerged && previousState != Issue.State.MERGED) {
                if (previousState != Issue.State.CLOSED) {
                    eventPublisher.publishEvent(new ScmDomainEvent.PullRequestClosed(prData, true, eventCtx));
                }
                eventPublisher.publishEvent(new ScmDomainEvent.PullRequestMerged(prData, eventCtx));
            }
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
    private Long resolveWebhookMilestoneId(@Nullable Long gitlabMilestoneId, Long providerId, Repository repository) {
        if (gitlabMilestoneId == null) {
            return null;
        }
        return milestoneRepository
                .findByNativeIdAndProviderId(gitlabMilestoneId, providerId)
                .filter(milestone -> milestone.getRepository().getId().equals(repository.getId()))
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
            @Nullable String mergeCommitSha,
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
                mergeCommitSha // the hook's merge_commit_sha; null, as before a merge, keeps the stored one
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
     * The merge request's review decision from what its reviewers did, or none where the sync did not read that whole.
     *
     * <p>A standing request for changes decides it, over any approval: GitLab's {@code REQUESTED_CHANGES} merge status
     * where the project blocks merging on one (Premium), and a reviewer's {@code REQUESTED_CHANGES} review state on any
     * tier. Otherwise it is approved only when someone approved and GitLab's {@code approved} says the approval rules
     * are met: that flag alone is also true when a project requires no approval and nobody gave one
     * (<a href="https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/ee/app/models/approval_state.rb">approval_state.rb</a>).
     * A reviewer or approver list GitLab did not return whole could hide either, so it leaves the decision unknown, as
     * does an {@code approved} it did not return and a status GitLab is still settling ({@link #isSettling}).
     * {@code approvalsRequired} is not read: GitLab's Community Edition schema has no such field.
     */
    private static @Nullable ReviewDecision reviewDecision(
            @Nullable String detailedMergeStatus,
            @Nullable Boolean approved,
            @Nullable List<SyncReviewerData> reviewers,
            @Nullable List<SyncUserData> approvers) {
        if (isSettling(detailedMergeStatus)) {
            return null;
        }
        boolean changesRequested = "REQUESTED_CHANGES".equalsIgnoreCase(detailedMergeStatus)
                || (reviewers != null
                        && reviewers.stream()
                                .anyMatch(reviewer -> "REQUESTED_CHANGES".equalsIgnoreCase(reviewer.reviewState())));
        if (changesRequested) {
            return ReviewDecision.CHANGES_REQUESTED;
        }
        if (reviewers == null || approvers == null || approved == null) {
            return null;
        }
        return approved && !approvers.isEmpty() ? ReviewDecision.APPROVED : ReviewDecision.REVIEW_REQUIRED;
    }

    /**
     * Whether GitLab is still working out the merge request's status after a change: {@code checking} while it
     * computes mergeability, {@code approvals_syncing} while it recomputes approvals after a push. Neither its
     * mergeability nor its approvals are settled then.
     *
     * @see <a href="https://docs.gitlab.com/api/merge_request_approvals/#prevent-approval-resets-in-automated-merge-requests">GitLab
     *     merge request approvals API</a>
     */
    static boolean isSettling(@Nullable String detailedMergeStatus) {
        return "checking".equalsIgnoreCase(detailedMergeStatus)
                || "approvals_syncing".equalsIgnoreCase(detailedMergeStatus);
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
     * The status of a pipeline GitLab reported as one {@link CheckState}: a pipeline that has not finished is pending
     * whatever stage it is in. A head with no pipeline has no status to map: {@link GitLabHeadPipeline} tells it apart
     * from a pipeline that was not read.
     */
    public static CheckState mapPipelineStatus(String status) {
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "SUCCESS" -> CheckState.SUCCESS;
            case "FAILED" -> CheckState.FAILURE;
            case "CANCELED", "CANCELING" -> CheckState.CANCELLED;
            case "SKIPPED" -> CheckState.SKIPPED;
            default -> CheckState.PENDING;
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
                // GitLab lists them as approving again: an approval given anew after it was withdrawn or reset, which
                // approves the head GitLab reports now. One that stayed approved keeps the commit it was given for.
                if (existingReview.getState() != PullRequestReview.State.APPROVED || existingReview.isDismissed()) {
                    Instant approvalInstant = resolveApprovalInstant(pr);
                    existingReview.setState(PullRequestReview.State.APPROVED);
                    existingReview.setDismissed(false);
                    existingReview.setSubmittedAt(approvalInstant);
                    existingReview.setUpdatedAt(approvalInstant);
                    String commit = resolveApprovalCommit(pr);
                    if (commit != null) {
                        existingReview.setCommitId(commit);
                    }
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
            stale.setUpdatedAt(ctx != null ? ctx.observedAt() : Instant.now());
            reviewRepository.save(stale);
            log.debug("Dismissed stale review from sync: prId={}, nativeId={}", pr.getId(), stale.getNativeId());

            if (ctx != null) {
                ScmEventPayload.ReviewData.from(stale)
                        .ifPresent(reviewData -> eventPublisher.publishEvent(
                                new ScmDomainEvent.ReviewDismissed(reviewData, EventContext.from(ctx))));
            }
        }
    }

    /** The payload's reviewer list, dated by when Hephaestus received the webhook. */
    private boolean updateRequestedReviewers(
            List<GitLabMergeRequestReviewerDTO> reviewerDtos, PullRequest pr, ProcessingContext context) {
        Map<User, RequestedReviewer.@Nullable ReviewState> reviewers = new HashMap<>();
        for (var dto : reviewerDtos) {
            User user = findOrCreateUser(dto.user(), Objects.requireNonNull(context.providerId()));
            if (user != null) reviewers.put(user, reviewState(dto.state(), pr, user));
        }
        return pr.replaceRequestedReviewers(reviewers, context.observedAt());
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
            @Nullable List<SyncReviewerData> syncReviewers,
            PullRequest pr,
            Long providerId,
            ProcessingContext context) {
        if (syncReviewers == null) return false;

        Map<User, RequestedReviewer.@Nullable ReviewState> reviewers = new HashMap<>();
        for (SyncReviewerData reviewer : syncReviewers) {
            SyncUserData data = reviewer.user();
            User user = findOrCreateUser(
                    new GitLabUserLookup(
                            data.globalId(),
                            data.username(),
                            data.name(),
                            data.avatarUrl(),
                            data.webUrl(),
                            data.publicEmail()),
                    providerId);
            if (user != null) reviewers.put(user, reviewState(reviewer.reviewState(), pr, user));
        }
        return pr.replaceRequestedReviewers(reviewers, context.observedAt());
    }

    /**
     * Where GitLab says {@code user}'s review stands. Where it sent no state, the stored one stands: see
     * {@link GitLabMergeRequestReviewerDTO} for when a hook sends none, and a reviewer who lost access to the merge
     * request has none in GraphQL. A state Hephaestus does not know clears the stored one.
     */
    private static RequestedReviewer.@Nullable ReviewState reviewState(
            @Nullable String sent, PullRequest pr, User user) {
        if (sent != null) {
            return GitLabMergeRequestReviewerDTO.reviewState(sent);
        }
        for (RequestedReviewer listed : pr.getRequestedReviewers()) {
            if (listed.getUser().getId().equals(user.getId())) {
                return listed.getReviewState();
            }
        }
        return null;
    }
}
