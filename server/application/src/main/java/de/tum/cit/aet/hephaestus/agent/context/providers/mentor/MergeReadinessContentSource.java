package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import de.tum.cit.aet.hephaestus.agent.context.ContentSource;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest.MentorChatRequest;
import de.tum.cit.aet.hephaestus.agent.context.providers.ReviewThreadContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.CheckState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
 * {@code notLoaded} is read on demand as {@code inputs/context/merge_readiness/<artifactId>.json}.
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

    public static final String OUTPUT_KEY = OUTPUT_PREFIX + "merge_readiness.json";

    /** Mirrored by {@code MERGE_READINESS_ITEM} in {@code pi-mentor-protocol.ts}. */
    private static final Pattern ITEM_KEY = Pattern.compile("inputs/context/merge_readiness/(\\d{1,18})\\.json");

    private static final int MAX_DETAILED = 5;
    private static final int MAX_LISTED = 20;
    private static final int MAX_NOTES = 10;
    private static final int MAX_REVIEWS_READ = 30;
    private static final int MAX_REVIEWERS = 5;
    private static final int MAX_BODY_CHARS = 1_000;
    private static final int MAX_THREADS = 5;
    private static final int MAX_THREAD_COMMENTS = 3;
    private static final int MAX_CLOSING_ISSUES = 5;

    /** What a closing issue is to the pull request: the provider records it as closed by a merge, nothing more. */
    private static final String CLOSING_RELATION = "PROVIDER_RECORDED_CLOSING_CANDIDATE";

    /** Stored comments read per channel per pull request, however many are Hephaestus's own. */
    private static final int SCAN_BUDGET = 200;

    private static final int SCAN_PAGE = 50;

    /**
     * The refs the delivery ledger records for a stored GitLab note, whose native id is the note id. A GitHub
     * ledger ref is a node id the mirror does not store, so a GitHub note of Hephaestus's is never matched and is
     * shown, flagged by its marker.
     */
    private static final List<String> LEDGER_REFS =
            List.of("gid://gitlab/Note/%d", "gid://gitlab/DiffNote/%d", "gid://gitlab/DiscussionNote/%d");

    private final WorkspaceActorSelector actorSelector;
    private final MentorContextQueryRepository queryRepository;
    private final PullRequestReviewRepository reviewRepository;
    private final IssueCommentRepository commentRepository;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(ContextRequest request) {
        return request instanceof MentorChatRequest;
    }

    @Override
    public boolean required() {
        return false;
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public void contribute(ContextRequest request, Map<String, byte[]> files) {
        MentorChatRequest req = (MentorChatRequest) request;
        try {
            files.put(OUTPUT_KEY, objectMapper.writeValueAsBytes(buildPayload(req.workspaceId(), req.developerId())));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize merge readiness context", e);
        }
    }

    /** The artifact id an on-demand key names, if {@code key} is one. */
    public static Optional<Long> artifactIdOf(String key) {
        Matcher matcher = ITEM_KEY.matcher(key);
        return matcher.matches() ? Optional.of(Long.parseLong(matcher.group(1))) : Optional.empty();
    }

    /** One open authored pull request, in the same shape and scope as the list; {@code NOT_FOUND} otherwise. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public ObjectNode inspect(long workspaceId, long developerId, long artifactId) {
        return build(workspaceId, (root, providerId) -> {
            // A list: two monitor rows with one path would make a single-result query throw.
            Optional<PullRequest> pr =
                    queryRepository
                            .findOpenAuthoredPullRequestOnInstance(workspaceId, developerId, providerId, artifactId)
                            .stream()
                            .findFirst();
            if (pr.isEmpty()) {
                root.put("status", "NOT_FOUND");
                root.put("reason", "No open pull request by this developer has that artifactId in this workspace.");
                return;
            }
            root.putArray("pullRequests").add(describe(workspaceId, developerId, providerId, pr.get()));
            root.putArray("notLoaded");
        });
    }

    ObjectNode buildPayload(Long workspaceId, Long developerId) {
        return build(workspaceId, (root, providerId) -> {
            List<PullRequest> open = queryRepository.findOpenAuthoredPullRequestsOnInstance(
                    workspaceId, developerId, providerId, PageRequest.of(0, MAX_DETAILED + MAX_LISTED + 1));
            ArrayNode detailed = root.putArray("pullRequests");
            ArrayNode notLoaded = root.putArray("notLoaded");
            for (int i = 0; i < Math.min(open.size(), MAX_DETAILED + MAX_LISTED); i++) {
                if (i < MAX_DETAILED) {
                    detailed.add(describe(workspaceId, developerId, providerId, open.get(i)));
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
        while (objectMapper.writeValueAsString(root).length() > MentorContextKeys.FETCH_CONTEXT_MAX_CHARS) {
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
                .put("artifactId", pr.getId())
                .put("number", pr.getNumber())
                .put("title", pr.getTitle());
    }

    private ObjectNode indexEntry(JsonNode detail) {
        return objectMapper
                .createObjectNode()
                .put("artifactId", detail.path("artifactId").asLong())
                .put("number", detail.path("number").asInt())
                .put("title", detail.path("title").asString());
    }

    private ObjectNode describe(long workspaceId, long developerId, long providerId, PullRequest pr) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("artifactId", pr.getId());
        node.put("number", pr.getNumber());
        node.put("title", pr.getTitle());
        node.put("url", pr.getHtmlUrl());
        node.put("isDraft", pr.isDraft());
        if (pr.getBody() != null && !pr.getBody().isBlank()) {
            putText(node, "description", pr.getBody());
        }
        // Webhooks advance it without re-reading merge state, so the fields below can be older.
        Instant written = pr.getLastSyncAt();
        node.put("recordUpdatedAt", written == null ? "UNKNOWN" : written.toString());
        Boolean mergeable = pr.getMergeable();
        node.put("mergeable", mergeable == null ? "UNKNOWN" : mergeable ? "YES" : "NO");
        node.put("mergeStateStatus", nameOrUnknown(pr.getMergeStateStatus()));
        node.put("reviewDecision", nameOrUnknown(pr.getReviewDecision()));
        node.put("headSha", pr.getHeadRefOid());
        node.put("checks", nameOrUnknown(pr.getHeadCheckState()));
        node.put("checksFor", checksFor(pr));
        node.put("checksSha", pr.getHeadCheckSha());
        node.put("checksObserved", checksObserved(pr));
        Instant checkedAt = pr.getHeadCheckObservedAt();
        node.put("checksObservedAt", checkedAt == null ? "UNKNOWN" : checkedAt.toString());

        List<PullRequestReview> recent = reviewRepository.findRecentByPullRequestIdWithAuthor(
                pr.getId(), Set.of(PullRequestReview.State.PENDING), PageRequest.of(0, MAX_REVIEWS_READ));
        ArrayNode reviews = node.putArray("latestReviews");
        Set<Long> seen = new HashSet<>();
        boolean reviewsCut = recent.size() == MAX_REVIEWS_READ;
        // Newest first, so the first review per reviewer is their current one.
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
            entry.put("submittedAt", review.getSubmittedAt().toString());
            // A review stands for the commit it was given on; one given on an earlier head says nothing of this one.
            entry.put("commit", review.getCommitId());
            entry.put("commitFor", commitFor(review.getCommitId(), pr.getHeadRefOid()));
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
            notesCut |= putComment(noteArray, pr.getAuthor(), note.getAuthor(), note.getBody(), note.getCreatedAt());
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
            threadsCut |= comments.size() > MAX_THREAD_COMMENTS;
            ArrayNode shown = entry.putArray("comments");
            for (PullRequestReviewComment comment :
                    comments.subList(0, Math.min(comments.size(), MAX_THREAD_COMMENTS))) {
                threadsCut |= putComment(
                        shown, pr.getAuthor(), comment.getAuthor(), comment.getBody(), comment.getCreatedAt());
            }
        }
        node.put("threadsStatus", threadsCut ? "TRUNCATED" : "COMPLETE");

        List<Issue> closing = queryRepository.findClosingIssuesOfOpenAuthoredPullRequest(
                workspaceId, developerId, providerId, pr.getId(), PageRequest.of(0, MAX_CLOSING_ISSUES + 1));
        boolean closingCut = closing.size() > MAX_CLOSING_ISSUES;
        ArrayNode closes = node.putArray("closingIssues");
        for (Issue issue : closing.subList(0, Math.min(closing.size(), MAX_CLOSING_ISSUES))) {
            ObjectNode entry = closes.addObject();
            entry.put("artifactId", issue.getId());
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
            BiPredicate<T, Boolean> visitor) {
        for (int read = 0; read < SCAN_BUDGET; read += SCAN_PAGE) {
            List<T> rows = page.apply(PageRequest.of(read / SCAN_PAGE, SCAN_PAGE));
            Map<String, Long> refs = new HashMap<>();
            for (T row : rows) {
                for (String format : LEDGER_REFS) {
                    refs.put(format.formatted(nativeId.apply(row)), nativeId.apply(row));
                }
            }
            Set<Long> posted = new HashSet<>();
            if (!refs.isEmpty()) {
                queryRepository
                        .findPostedCommentRefs(workspaceId, ArtifactKinds.PULL_REQUEST, pullRequestId, refs.keySet())
                        .forEach(ref -> posted.add(refs.get(ref)));
            }
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
    private static boolean putComment(
            ArrayNode into,
            @Nullable User workAuthor,
            @Nullable User author,
            String body,
            @Nullable Instant createdAt) {
        ObjectNode c = into.addObject();
        c.put("author", author == null ? null : author.getLogin());
        c.put("authorRelation", authorRelation(workAuthor, author));
        if (author != null && author.getType() == User.Type.BOT) {
            c.put("bot", true);
        }
        c.put("createdAt", createdAt == null ? null : createdAt.toString());
        // Not in the ledger yet carrying the marker: Hephaestus's note under an unmatched ref, or a person quoting one.
        if (body.contains(ReviewThreadContentSource.HEPHAESTUS_MARKER)) {
            c.put("quotesHephaestusMarker", true);
        }
        return putBody(c, body);
    }

    /** Whether {@code author} wrote the work itself, by stored identity; unknown when either identity is missing. */
    private static String authorRelation(@Nullable User workAuthor, @Nullable User author) {
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

    /**
     * Puts {@code text} under {@code key}, clipped at {@link #MAX_BODY_CHARS} without splitting a surrogate pair, and
     * marks a clipped one with {@code <key>Truncated}.
     *
     * @return whether the text was clipped — a condition past the cut is then unseen
     */
    private static boolean putText(ObjectNode entry, String key, String text) {
        if (text.length() <= MAX_BODY_CHARS) {
            entry.put(key, text);
            return false;
        }
        int end = Character.isHighSurrogate(text.charAt(MAX_BODY_CHARS - 1)) ? MAX_BODY_CHARS - 1 : MAX_BODY_CHARS;
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

    /**
     * What the provider was recorded saying about the current head's checks. GitLab reporting no pipeline or a skipped
     * one is not a failure, and says nothing about whether CI is configured. {@code NONE} is GitHub's empty rollup, and
     * on GitLab a record stored before the two were told apart. An observation of another commit, or none, leaves the
     * current head's checks not captured.
     */
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

    private static String nameOrUnknown(@Nullable Enum<?> value) {
        return value == null ? "UNKNOWN" : value.name();
    }
}
