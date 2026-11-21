package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest;

import de.tum.cit.aet.hephaestus.integration.scm.domain.common.PostgresStringUtils;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.MergeStateStatus;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.RequestedReviewer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.ReviewDecision;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabUserLookup;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.GitLabMergeRequestProcessor.SyncReviewerData;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.GitLabMergeRequestProcessor.SyncUserData;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.dto.GitLabMergeRequestReviewerDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records what GitLab says about merging a stored merge request: its reviewers and approvals, the review decision,
 * mergeability and merge status that follow them, and the facts of a completed merge. It also maps GitLab's merge
 * request state and detailed merge status to the stored values. An approval is stored as an APPROVED
 * {@link PullRequestReview} under a deterministic id that cannot collide with a GitHub review id.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabMergeRequestReadiness {

    private static final Logger log = LoggerFactory.getLogger(GitLabMergeRequestReadiness.class);

    private final PullRequestRepository pullRequestRepository;
    private final PullRequestReviewRepository reviewRepository;
    private final GitLabUserService gitLabUserService;
    private final ApplicationEventPublisher eventPublisher;

    public GitLabMergeRequestReadiness(
            PullRequestRepository pullRequestRepository,
            PullRequestReviewRepository reviewRepository,
            GitLabUserService gitLabUserService,
            ApplicationEventPublisher eventPublisher) {
        this.pullRequestRepository = pullRequestRepository;
        this.reviewRepository = reviewRepository;
        this.gitLabUserService = gitLabUserService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Records what GitLab said about merging merge request {@code iid}, read after a webhook with the read begun at
     * {@code requestedAt}, where it still describes what is stored. Runs after the read, in its own transaction, with
     * the merge request's row locked; the caller checks first that the delivery may still write.
     *
     * <p>Mutable readiness is recorded only when GitLab names this repository's project and this merge request, both
     * open or both merged, at the head stored now, and not at an older version: a push stored meanwhile is
     * read again after its own event, and the answer never moves the head. The head pipeline is recorded as observed
     * ({@link GitLabHeadPipeline}) under its own clock. The reviewers, approvals, review decision, mergeability and
     * merge status — which follow the approvals — are recorded together, only when no hook or read received after
     * {@code requestedAt} is stored ({@link PullRequest#takesReviewSnapshotAt}); mergeability and approvals stay unknown
     * while GitLab is still settling them ({@link #isSettling}), and the approvals come only from a whole list.
     * An older GraphQL version can date already-standing merged approvals only through
     * {@link #recoverStandingApprovalDates}, without changing mutable readiness or membership. The diff base GitLab
     * paired with that head is recorded under the same ordering as the reviews.
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
        Issue.State expected = pr.getState() == Issue.State.MERGED ? Issue.State.MERGED : Issue.State.OPEN;
        String reason = readinessMismatch(repository, pr, facts, expected);
        if (reason != null) {
            log.debug("Skipped merge request readiness: prId={}, reason={}", pr.getId(), reason);
            return "olderVersion".equals(reason)
                    && !isSettling(facts.detailedMergeStatus())
                    && recoverStandingApprovalDates(
                            pr, facts.headSha(), facts.approvers(), facts.approvalRows(), requestedAt);
        }
        Long providerId = Objects.requireNonNull(repository.getProvider().getId());
        boolean settling = isSettling(facts.detailedMergeStatus());
        facts.headPipeline().observeOn(pr, requestedAt);
        if (pr.takesReviewSnapshotAt(requestedAt)) {
            // Accept diff refs only from a current read; an absent pair cannot replace the stored pair.
            var pair = facts.diffRefs();
            boolean basePaired = false;
            if (pair != null && pair.head().equals(pr.getHeadRefOid())) {
                basePaired = !pair.base().equals(pr.getBaseRefOid());
                pr.setBaseRefOid(pair.base());
            }
            ProcessingContext read = context.withObservedAt(requestedAt);
            updateSyncReviewers(facts.reviewers(), pr, providerId, read);
            recordReviewSnapshot(
                    pr,
                    reviewDecision(facts.detailedMergeStatus(), facts.approved(), facts.reviewers(), facts.approvers()),
                    settling ? null : facts.mergeable(),
                    mapDetailedMergeStatus(facts.detailedMergeStatus()));
            pr = pullRequestRepository.save(pr);
            if (!settling) {
                reconcileApprovals(facts.approvers(), pr, providerId, read, facts.approvalRows());
            }
            if (basePaired) {
                // Not an occasion: it lets the occasions recorded at this head pin their version.
                eventPublisher.publishEvent(new ScmDomainEvent.PullRequestUpdated(
                        ScmEventPayload.PullRequestData.from(pr), Set.of("baseRefOid"), EventContext.from(read)));
            }
        } else {
            log.debug("Kept reviews stored after the readiness read began: prId={}", pr.getId());
            pullRequestRepository.save(pr);
        }
        return true;
    }

    /**
     * A merged approval date can be read at the native material version even when GraphQL's second precision cannot
     * order its mutable readiness against a millisecond webhook. Only already-standing rows are dated; the exact
     * native version and content, current head and whole matching actor sets do not authorize membership changes.
     * Missing native metadata or null description cannot bind this path; ordinary version-accepted reads are separate.
     */
    boolean recoverStandingApprovalDates(
            PullRequest pr,
            @Nullable String head,
            @Nullable List<SyncUserData> approvers,
            GitLabApprovalClient.@Nullable Snapshot rows,
            Instant requestedAt) {
        Repository repository = pr.getRepository();
        if (pr.getState() != Issue.State.MERGED
                || repository == null
                || approvers == null
                || rows == null
                || rows.id() == null
                || !Objects.equals(rows.id(), pr.getNativeId())
                || rows.iid() == null
                || rows.iid() != pr.getNumber()
                || rows.projectId() == null
                || !Objects.equals(rows.projectId(), repository.getNativeId())
                || !"merged".equalsIgnoreCase(rows.state())
                || head == null
                || !head.equals(pr.getHeadRefOid())
                || rows.updatedAt() == null
                || !rows.updatedAt().equals(pr.getUpdatedAt())
                || rows.title() == null
                || !Objects.equals(PostgresStringUtils.sanitize(rows.title()), pr.getTitle())
                || rows.description() == null
                || !Objects.equals(PostgresStringUtils.sanitize(rows.description()), pr.getBody())) {
            return false;
        }
        var dates = rows.datesFor(repository.getNativeId(), pr.getNativeId(), pr.getNumber(), approvers);
        if (dates == null) return false;
        Long providerId = Objects.requireNonNull(repository.getProvider().getId());
        Map<Long, PullRequestReview> standing = new HashMap<>();
        for (var review : pr.getReviews()) {
            if (review.getState() != PullRequestReview.State.APPROVED || review.isDismissed()) continue;
            var author = review.getAuthor();
            if (review.getProvider() == null
                    || !providerId.equals(review.getProvider().getId())
                    || author == null
                    || author.getProvider() == null
                    || !providerId.equals(author.getProvider().getId())
                    || review.getNativeId() != generateApprovalNativeId(pr.getNativeId(), author.getNativeId())
                    || standing.put(author.getNativeId(), review) != null) return false;
        }
        Set<Long> expected = new HashSet<>();
        for (var approver : approvers) {
            // datesFor already validated each global identity.
            expected.add(GitLabSyncConstants.extractNumericId(Objects.requireNonNull(approver.globalId())));
        }
        if (!standing.keySet().equals(expected) || !pr.takesReviewSnapshotAt(requestedAt)) return false;
        for (var entry : standing.entrySet()) {
            var review = entry.getValue();
            Instant date = dates.get(entry.getKey());
            if (!Objects.equals(review.getSubmittedAt(), date)) {
                review.setSubmittedAt(date);
                reviewRepository.save(review);
            }
        }
        pullRequestRepository.save(pr);
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
     * Why GitLab's report of a merge is not about the merge of {@code pr}, or {@code null} when it is: this merge
     * request, merged in GitLab and as stored, at the stored head, with the stored merge commit where both know one.
     * No version is compared. A merged merge request's title or description can still change, but who merged it, when
     * and into which commit are facts of the completed merge that do not; so they hold whatever version GitLab dated
     * its report with. GitLab's GraphQL dates to the second what its webhooks date to the millisecond, so the report
     * of a merge is dated before the merge hook it follows.
     */
    static @Nullable String mergeMismatch(
            PullRequest pr,
            long mergeRequestNativeId,
            @Nullable String state,
            @Nullable String head,
            @Nullable String commit) {
        if (mergeRequestNativeId != pr.getNativeId()) {
            return "otherMergeRequest";
        }
        if (pr.getState() != Issue.State.MERGED || convertState(state) != Issue.State.MERGED) {
            return "notMerged";
        }
        if (head == null || !head.equals(pr.getHeadRefOid())) {
            return "otherHead";
        }
        if (commit != null && pr.getMergeCommitSha() != null && !commit.equals(pr.getMergeCommitSha())) {
            return "otherMergeCommit";
        }
        return null;
    }

    /**
     * Records what is unknown about the merge of {@code pr} from GitLab's report of it, which {@link #mergeMismatch}
     * accepted: a merger, merge commit or merge time already stored stays, and one GitLab does not name stays unknown.
     *
     * @return whether anything was recorded
     */
    boolean fillUnknownMerge(
            PullRequest pr,
            @Nullable SyncUserData merger,
            @Nullable Instant mergedAt,
            @Nullable String commit,
            Long providerId) {
        boolean changed = false;
        if (pr.getMergedBy() == null && merger != null) {
            User user = gitLabUserService.findOrCreateUser(
                    new GitLabUserLookup(
                            merger.globalId(),
                            merger.username(),
                            merger.name(),
                            merger.avatarUrl(),
                            merger.webUrl(),
                            merger.publicEmail(),
                            merger.bot()),
                    providerId);
            if (user != null) {
                pr.setMergedBy(user);
                changed = true;
            }
        }
        if (pr.getMergeCommitSha() == null && commit != null) {
            pr.setMergeCommitSha(commit);
            changed = true;
        }
        if (pr.getMergedAt() == null && mergedAt != null) {
            pr.setMergedAt(mergedAt);
            if (pr.getClosedAt() == null) {
                pr.setClosedAt(mergedAt);
            }
            changed = true;
        }
        if (changed) {
            pullRequestRepository.save(pr);
            log.debug("Recorded merge facts from GitLab: prId={}", pr.getId());
        }
        return changed;
    }

    /**
     * Records who merged merge request {@code iid}, when, and the merge commit, read from GitLab after its merge hook
     * where the hook named none of them. Runs after the read, in its own transaction, with the merge request's row
     * locked; the caller checks first that the delivery may still write.
     *
     * <p>Nothing is recorded unless GitLab's answer is about this repository's project and the stored merge
     * ({@link #mergeMismatch}); its version is not compared. Only what is unknown is filled in, and only with what
     * GitLab named: a merger or commit already stored stays, and one GitLab does not name stays unknown.
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
        GitLabMergeRequestReadinessReader.Merge merge = facts.merge();
        String reason = facts.projectNativeId() != repository.getNativeId()
                ? "otherProject"
                : mergeMismatch(pr, facts.mergeRequestNativeId(), facts.state(), facts.headSha(), merge.commitSha());
        if (reason != null) {
            log.debug("Skipped merge facts: prId={}, reason={}", pr.getId(), reason);
            return false;
        }
        return fillUnknownMerge(
                pr,
                merge.user(),
                merge.mergedAt(),
                merge.commitSha(),
                Objects.requireNonNull(repository.getProvider().getId()));
    }

    /** Keeps readiness unknown after a review event until an authoritative snapshot is read. */
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
    static boolean recordReviewSnapshot(
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
    static boolean forgetReadiness(PullRequest pr) {
        boolean changed =
                pr.getReviewDecision() != null || pr.getMergeable() != null || pr.getMergeStateStatus() != null;
        pr.setReviewDecision(null);
        pr.setMergeable(null);
        pr.setMergeStateStatus(null);
        return changed;
    }

    static Issue.State convertState(@Nullable String state) {
        if (state == null) return Issue.State.OPEN;
        return switch (state.toLowerCase(Locale.ROOT)) {
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
    static @Nullable ReviewDecision reviewDecision(
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
    static String mapDetailedMergeStatus(@Nullable String detailedStatus) {
        if (detailedStatus == null) return null;
        return switch (detailedStatus.toLowerCase(Locale.ROOT)) {
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
        // approvedBy names the approver, but proves neither the approved head nor its date.
        review.setAuthor(approver);
        review.setPullRequest(pr);
        return review;
    }

    void reconcileApprovals(
            @Nullable List<SyncUserData> syncApprovers,
            PullRequest pr,
            Long providerId,
            @Nullable ProcessingContext ctx,
            GitLabApprovalClient.@Nullable Snapshot approvalRows) {
        if (syncApprovers == null) return;
        Repository repository = pr.getRepository();
        var nativeDates = pr.getState() == Issue.State.MERGED && approvalRows != null && repository != null
                ? approvalRows.datesFor(repository.getNativeId(), pr.getNativeId(), pr.getNumber(), syncApprovers)
                : null;

        Set<Long> expectedNativeIds = new HashSet<>();

        // Map existing reviews by nativeId for efficient lookup
        Map<Long, PullRequestReview> existingReviewsByNativeId = pr.getReviews().stream()
                .filter(r -> r.getProvider() != null
                        && Objects.requireNonNull(r.getProvider().getId()).equals(providerId))
                .collect(Collectors.toMap(PullRequestReview::getNativeId, r -> r, (a, b) -> a));

        for (SyncUserData approver : syncApprovers) {
            User user = gitLabUserService.findOrCreateUser(
                    new GitLabUserLookup(
                            approver.globalId(),
                            approver.username(),
                            approver.name(),
                            approver.avatarUrl(),
                            approver.webUrl(),
                            approver.publicEmail(),
                            approver.bot()),
                    providerId);
            if (user == null) continue;

            long approvalNativeId = generateApprovalNativeId(pr.getNativeId(), user.getNativeId());
            expectedNativeIds.add(approvalNativeId);

            PullRequestReview existingReview = existingReviewsByNativeId.get(approvalNativeId);
            if (existingReview != null) {
                boolean changed = false;
                // A renewed standing approval is a new act whose time the approver list does not
                // name: it does not inherit the withdrawn one's.
                if (existingReview.getState() != PullRequestReview.State.APPROVED || existingReview.isDismissed()) {
                    existingReview.setState(PullRequestReview.State.APPROVED);
                    existingReview.setDismissed(false);
                    existingReview.setSubmittedAt(null);
                    existingReview.setUpdatedAt(ctx != null ? ctx.observedAt() : Instant.now());
                    changed = true;
                    log.debug("Updated review to APPROVED from sync: prId={}, reviewerId={}", pr.getId(), user.getId());
                }
                var submittedAt = pr.getState() == Issue.State.MERGED && nativeDates == null
                        ? existingReview.getSubmittedAt()
                        : nativeDates == null ? null : nativeDates.get(user.getNativeId());
                boolean dateChanged = !Objects.equals(existingReview.getSubmittedAt(), submittedAt);
                if (dateChanged) existingReview.setSubmittedAt(submittedAt);
                if (changed || dateChanged) {
                    reviewRepository.save(existingReview);

                    if (changed && ctx != null) {
                        ScmEventPayload.ReviewData.from(existingReview)
                                .ifPresent(reviewData -> eventPublisher.publishEvent(
                                        new ScmDomainEvent.ReviewSubmitted(reviewData, EventContext.from(ctx))));
                    }
                }
            } else {
                // No review exists - create new
                PullRequestReview review = createApprovalReview(approvalNativeId, pr, user);
                if (nativeDates != null) review.setSubmittedAt(nativeDates.get(user.getNativeId()));
                reviewRepository.save(review);
                pr.addReview(review);
                log.debug("Created approval review from sync: prId={}, reviewerId={}", pr.getId(), user.getId());

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

    boolean updateSyncReviewers(
            @Nullable List<SyncReviewerData> syncReviewers,
            PullRequest pr,
            Long providerId,
            ProcessingContext context) {
        if (syncReviewers == null) return false;

        Map<User, RequestedReviewer.@Nullable ReviewState> reviewers = new HashMap<>();
        for (SyncReviewerData reviewer : syncReviewers) {
            SyncUserData data = reviewer.user();
            User user = gitLabUserService.findOrCreateUser(
                    new GitLabUserLookup(
                            data.globalId(),
                            data.username(),
                            data.name(),
                            data.avatarUrl(),
                            data.webUrl(),
                            data.publicEmail(),
                            data.bot()),
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
    static RequestedReviewer.@Nullable ReviewState reviewState(@Nullable String sent, PullRequest pr, User user) {
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
