package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.GitLabMergeRequestReadiness.convertState;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.GitLabMergeRequestReadiness.forgetReadiness;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.GitLabMergeRequestReadiness.isSettling;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.GitLabMergeRequestReadiness.mapDetailedMergeStatus;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.GitLabMergeRequestReadiness.mergeMismatch;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.GitLabMergeRequestReadiness.recordReviewSnapshot;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.GitLabMergeRequestReadiness.reviewDecision;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.GitLabMergeRequestReadiness.reviewState;

import de.tum.cit.aet.hephaestus.integration.core.ProviderStateVersion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.Milestone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.MilestoneRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.RequestedReviewer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.ReviewDecision;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmEventIdentity;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmEventPayload;
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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores GitLab merge requests from webhooks and GraphQL sync as {@link PullRequest}s.
 * {@link GitLabMergeRequestReadiness} records what GitLab says about merging them, the approvals included.
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabMergeRequestProcessor extends BaseGitLabProcessor {

    private static final Logger log = LoggerFactory.getLogger(GitLabMergeRequestProcessor.class);

    private final PullRequestRepository pullRequestRepository;
    private final GitLabMergeRequestReadiness readiness;
    private final MilestoneRepository milestoneRepository;
    private final IssueRepository issueRepository;
    private final ApplicationEventPublisher eventPublisher;

    public GitLabMergeRequestProcessor(
            GitLabUserService gitLabUserService,
            PullRequestRepository pullRequestRepository,
            GitLabMergeRequestReadiness readiness,
            MilestoneRepository milestoneRepository,
            IssueRepository issueRepository,
            UserRepository userRepository,
            LabelRepository labelRepository,
            GitLabProperties gitLabProperties,
            ApplicationEventPublisher eventPublisher) {
        super(gitLabUserService, userRepository, labelRepository, gitLabProperties);
        this.pullRequestRepository = pullRequestRepository;
        this.readiness = readiness;
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
     *
     * <p>The accepted local update retains the initiating delivery's source and actor. Its event time follows
     * {@link EventContext#from}, and its stored version still fences a late read.
     */
    @Transactional
    public void replaceClosingIssues(
            Repository repository,
            int iid,
            List<Integer> closingIssueNumbers,
            StoredVersion readFor,
            ProcessingContext context) {
        pullRequestRepository
                .findForUpdateByRepositoryIdAndNumber(repository.getId(), iid)
                .filter(pr -> Objects.equals(pr.getHeadRefOid(), readFor.head())
                        && Objects.equals(pr.getUpdatedAt(), readFor.updatedAt()))
                .ifPresent(pr -> {
                    if (pr.replaceClosingIssues(resolveLocalIssues(repository, closingIssueNumbers))) {
                        PullRequest stored = pullRequestRepository.save(pr);
                        eventPublisher.publishEvent(new ScmDomainEvent.PullRequestUpdated(
                                ScmEventPayload.PullRequestData.from(stored),
                                Set.of("relationships", ScmItemEvents.CLOSING_ISSUES_FIELD),
                                EventContext.from(context)));
                    }
                });
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
            @Nullable String publicEmail,
            @Nullable Boolean bot) {}

    /** A reviewer in sync data, with GitLab's {@code MergeRequestReviewState} for them when it gave one. */
    public record SyncReviewerData(
            SyncUserData user, @Nullable String reviewState) {}

    /**
     * @param approved GitLab's {@code approved}; null where the read did not capture it
     * @param baseSha the diff base GitLab paired with {@code diffHeadSha}; null where the read did not capture that pair
     * @param closingIssueNumbers the iids of GitLab's closing candidates for the MR, from the REST closes-issues route;
     *     null when this sync did not read them, which leaves the stored set alone
     */
    public record SyncMergeRequestData(
            @Nullable String globalId,
            @Nullable String iid,
            @Nullable String title,
            @Nullable String description,
            @Nullable String state,
            boolean draft,
            @Nullable Boolean mergeable,
            @Nullable String detailedMergeStatus,
            @Nullable Boolean approved,
            @Nullable String webUrl,
            @Nullable String createdAt,
            @Nullable String updatedAt,
            @Nullable String closedAt,
            @Nullable String mergedAt,
            @Nullable Integer commitCount,
            @Nullable Integer additions,
            @Nullable Integer deletions,
            @Nullable Integer fileCount,
            @Nullable String sourceBranch,
            @Nullable String targetBranch,
            @Nullable String diffHeadSha,
            @Nullable String baseSha,
            @Nullable String mergeCommitSha,
            @Nullable Boolean discussionLocked,
            @Nullable Integer commentsCount,
            @Nullable String authorGlobalId,
            @Nullable String authorUsername,
            @Nullable String authorName,
            @Nullable String authorAvatarUrl,
            @Nullable String authorWebUrl,
            @Nullable String authorPublicEmail,
            @Nullable Boolean authorBot,
            @Nullable String mergeUserGlobalId,
            @Nullable String mergeUserUsername,
            @Nullable String mergeUserName,
            @Nullable String mergeUserAvatarUrl,
            @Nullable String mergeUserWebUrl,
            @Nullable String mergeUserPublicEmail,
            @Nullable Boolean mergeUserBot,
            @Nullable List<SyncLabelData> syncLabels,
            @Nullable List<SyncUserData> syncAssignees,
            @Nullable List<SyncReviewerData> syncReviewers,
            @Nullable List<SyncUserData> syncApprovers,
            @Nullable List<SyncUserData> syncParticipants,
            @Nullable Integer milestoneIid,
            GitLabHeadPipeline headPipeline,
            @Nullable List<Integer> closingIssueNumbers,
            GitLabApprovalClient.@Nullable Snapshot approvalRows) {}

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
        String previousTitle = null;
        String previousBody = null;
        if (attrs.iid() != null) {
            Optional<PullRequest> existingOpt = pullRequestRepository.findForUpdateByRepositoryIdAndNumber(
                    Objects.requireNonNull(context.repository()).getId(), attrs.iid());
            if (existingOpt.isPresent()) {
                isNew = false;
                PullRequest existing = existingOpt.get();
                wasDraft = existing.isDraft();
                previousHead = existing.getHeadRefOid();
                previousTitle = existing.getTitle();
                previousBody = existing.getBody();
                Instant eventUpdatedAt = parseGitLabTimestamp(attrs.updatedAt());
                boolean continuesHead = "update".equals(attrs.action())
                        && attrs.oldrev() != null
                        && attrs.oldrev().equals(existing.getHeadRefOid())
                        && attrs.lastCommit() != null
                        && attrs.lastCommit().id() != null
                        && eventUpdatedAt != null
                        && eventUpdatedAt.equals(existing.getUpdatedAt());
                if (!continuesHead && ProviderStateVersion.cannotReplace(eventUpdatedAt, existing.getUpdatedAt())) {
                    // The reviewer list still applies unless the payload is older, as
                    // sync-lifecycle.mdx § Reviewer lists are dated snapshots explains.
                    if (!ProviderStateVersion.isOlder(eventUpdatedAt, existing.getUpdatedAt())
                            && updateRequestedReviewers(event.currentReviewers(), existing, context)) {
                        existing = pullRequestRepository.save(existing);
                    }
                    log.debug(
                            "Skipped stale MR webhook: nativeId={}, existingUpdatedAt={}, eventUpdatedAt={}",
                            attrs.id(),
                            existing.getUpdatedAt(),
                            eventUpdatedAt);
                    // A sync can store a push before its hook arrives; the push is still this hook's event.
                    String head =
                            attrs.lastCommit() != null ? attrs.lastCommit().id() : null;
                    if (attrs.oldrev() != null && head != null && head.equals(existing.getHeadRefOid())) {
                        eventPublisher.publishEvent(new ScmDomainEvent.PullRequestSynchronized(
                                ScmEventPayload.PullRequestData.from(existing),
                                EventContext.from(context)
                                        .withSourceKey(ScmEventIdentity.push(existing.getId(), attrs.oldrev(), head))));
                    }
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
                isNew,
                previousTitle,
                previousBody);

        if (pr == null) return null;

        boolean changed = updateLabels(event.labels(), pr.getLabels(), Objects.requireNonNull(context.repository()));
        changed |= updateAssignees(
                event.currentAssignees(), pr.getAssignees(), Objects.requireNonNull(context.providerId()));
        changed |= updateRequestedReviewers(event.currentReviewers(), pr, context);
        // GitLab can omit oldrev, so a changed stored head also proves a push.
        // oldrev still identifies a push when sync has already stored the new head.
        boolean pushed = !isNew
                && headRefOid != null
                && (attrs.oldrev() != null || (previousHead != null && !previousHead.equals(headRefOid)));
        if (pushed) {
            changed |= forgetReadiness(pr);
        }
        if (changed) {
            pr = pullRequestRepository.save(pr);
        }

        // Detect draft transitions and pushes. A new merge request is Created only, as a GitHub
        // pull request opened ready is: raising Ready as well would review the same head twice.
        var prData = ScmEventPayload.PullRequestData.from(pr);
        var eventCtx = EventContext.from(context);
        Instant hookAt = parseGitLabTimestamp(attrs.updatedAt());
        if (pushed) {
            eventPublisher.publishEvent(new ScmDomainEvent.PullRequestSynchronized(
                    prData,
                    eventCtx.withSourceKey(ScmEventIdentity.push(
                            pr.getId(),
                            attrs.oldrev() != null ? attrs.oldrev() : previousHead,
                            Objects.requireNonNull(headRefOid)))));
            log.debug("Merge request received new commits: prId={}, iid={}", pr.getId(), attrs.iid());
        }
        if (!isNew && wasDraft != null) {
            if (wasDraft && !attrs.draft()) {
                eventPublisher.publishEvent(new ScmDomainEvent.PullRequestReady(
                        prData, eventCtx.withSourceKey(ScmEventIdentity.transition(pr.getId(), "ready", hookAt))));
                log.info("Merge request marked ready: prId={}, iid={}", pr.getId(), attrs.iid());
            } else if (!wasDraft && attrs.draft()) {
                eventPublisher.publishEvent(new ScmDomainEvent.PullRequestDrafted(
                        prData, eventCtx.withSourceKey(ScmEventIdentity.transition(pr.getId(), "draft", hookAt))));
                log.info("Merge request converted to draft: prId={}, iid={}", pr.getId(), attrs.iid());
            }
        }

        return pr;
    }

    @Transactional
    @Nullable
    public PullRequest processClosed(GitLabMergeRequestEventDTO event, ProcessingContext context) {
        PullRequest pr = processInternal(event, context);
        // A first close that arrives after sync stored it still records its occasion; a redelivery records none.
        if (pr != null && pr.getState() == Issue.State.CLOSED) {
            eventPublisher.publishEvent(new ScmDomainEvent.PullRequestClosed(
                    ScmEventPayload.PullRequestData.from(pr), false, EventContext.from(context)));
            log.debug("Closed merge request: prId={}", pr.getId());
        }
        return pr;
    }

    @Transactional
    @Nullable
    public PullRequest processReopened(GitLabMergeRequestEventDTO event, ProcessingContext context) {
        PullRequest pr = processInternal(event, context);
        if (pr != null && pr.getState() == Issue.State.OPEN) {
            var attrs = event.objectAttributes();
            Instant reopenedAt = attrs == null ? null : parseGitLabTimestamp(attrs.updatedAt());
            eventPublisher.publishEvent(new ScmDomainEvent.PullRequestReopened(
                    ScmEventPayload.PullRequestData.from(pr),
                    EventContext.from(context)
                            .withSourceKey(ScmEventIdentity.transition(pr.getId(), "reopened", reopenedAt))));
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
        // Merge admission waits for the follow-up read to identify the merger.
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
     * Approval hooks refresh readiness; only the native approval snapshot owns current membership and dates.
     * GitLab's asynchronous hook worker reloads the current merge request, so its head is not the approved commit
     * (<a href="https://gitlab.com/gitlab-org/gitlab/-/blob/v19.4.1-ee/app/workers/merge_requests/execute_approval_hooks_worker.rb">provider source</a>).
     */
    @Transactional
    @Nullable
    public PullRequest processApproved(GitLabMergeRequestEventDTO event, ProcessingContext context) {
        return refreshApprovals(event, context, "approval");
    }

    /** Forgets the stored review decision for a later read to settle; an occasion of {@code kind} records no review. */
    private @Nullable PullRequest refreshApprovals(
            GitLabMergeRequestEventDTO event, ProcessingContext context, String kind) {
        PullRequest pr = processInternal(event, context);
        if (pr == null) return pr;
        if (!actsOnStoredHead(event, pr)) {
            log.debug("Skipped {} of another head than the stored one: prId={}", kind, pr.getId());
            return pr;
        }
        if (!pr.takesReviewSnapshotAt(context.observedAt())) {
            log.debug("Skipped {} older than the stored reviews: prId={}", kind, pr.getId());
            return pr;
        }
        readiness.forgetReviewReadiness(pr);
        log.debug("Approvals to read again after {}: prId={}", kind, pr.getId());
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

    /**
     * Withdrawal and reset hooks refresh readiness through the same native snapshot as approval hooks.
     * A reset's actor is the pusher, and delayed withdrawal may follow a new approval; neither identifies
     * whose approvals still stand.
     */
    @Transactional
    @Nullable
    public PullRequest processUnapproved(GitLabMergeRequestEventDTO event, ProcessingContext context) {
        var attrs = event.objectAttributes();
        return refreshApprovals(
                event,
                context,
                attrs != null && attrs.isSystemInitiated()
                        ? "approval reset " + sanitizeForLog(attrs.systemAction())
                        : "unapproval");
    }

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
        // A page that describes an older version than the one stored changes nothing: a later
        // webhook or read stored it, and the page's head, checks and approvals would take it back.
        Instant fetchedUpdatedAt = parseGitLabTimestamp(data.updatedAt());
        if (existingOpt.isPresent() && fetchedUpdatedAt == null) {
            // A page that did not capture the version cannot be ordered against the stored merge
            // request.
            log.debug("Skipped merge request read without its version: iid={}", data.iid());
            return existingOpt.get();
        }
        if (existingOpt.isPresent()
                && existingOpt.get().getUpdatedAt() != null
                && fetchedUpdatedAt != null
                && fetchedUpdatedAt.isBefore(existingOpt.get().getUpdatedAt())) {
            PullRequest stored = existingOpt.get();
            // Nothing that can still change, such as the title or description, is taken from an
            // older page; only the facts of the completed merge it reports, when it is the stored
            // merge, fill what is unknown (see GitLabMergeRequestReadiness#mergeMismatch).
            if (mergeMismatch(stored, nativeId, data.state(), data.diffHeadSha(), data.mergeCommitSha()) == null) {
                readiness.fillUnknownMerge(
                        stored,
                        data.mergeUserGlobalId() == null
                                ? null
                                : new SyncUserData(
                                        data.mergeUserGlobalId(),
                                        data.mergeUserUsername(),
                                        data.mergeUserName(),
                                        data.mergeUserAvatarUrl(),
                                        data.mergeUserWebUrl(),
                                        data.mergeUserPublicEmail(),
                                        data.mergeUserBot()),
                        parseGitLabTimestamp(data.mergedAt()),
                        data.mergeCommitSha(),
                        providerId);
            }
            if (mergeMismatch(stored, nativeId, data.state(), data.diffHeadSha(), data.mergeCommitSha()) == null
                    && !isSettling(data.detailedMergeStatus())) {
                readiness.recoverStandingApprovalDates(
                        stored, data.diffHeadSha(), data.syncApprovers(), data.approvalRows(), context.observedAt());
            }
            log.debug(
                    "Skipped merge request older than the stored one: iid={}, storedUpdatedAt={}, readUpdatedAt={}",
                    data.iid(),
                    stored.getUpdatedAt(),
                    fetchedUpdatedAt);
            return stored;
        }
        Instant lastWriteAt = existingOpt.map(PullRequest::getLastSyncAt).orElse(null);
        Instant reviewsObservedAt =
                existingOpt.map(PullRequest::getReviewersObservedAt).orElse(null);
        if (reviewsObservedAt != null && (lastWriteAt == null || reviewsObservedAt.isAfter(lastWriteAt))) {
            lastWriteAt = reviewsObservedAt;
        }
        boolean sameVersion = existingOpt.isPresent()
                && ProviderStateVersion.refuses(
                        fetchedUpdatedAt, existingOpt.get().getUpdatedAt(), true, context.observedAt(), lastWriteAt);
        if (sameVersion
                && (!Objects.equals(
                                data.diffHeadSha(), existingOpt.orElseThrow().getHeadRefOid())
                        || convertState(data.state())
                                != existingOpt.orElseThrow().getState()
                        || data.draft() != existingOpt.orElseThrow().isDraft())) {
            return existingOpt.orElseThrow();
        }
        // The upsert replaces the prior draft state; retain it for transition detection.
        Boolean wasDraft = existingOpt.map(PullRequest::isDraft).orElse(null);
        Issue.State previousState = existingOpt.map(PullRequest::getState).orElse(null);
        String previousTitle = existingOpt.map(PullRequest::getTitle).orElse(null);
        String previousBody = existingOpt.map(PullRequest::getBody).orElse(null);
        String previousHead = existingOpt.map(PullRequest::getHeadRefOid).orElse(null);

        User author = findOrCreateUser(
                new GitLabUserLookup(
                        data.authorGlobalId(),
                        data.authorUsername(),
                        data.authorName(),
                        data.authorAvatarUrl(),
                        data.authorWebUrl(),
                        data.authorPublicEmail(),
                        data.authorBot()),
                providerId);

        User mergeUser = findOrCreateUser(
                new GitLabUserLookup(
                        data.mergeUserGlobalId(),
                        data.mergeUserUsername(),
                        data.mergeUserName(),
                        data.mergeUserAvatarUrl(),
                        data.mergeUserWebUrl(),
                        data.mergeUserPublicEmail(),
                        data.mergeUserBot()),
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
                                participant.publicEmail(),
                                participant.bot()),
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
        int written = 0;
        if (!sameVersion) {
            written = pullRequestRepository.upsertCore(
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
                    null,
                    null,
                    null, // mergeable: likewise
                    data.sourceBranch(),
                    data.targetBranch(),
                    data.diffHeadSha(),
                    null, // baseRefOid: recorded with the review snapshot below, as for mergeable
                    mergeUser != null ? mergeUser.getId() : null,
                    data.mergeCommitSha());
        }

        PullRequest pr = sameVersion
                ? existingOpt.orElseThrow()
                : pullRequestRepository
                        .findByRepositoryIdAndNumber(repository.getId(), mrNumber)
                        .orElseThrow(() -> new IllegalStateException(
                                "PullRequest not found after upsert: nativeId=" + nativeId + ", iid=" + data.iid()));

        if (!sameVersion && written == 0) return pr;
        pr.setProvider(repository.getProvider());

        // Head-scoped status is stored only when this read captured the head it describes.
        boolean headCaptured = fetchedUpdatedAt != null
                && data.diffHeadSha() != null
                && !data.diffHeadSha().isBlank();
        boolean headMoved = headCaptured && previousHead != null && !previousHead.equals(pr.getHeadRefOid());
        boolean reviewsCurrent = headCaptured && pr.takesReviewSnapshotAt(context.observedAt());
        boolean changed = false;
        if (reviewsCurrent) {
            changed |= recordReviewSnapshot(pr, reviewDecision, settling ? null : data.mergeable(), mergeStateStatus);
            // Only the accepted read may store its base/head pair; stale pages cannot replace it.
            // The upsert clears the base when its head or target changes.
            String pairedBase = data.baseSha();
            String storedHead = pr.getHeadRefOid();
            if (pairedBase != null
                    && storedHead != null
                    && storedHead.equals(data.diffHeadSha())
                    && !pairedBase.equals(pr.getBaseRefOid())) {
                pr.setBaseRefOid(pairedBase);
                changed = true;
            }
        } else if (headMoved) {
            changed |= forgetReadiness(pr);
        }
        if (!sameVersion) {
            changed |= updateSyncLabels(data.syncLabels(), pr.getLabels(), repository);
            changed |= updateSyncAssignees(data.syncAssignees(), pr.getAssignees(), providerId);
        }
        changed |= readiness.updateSyncReviewers(data.syncReviewers(), pr, providerId, context);
        if (headCaptured || data.headPipeline().kind() == GitLabHeadPipeline.Kind.REPORTED) {
            changed |= data.headPipeline().observeOn(pr, context.observedAt());
        }
        boolean closingIssuesChanged = data.closingIssueNumbers() != null
                && pr.replaceClosingIssues(resolveLocalIssues(repository, data.closingIssueNumbers()));
        changed |= closingIssuesChanged;
        if (changed) {
            pr = pullRequestRepository.save(pr);
        }

        // Approvers GitLab is still recomputing after a push are not a settled list.
        if (reviewsCurrent && !settling) {
            readiness.reconcileApprovals(data.syncApprovers(), pr, providerId, context, data.approvalRows());
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
            // Merged work has no later webhook for head or closing-issue changes; sync records them.
            Set<String> changedFields = authoredChanges(previousTitle, previousBody, pr);
            if (headMoved) changedFields.add("headRefOid");
            if (closingIssuesChanged) {
                changedFields.add("relationships");
                changedFields.add(ScmItemEvents.CLOSING_ISSUES_FIELD);
            }
            eventPublisher.publishEvent(new ScmDomainEvent.PullRequestUpdated(prData, changedFields, eventCtx));
            // A push this sync stored is the push its hook reports, under the same identity: the hook that arrives
            // later records nothing new, and a lost hook loses no review.
            if (headMoved) {
                eventPublisher.publishEvent(new ScmDomainEvent.PullRequestSynchronized(
                        prData,
                        eventCtx.withSourceKey(ScmEventIdentity.push(
                                pr.getId(), previousHead, Objects.requireNonNull(pr.getHeadRefOid())))));
            }
            // A merge the webhook missed is recorded as the sync found it, so a later delivery of
            // it can still claim it; a sync records it without starting a review.
            if (isMerged && previousState != Issue.State.MERGED) {
                if (previousState != Issue.State.CLOSED) {
                    eventPublisher.publishEvent(new ScmDomainEvent.PullRequestClosed(prData, true, eventCtx));
                }
                eventPublisher.publishEvent(new ScmDomainEvent.PullRequestMerged(prData, eventCtx));
            }
            log.debug("Updated merge request from sync: nativeId={}, iid={}", nativeId, data.iid());
        }

        // Reconciliation must recover draft transitions from missed webhooks.
        if (!sameVersion && wasDraft != null) {
            if (wasDraft && !data.draft()) {
                eventPublisher.publishEvent(new ScmDomainEvent.PullRequestReady(
                        prData,
                        eventCtx.withSourceKey(ScmEventIdentity.transition(pr.getId(), "ready", pr.getUpdatedAt()))));
                log.info("Merge request found ready during sync: prId={}, iid={}", pr.getId(), data.iid());
            } else if (!wasDraft && data.draft()) {
                eventPublisher.publishEvent(new ScmDomainEvent.PullRequestDrafted(
                        prData,
                        eventCtx.withSourceKey(ScmEventIdentity.transition(pr.getId(), "draft", pr.getUpdatedAt()))));
                log.info("Merge request found converted to draft during sync: prId={}, iid={}", pr.getId(), data.iid());
            }
        }

        return pr;
    }

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
            boolean isNew,
            @Nullable String previousTitle,
            @Nullable String previousBody) {
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
        int written = pullRequestRepository.upsertCore(
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
                null, // Webhooks omit comment counts; null preserves the stored value through COALESCE.
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
                null, // Webhooks omit change counts; null preserves stored values.
                null,
                null,
                null, // reviewDecision, mergeStateStatus, mergeable — not in webhook
                sourceBranch,
                targetBranch,
                headRefOid,
                null, // A changed head without a provider base invalidates the stored diff pair.
                mergedBy != null ? mergedBy.getId() : null,
                mergeCommitSha // An absent merge commit must preserve the stored value.
                );

        PullRequest pr = pullRequestRepository
                .findByRepositoryIdAndNumber(repository.getId(), mrNumber)
                .orElseThrow(() -> new IllegalStateException(
                        "PullRequest not found after upsert: nativeId=" + nativeId + ", number=" + mrNumber));

        if (written == 0) return null;
        pr.setProvider(repository.getProvider());

        if (isNew) {
            eventPublisher.publishEvent(new ScmDomainEvent.PullRequestCreated(
                    ScmEventPayload.PullRequestData.from(pr), EventContext.from(context)));
            log.debug("Created merge request: nativeId={}, iid={}", nativeId, mrNumber);
        } else {
            eventPublisher.publishEvent(new ScmDomainEvent.PullRequestUpdated(
                    ScmEventPayload.PullRequestData.from(pr),
                    authoredChanges(previousTitle, previousBody, pr),
                    EventContext.from(context)));
            log.debug("Updated merge request: nativeId={}, iid={}", nativeId, mrNumber);
        }

        return pr;
    }

    /**
     * The authored fields this write changed, read off the stored row before and after it. GitLab's own
     * {@code changes} object is not used: it may be empty, and a sync carries none at all.
     */
    private static Set<String> authoredChanges(
            @Nullable String previousTitle, @Nullable String previousBody, PullRequest pr) {
        Set<String> changed = new HashSet<>();
        if (!Objects.equals(previousTitle, pr.getTitle())) {
            changed.add("title");
        }
        if (!Objects.equals(previousBody, pr.getBody())) {
            changed.add("body");
        }
        return changed;
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
                            data.publicEmail(),
                            data.bot()),
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
}
