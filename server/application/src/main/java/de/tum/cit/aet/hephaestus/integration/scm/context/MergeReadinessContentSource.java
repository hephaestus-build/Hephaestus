package de.tum.cit.aet.hephaestus.integration.scm.context;

import de.tum.cit.aet.hephaestus.evidence.capture.ContentSource;
import de.tum.cit.aet.hephaestus.evidence.capture.MentorContextPaths;
import de.tum.cit.aet.hephaestus.evidence.capture.MentorContextRequest;
import de.tum.cit.aet.hephaestus.integration.core.framework.IntegrationManifestRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.DeliveredPullRequestCommentLookup;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.Commit;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitFileChange;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.CheckState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.RequestedReviewer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.ReviewDecision;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Materialises {@code inputs/context/merge_readiness.json}: for the developer's open authored pull requests, what
 * the provider says about merging and what participants wrote, the work's author included. Each comment carries its
 * author's relation to the work, from stored identities; a comment is never a review, and only a recorded review
 * approves or requests changes. An approval with an empty body is not the whole review — a condition often sits in a
 * general note or an inline thread beside it, and resolving that thread does not show the condition was met. Its description and the issues the provider records it closing, with their bodies,
 * carry conditions too: a closing link says the provider will close the issue on merge, not that the issue's
 * conditions are met, and a stored list can miss a link whose read failed. One that is only listed in
 * {@code notLoaded} is read on demand from the {@code resource} its entry carries, as is the developer's closed or
 * merged work from its entry in {@code recent_authored_work.json}: the list is for work still to merge, the detail for
 * any work they authored, with its stored state saying which.
 *
 * <p>Each read is one snapshot: the pages of a scan are ordered by thread state a sync may change between them, so
 * under read-committed a row could slip past the offset unread.
 *
 * <p>It is the stored copy, not a live read: GitHub and GitLab sync incrementally and webhooks update single
 * fields, so no stored timestamp proves merge state, checks or approvals are current. A {@code COMPLETE} list is
 * everything stored, not everything on the provider: a failed note or discussion sync is logged, not recorded.
 * Not cached, so every stored note, approval and pipeline change reaches the next turn without an eviction per
 * event.
 */
@Component
@RequiredArgsConstructor
public class MergeReadinessContentSource implements ContentSource {

    public static final String OUTPUT_KEY = MentorContextPaths.MERGE_READINESS;

    /** One item path, which a {@code read} of the turn's context folder stages on demand. */
    private static final Pattern ITEM_KEY = Pattern.compile("inputs/context/merge_readiness/([1-9][0-9]{0,18})\\.json");

    private static final int MAX_DETAILED = 5;
    private static final int MAX_LISTED = 20;
    private static final int MAX_NOTES = 10;
    private static final int MAX_REVIEWS_READ = 30;
    private static final int MAX_REVIEWERS = 5;
    private static final int MAX_BODY_CHARS = 1_000;
    private static final int MAX_THREADS = 5;
    private static final int MAX_THREAD_COMMENTS = 3;
    private static final int MAX_CLOSING_ISSUES = 5;
    private static final int MAX_COMMITS = 20;
    private static final int MAX_FILES = 50;
    private static final int MAX_FILE_CHANGES_READ = 500;
    private static final int MAX_HUNK_CHARS = 1_500;

    /** What a closing issue is to the pull request: the provider records it as closed by a merge, nothing more. */
    private static final String CLOSING_RELATION = "PROVIDER_RECORDED_CLOSING_CANDIDATE";

    /** Stored comments read per channel per pull request, however many are Hephaestus's own. */
    private static final int SCAN_BUDGET = 200;

    private static final int SCAN_PAGE = 50;

    private final WorkspaceActorSelector actorSelector;
    private final MentorContextQueryRepository queryRepository;
    private final ScmMentorWorkQuery work;
    private final PullRequestReviewRepository reviewRepository;
    private final IssueCommentRepository commentRepository;
    private final ObjectMapper objectMapper;
    private final IntegrationManifestRegistry manifests;
    private final DeliveredPullRequestCommentLookup deliveredComments;

    @Override
    public boolean supports(MentorContextRequest request) {
        return true;
    }

    @Override
    public boolean required() {
        return false;
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public void contribute(MentorContextRequest request, Map<String, byte[]> files) {
        MentorContextRequest req = request;
        try {
            files.put(OUTPUT_KEY, objectMapper.writeValueAsBytes(buildPayload(req.workspaceId(), req.developerId())));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize merge readiness context", e);
        }
    }

    /** The on-demand key of one pull request's detail, published as the {@code resource} of its entries. */
    public static String resourceOf(long pullRequestId) {
        return MentorContextPaths.mergeReadiness(pullRequestId);
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public Optional<JsonNode> read(long workspaceId, long accountId, String path) {
        return pullRequestIdOf(path).map(id -> inspectPayload(workspaceId, accountId, id));
    }

    public static Optional<Long> pullRequestIdOf(String key) {
        Matcher matcher = ITEM_KEY.matcher(key);
        if (!matcher.matches()) return Optional.empty();
        try {
            return Optional.of(Long.parseLong(matcher.group(1)));
        } catch (NumberFormatException invalid) {
            return Optional.empty();
        }
    }

    /**
     * One pull request the developer authored, open, closed or merged, in the shape of the list and its scope apart
     * from state; {@code NOT_FOUND} otherwise.
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public ObjectNode inspect(long workspaceId, long developerId, long pullRequestId) {
        return inspectPayload(workspaceId, developerId, pullRequestId);
    }

    private ObjectNode inspectPayload(long workspaceId, long developerId, long pullRequestId) {
        return build(workspaceId, (root, providerId) -> {
            // A list: two monitor rows with one path would make a single-result query throw.
            Optional<PullRequest> pr = queryRepository
                    .findAuthoredPullRequestOnInstance(
                            workspaceId, work.actorIds(workspaceId, developerId), providerId, pullRequestId)
                    .stream()
                    .findFirst();
            if (pr.isEmpty()) {
                root.put("status", "NOT_FOUND");
                root.put(
                        "reason",
                        "No pull request by this developer has that pullRequestId in this workspace. Copy the resource "
                                + "of an entry in recent_authored_work.json or merge_readiness.json.");
                return;
            }
            root.putArray("pullRequests").add(describe(workspaceId, developerId, providerId, pr.get(), true));
            root.putArray("notLoaded");
        });
    }

    ObjectNode buildPayload(Long workspaceId, Long developerId) {
        return build(workspaceId, (root, providerId) -> {
            List<PullRequest> open = queryRepository.findOpenAuthoredPullRequestsOnInstance(
                    workspaceId,
                    work.actorIds(workspaceId, developerId),
                    providerId,
                    PageRequest.of(0, MAX_DETAILED + MAX_LISTED + 1));
            ArrayNode detailed = root.putArray("pullRequests");
            ArrayNode notLoaded = root.putArray("notLoaded");
            for (int i = 0; i < Math.min(open.size(), MAX_DETAILED + MAX_LISTED); i++) {
                if (i < MAX_DETAILED) {
                    detailed.add(describe(workspaceId, developerId, providerId, open.get(i), false));
                } else {
                    notLoaded.add(indexEntry(open.get(i)));
                }
            }
            root.put("notLoadedTruncated", open.size() > MAX_DETAILED + MAX_LISTED);
        });
    }

    private ObjectNode build(Long workspaceId, BiConsumer<ObjectNode, Long> fill) {
        ObjectNode root = objectMapper.createObjectNode();
        // When this was read from the stored copy; nothing stored proves the provider has not changed since.
        root.put("readAt", Instant.now().toString());
        root.put("providerFreshness", "UNKNOWN");
        Optional<Long> providerId = actorSelector.connectedProviderId(workspaceId);
        if (providerId.isEmpty()) {
            root.put("status", "UNAVAILABLE");
            root.put("reason", "The workspace has no active GitHub or GitLab connection with synced work.");
            return root;
        }
        fill.accept(root, providerId.get());
        return fitted(root);
    }

    /**
     * Moves detail into {@code notLoaded}, newest last, until the JSON fits; the listing itself goes last. What
     * remains is always whole JSON that says what it left out.
     */
    private ObjectNode fitted(ObjectNode root) {
        if (!(root.get("pullRequests") instanceof ArrayNode detailed)
                || !(root.get("notLoaded") instanceof ArrayNode notLoaded)) {
            return root;
        }
        while (objectMapper.writeValueAsString(root).length() > MentorContextPaths.MAX_CHARS) {
            if (!detailed.isEmpty()) {
                JsonNode dropped = detailed.remove(detailed.size() - 1);
                notLoaded.insert(0, indexEntry(dropped));
            } else if (!notLoaded.isEmpty()) {
                notLoaded.removeAll();
                root.put("notLoadedTruncated", true);
            } else {
                break;
            }
            root.put("sizeLimited", true);
        }
        return root;
    }

    private ObjectNode indexEntry(PullRequest pr) {
        return objectMapper
                .createObjectNode()
                .put("pullRequestId", pr.getId())
                .put("resource", resourceOf(pr.getId()))
                .put("number", pr.getNumber())
                .put("title", pr.getTitle())
                .put("url", pr.getHtmlUrl());
    }

    private ObjectNode indexEntry(JsonNode detail) {
        return objectMapper
                .createObjectNode()
                .put("pullRequestId", detail.path("pullRequestId").asLong())
                .put("resource", resourceOf(detail.path("pullRequestId").asLong()))
                .put("number", detail.path("number").asInt())
                .put("title", detail.path("title").asString())
                .put("url", detail.path("url").asString(null));
    }

    /**
     * @param change whether to add what changed — reviewers asked, labels, refs, commits and files — as one detail
     *     does
     */
    private ObjectNode describe(long workspaceId, long developerId, long providerId, PullRequest pr, boolean change) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("pullRequestId", pr.getId());
        node.put("resource", resourceOf(pr.getId()));
        node.put("number", pr.getNumber());
        node.put("title", pr.getTitle());
        node.put("url", pr.getHtmlUrl());
        node.put("state", pr.getState().name());
        node.put("isDraft", pr.isDraft());
        node.put("isMerged", pr.isMerged());
        if (pr.getMergedAt() != null) {
            node.put("mergedAt", pr.getMergedAt().toString());
        }
        User merger = pr.getMergedBy();
        if (merger != null) {
            node.put("mergedBy", merger.getLogin());
            if (merger.getType() == User.Type.BOT) {
                node.put("mergedByBot", true);
            }
        }
        if (pr.getBody() != null && !pr.getBody().isBlank()) {
            putText(node, "description", pr.getBody());
        }
        // Webhooks advance it without re-reading merge state, so the fields below can be older.
        Instant written = pr.getLastSyncAt();
        node.put("recordUpdatedAt", written == null ? "UNKNOWN" : written.toString());
        Boolean mergeable = pr.getMergeable();
        node.put("mergeable", mergeable == null ? "UNKNOWN" : mergeable ? "YES" : "NO");
        node.put("mergeStateStatus", nameOrUnknown(pr.getMergeStateStatus()));
        node.put("reviewDecision", approval(pr.getReviewDecision()));
        node.put("headSha", pr.getHeadRefOid());
        node.put("checks", nameOrUnknown(pr.getHeadCheckState()));
        node.put("checksFor", checksFor(pr));
        node.put("checksSha", pr.getHeadCheckSha());
        node.put("checksObserved", checksObserved(pr));
        Instant checkedAt = pr.getHeadCheckObservedAt();
        node.put("checksObservedAt", checkedAt == null ? "UNKNOWN" : checkedAt.toString());
        if (change) {
            putChange(node, pr);
        }

        List<PullRequestReview> recent = reviewRepository.findRecentByPullRequestIdWithAuthor(
                pr.getId(), Set.of(PullRequestReview.State.PENDING), PageRequest.of(0, MAX_REVIEWS_READ));
        ArrayNode reviews = node.putArray("latestReviews");
        Set<Long> seen = new HashSet<>();
        boolean reviewsCut = recent.size() == MAX_REVIEWS_READ;
        // Standing GitLab approvals precede review history; this order is not approval chronology.
        for (PullRequestReview review : recent) {
            User reviewer = review.getAuthor();
            if (reviewer == null || !seen.add(reviewer.getId())) {
                continue;
            }
            if (reviews.size() == MAX_REVIEWERS) {
                reviewsCut = true;
                break;
            }
            ObjectNode entry = reviews.addObject();
            entry.put("reviewer", reviewer.getLogin());
            if (reviewer.getType() == User.Type.BOT) {
                entry.put("bot", true);
            }
            // GitHub keeps a dismissed review's original state beside the flag; a dismissed approval approves nothing.
            if (review.isDismissed() && review.getState() != PullRequestReview.State.DISMISSED) {
                entry.put("state", PullRequestReview.State.DISMISSED.name());
                entry.put("dismissedState", review.getState().name());
            } else {
                entry.put("state", review.getState().name());
            }
            Instant submittedAt = review.getSubmittedAt();
            if (submittedAt != null) {
                entry.put("submittedAt", submittedAt.toString());
            }
            entry.put("url", review.getHtmlUrl());
            entry.put("commit", review.getCommitId());
            entry.put(
                    "commitFor",
                    manifests
                            .manifestFor(pr.getProvider().kind())
                            .map(manifest -> manifest.reviewCommitFor(review.getCommitId(), pr.getHeadRefOid()))
                            .orElse("UNKNOWN"));
            if (review.getBody() != null && !review.getBody().isBlank()) {
                reviewsCut |= putBody(entry, review.getBody());
            }
        }
        node.put("latestReviewsStatus", reviewsCut ? "TRUNCATED" : "COMPLETE");

        List<IssueComment> notes = new ArrayList<>();
        boolean notesUnread = scan(
                workspaceId,
                pr.getId(),
                page -> commentRepository.findRecentByIssueIdWithAuthor(pr.getId(), page),
                IssueComment::getNativeId,
                false,
                (note, own) -> {
                    if (!own && !note.getBody().isBlank()) {
                        notes.add(note);
                    }
                    return notes.size() <= MAX_NOTES;
                });
        boolean notesCut = notesUnread || notes.size() > MAX_NOTES;
        ArrayNode noteArray = node.putArray("generalNotes");
        // Newest first from the query; listed oldest first.
        for (IssueComment note :
                notes.subList(0, Math.min(notes.size(), MAX_NOTES)).reversed()) {
            notesCut |= putComment(
                    noteArray,
                    pr.getAuthor(),
                    note.getAuthor(),
                    note.getBody(),
                    note.getCreatedAt(),
                    note.getHtmlUrl());
        }
        node.put("generalNotesStatus", notesCut ? "TRUNCATED" : "COMPLETE");

        // A thread's rows arrive together; one holding only Hephaestus's own comments is never kept.
        Map<PullRequestReviewThread, List<PullRequestReviewComment>> others = new LinkedHashMap<>();
        Set<PullRequestReviewThread> repliedTo = new HashSet<>();
        boolean threadsUnread = scan(
                workspaceId,
                pr.getId(),
                page -> queryRepository.findThreadCommentsUnresolvedFirst(pr.getId(), page),
                PullRequestReviewComment::getNativeId,
                true,
                (comment, own) -> {
                    PullRequestReviewThread thread = comment.getThread();
                    if (thread == null) {
                        return true;
                    }
                    if (own) {
                        repliedTo.add(thread);
                        return true;
                    }
                    if (!others.containsKey(thread) && others.size() == MAX_THREADS) {
                        others.put(thread, List.of());
                        return false;
                    }
                    others.computeIfAbsent(thread, t -> new ArrayList<>()).add(comment);
                    return true;
                });
        boolean threadsCut = threadsUnread || others.size() > MAX_THREADS;
        ArrayNode threads = node.putArray("threads");
        for (Map.Entry<PullRequestReviewThread, List<PullRequestReviewComment>> kept :
                others.entrySet().stream().limit(MAX_THREADS).toList()) {
            PullRequestReviewThread thread = kept.getKey();
            List<PullRequestReviewComment> comments = kept.getValue();
            ObjectNode entry = threads.addObject();
            entry.put("state", thread.getState().name());
            User resolver = thread.getResolvedBy();
            if (resolver != null) {
                entry.put("resolvedBy", resolver.getLogin());
            }
            entry.put("path", thread.getPath());
            entry.put("line", thread.getLine());
            entry.put("outdated", thread.getOutdated());
            if (repliedTo.contains(thread)) {
                entry.put("repliesToHephaestusNote", true);
            }
            String hunk = comments.isEmpty() ? null : comments.getFirst().getDiffHunk();
            if (change && hunk != null && !hunk.isBlank()) {
                // The lines the thread's first comment was written on, as the provider stored them.
                putText(entry, "diffHunk", hunk, MAX_HUNK_CHARS);
            }
            threadsCut |= comments.size() > MAX_THREAD_COMMENTS;
            ArrayNode shown = entry.putArray("comments");
            for (PullRequestReviewComment comment :
                    comments.subList(0, Math.min(comments.size(), MAX_THREAD_COMMENTS))) {
                threadsCut |= putComment(
                        shown,
                        pr.getAuthor(),
                        comment.getAuthor(),
                        comment.getBody(),
                        comment.getCreatedAt(),
                        comment.getHtmlUrl());
            }
        }
        node.put("threadsStatus", threadsCut ? "TRUNCATED" : "COMPLETE");

        List<Issue> closing = queryRepository.findClosingIssuesOfAuthoredPullRequest(
                workspaceId,
                work.actorIds(workspaceId, developerId),
                providerId,
                pr.getId(),
                PageRequest.of(0, MAX_CLOSING_ISSUES + 1));
        boolean closingCut = closing.size() > MAX_CLOSING_ISSUES;
        ArrayNode closes = node.putArray("closingIssues");
        for (Issue issue : closing.subList(0, Math.min(closing.size(), MAX_CLOSING_ISSUES))) {
            ObjectNode entry = closes.addObject();
            entry.put("issueId", issue.getId());
            entry.put("number", issue.getNumber());
            entry.put("title", issue.getTitle());
            entry.put("url", issue.getHtmlUrl());
            entry.put("state", issue.getState().name());
            entry.put("relation", CLOSING_RELATION);
            if (issue.getBody() != null && !issue.getBody().isBlank()) {
                closingCut |= putBody(entry, issue.getBody());
            }
        }
        node.put("closingIssuesStatus", closingCut ? "TRUNCATED" : "COMPLETE");
        return node;
    }

    /**
     * What one pull request changes and who it waits for, from the stored copy: requested reviewers and whether each
     * still owes a verdict, requested teams, labels, refs, the commits the provider associates with it, and the files
     * those commits touched. File changes exist only for commits whose details were captured, so {@code filesCoverage}
     * says whether the list can be whole; an absent file is never inferred. Sums per file over the commits, not the
     * provider's diff, and no code.
     */
    private void putChange(ObjectNode node, PullRequest pr) {
        RecentAuthoredWorkContentSource.putRepository(node, pr);
        node.put("baseRef", pr.getBaseRefName());
        node.put("headRef", pr.getHeadRefName());
        RecentAuthoredWorkContentSource.putDates(node, pr);
        RecentAuthoredWorkContentSource.putLabels(node, pr);
        Set<Long> awaiting = queryRepository.findAwaitingReviewerIds(pr.getId());
        ArrayNode reviewers = node.putArray("requestedReviewers");
        pr.getRequestedReviewers().stream()
                .sorted(Comparator.comparing(request -> request.getUser().getLogin()))
                .forEach(request -> {
                    ObjectNode entry = reviewers.addObject();
                    entry.put("login", request.getUser().getLogin());
                    entry.put(
                            "awaitingVerdict",
                            awaiting.contains(request.getUser().getId()));
                    RequestedReviewer.ReviewState state = request.getReviewState();
                    if (state != null) {
                        entry.put("reviewState", state.name());
                    }
                });
        ArrayNode teams = node.putArray("requestedTeams");
        pr.getRequestedTeams().stream()
                .map(request -> request.getTeam().getName())
                .sorted()
                .forEach(teams::add);
        Instant reviewersObservedAt = pr.getReviewersObservedAt();
        node.put("reviewersObservedAt", reviewersObservedAt == null ? "UNKNOWN" : reviewersObservedAt.toString());

        node.put("commitsOnProvider", pr.getCommits());
        List<Commit> commits = queryRepository.findCommitsOfPullRequest(pr.getId(), PageRequest.of(0, MAX_COMMITS + 1));
        ArrayNode commitArray = node.putArray("commits");
        for (Commit commit : commits.subList(0, Math.min(commits.size(), MAX_COMMITS))) {
            ObjectNode entry = commitArray.addObject();
            entry.put(
                    "sha",
                    commit.getSha().substring(0, Math.min(7, commit.getSha().length())));
            entry.put("headline", commit.getMessage());
            entry.put("url", commit.getHtmlUrl());
            Instant authoredAt = commit.getAuthoredAt();
            entry.put("authoredAt", authoredAt == null ? null : authoredAt.toString());
        }
        node.put("commitsTruncated", commits.size() > MAX_COMMITS);

        MentorContextQueryRepository.CommitCoverage coverage = queryRepository.countCommitsOfPullRequest(pr.getId());
        Map<String, ObjectNode> files = new LinkedHashMap<>();
        List<CommitFileChange> changes = coverage.getCaptured() == 0
                ? List.of()
                : queryRepository.findFileChangesOfPullRequest(
                        pr.getId(), PageRequest.of(0, MAX_FILE_CHANGES_READ + 1));
        boolean filesCut = changes.size() > MAX_FILE_CHANGES_READ;
        for (CommitFileChange fileChange : changes.subList(0, Math.min(changes.size(), MAX_FILE_CHANGES_READ))) {
            ObjectNode file = files.computeIfAbsent(
                    fileChange.getFilename(),
                    path -> objectMapper
                            .createObjectNode()
                            .put("path", path)
                            .put("additions", 0)
                            .put("deletions", 0));
            file.put("changeType", fileChange.getChangeType().name());
            file.put("additions", file.path("additions").asInt() + fileChange.getAdditions());
            file.put("deletions", file.path("deletions").asInt() + fileChange.getDeletions());
        }
        ArrayNode fileArray = node.putArray("files");
        files.values().stream().limit(MAX_FILES).forEach(fileArray::add);
        filesCut |= files.size() > MAX_FILES;
        node.put("changedFilesOnProvider", pr.getChangedFiles());
        node.put(
                "filesCoverage",
                coverage.getCaptured() == 0
                        ? "NOT_CAPTURED"
                        : coverage.getCaptured() == coverage.getStored()
                                        && coverage.getStored() >= pr.getCommits()
                                        && !filesCut
                                ? "COMPLETE"
                                : "PARTIAL");
    }

    /**
     * Reads stored comments a page at a time, asking the delivery ledger about each page's refs only, until
     * {@code visitor} — given each row and whether Hephaestus posted it — returns false or {@link #SCAN_BUDGET}
     * rows are read.
     *
     * @return whether the budget ran out with rows possibly unread — the caller then cannot claim a complete list
     */
    private <T> boolean scan(
            long workspaceId,
            long pullRequestId,
            Function<Pageable, List<T>> page,
            Function<T, Long> nativeId,
            boolean inline,
            BiPredicate<T, Boolean> visitor) {
        for (int read = 0; read < SCAN_BUDGET; read += SCAN_PAGE) {
            List<T> rows = page.apply(PageRequest.of(read / SCAN_PAGE, SCAN_PAGE));
            var receipts = deliveredComments.findForPullRequest(workspaceId, pullRequestId, ScmItemEvents.PULL_REQUEST);
            var posted = inline ? receipts.inline() : receipts.general();
            for (T row : rows) {
                if (!visitor.test(row, posted.contains(nativeId.apply(row)))) {
                    return false;
                }
            }
            if (rows.size() < SCAN_PAGE) {
                return false;
            }
        }
        return true;
    }

    /** @return whether the body was clipped */
    static boolean putComment(
            ArrayNode into,
            @Nullable User workAuthor,
            @Nullable User author,
            String body,
            @Nullable Instant createdAt,
            @Nullable String url) {
        ObjectNode c = into.addObject();
        c.put("author", author == null ? null : author.getLogin());
        c.put("authorRelation", authorRelation(workAuthor, author));
        if (author != null && author.getType() == User.Type.BOT) {
            c.put("bot", true);
        }
        c.put("createdAt", createdAt == null ? null : createdAt.toString());
        c.put("url", url);
        // Not in the ledger yet carrying the marker: Hephaestus's note under an unmatched ref, or a person quoting one.
        if (body.contains(WorkspaceScmProjection.HEPHAESTUS_MARKER)) {
            c.put("quotesHephaestusMarker", true);
        }
        return putBody(c, body);
    }

    /** Whether {@code author} wrote the work itself, by stored identity; unknown when either identity is missing. */
    static String authorRelation(@Nullable User workAuthor, @Nullable User author) {
        Long workAuthorId = workAuthor == null ? null : workAuthor.getId();
        Long authorId = author == null ? null : author.getId();
        if (workAuthorId == null || authorId == null) {
            return "UNKNOWN";
        }
        return workAuthorId.equals(authorId) ? "WORK_AUTHOR" : "OTHER_PARTICIPANT";
    }

    /** @return whether the body was clipped — a condition past the cut is then unseen */
    private static boolean putBody(ObjectNode entry, String body) {
        return putText(entry, "body", body);
    }

    private static boolean putText(ObjectNode entry, String key, String text) {
        return putText(entry, key, text, MAX_BODY_CHARS);
    }

    /**
     * Puts {@code text} under {@code key}, clipped at {@code max} without splitting a surrogate pair, and marks a
     * clipped one with {@code <key>Truncated}.
     *
     * @return whether the text was clipped — a condition past the cut is then unseen
     */
    static boolean putText(ObjectNode entry, String key, String text, int max) {
        if (text.length() <= max) {
            entry.put(key, text);
            return false;
        }
        int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        entry.put(key, text.substring(0, end));
        entry.put(key + "Truncated", true);
        return true;
    }

    /** Whether the recorded check state belongs to the commit the pull request now points at. */
    private static String checksFor(PullRequest pr) {
        return commitFor(pr.getHeadCheckSha(), pr.getHeadRefOid());
    }

    /** Whether {@code commit} is the head the pull request now points at; unknown where either is not recorded. */
    private static String commitFor(@Nullable String commit, @Nullable String head) {
        if (commit == null || head == null) {
            return "UNKNOWN";
        }
        return commit.equals(head) ? "CURRENT_HEAD" : "OTHER_COMMIT";
    }

    // Missing or skipped GitLab pipelines establish no failure or CI configuration.
    // Check results from another head cannot describe the reviewed head.
    private static String checksObserved(PullRequest pr) {
        CheckState state = pr.getHeadCheckState();
        if (state == null || !"CURRENT_HEAD".equals(checksFor(pr))) {
            return "NOT_CAPTURED";
        }
        return switch (state) {
            case NO_PIPELINE -> "NO_PIPELINE_REPORTED";
            case SKIPPED -> "SKIPPED_PIPELINE_REPORTED";
            case NONE -> "NONE_REPORTED";
            case SUCCESS, FAILURE, PENDING, CANCELLED -> "STATUS_REPORTED";
        };
    }

    /**
     * The provider's approval state in the words a developer reads: a conversation answer repeats this field to them.
     * On GitLab, {@code REVIEW_REQUIRED} also means that nobody approved.
     */
    private static String approval(@Nullable ReviewDecision decision) {
        if (decision == null) {
            return "UNKNOWN";
        }
        return switch (decision) {
            case APPROVED -> "approved";
            case CHANGES_REQUESTED -> "changes requested";
            case REVIEW_REQUIRED -> "not approved yet";
        };
    }

    private static String nameOrUnknown(@Nullable Enum<?> value) {
        return value == null ? "UNKNOWN" : value.name();
    }
}
