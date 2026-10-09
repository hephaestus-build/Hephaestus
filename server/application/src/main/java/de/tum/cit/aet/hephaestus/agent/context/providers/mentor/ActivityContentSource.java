package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import de.tum.cit.aet.hephaestus.agent.context.ContentSource;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest.HephTurnRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.Commit;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Materialises {@code inputs/context/activity.json}: what the developer did in the last {@value #WINDOW_DAYS} days, each
 * action dated — the pull requests and issues they opened, merged or closed, the reviews and comments they wrote, and
 * the commits they authored. Their own actions only, in repositories the workspace monitors and no team hides from
 * contributions, the scope Activity counts. Words are their own words, cut short. It lists and dates; it never
 * scores.
 */
@Component
@RequiredArgsConstructor
public class ActivityContentSource implements ContentSource {

    public static final String OUTPUT_KEY = OUTPUT_PREFIX + "activity.json";

    static final int WINDOW_DAYS = 14;

    private static final int MAX_WORK = 30;
    private static final int MAX_ROWS = 30;
    private static final int MAX_WORDS_CHARS = 400;

    /** The file's own bound: words go first, then the oldest rows of the longest list, each counted. */
    private static final int MAX_CHARS = 32_000;

    private static final List<String> LISTS =
            List.of("pullRequests", "issues", "reviewsGiven", "comments", "inlineComments", "commits");

    private final MentorContextQueryRepository queryRepository;
    private final WorkspaceActorSelector actorSelector;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Override
    public boolean supports(ContextRequest request) {
        return request instanceof HephTurnRequest;
    }

    @Override
    public boolean required() {
        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public void contribute(ContextRequest request, Map<String, byte[]> files) {
        HephTurnRequest req = (HephTurnRequest) request;
        try {
            files.put(OUTPUT_KEY, objectMapper.writeValueAsBytes(buildPayload(req.workspaceId(), req.developerId())));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize activity context", e);
        }
    }

    ObjectNode buildPayload(long workspaceId, long developerId) {
        Instant until = clock.instant();
        Instant since = until.minus(WINDOW_DAYS, ChronoUnit.DAYS);
        PageRequest work = PageRequest.of(0, MAX_WORK);
        PageRequest rows = PageRequest.of(0, MAX_ROWS);

        ObjectNode root = objectMapper.createObjectNode();
        root.putObject("window")
                .put("since", since.toString())
                .put("until", until.toString())
                .put("days", WINDOW_DAYS);
        root.put(
                "about",
                "The developer's own actions, from Hephaestus's stored copy, newest first. People's "
                        + "reviews they gave are here; Hephaestus's automated practice reviews are not.");

        // A repository path names a repository only on one instance: no activity without the connected one.
        Optional<Long> provider = actorSelector.connectedProviderId(workspaceId);
        if (provider.isEmpty()) {
            root.put("status", "UNAVAILABLE");
            root.put("reason", "The workspace has no connected GitHub or GitLab instance.");
            return root;
        }
        long providerId = provider.get();
        ArrayNode pullRequests = root.putArray("pullRequests");
        for (PullRequest pr : queryRepository.findAuthoredPullRequestsActiveSince(
                workspaceId, providerId, developerId, since, work)) {
            ObjectNode node = workNode(pullRequests.addObject(), pr);
            node.put("resource", MergeReadinessContentSource.resourceOf(pr.getId()));
            node.put("isDraft", pr.isDraft());
            ArrayNode actions = node.putArray("actions");
            action(actions, "OPENED", pr.getCreatedAt(), since);
            action(actions, "MERGED", pr.getMergedAt(), since);
            if (!pr.isMerged()) {
                action(actions, "CLOSED", pr.getClosedAt(), since);
            }
        }
        ArrayNode issues = root.putArray("issues");
        for (Issue issue :
                queryRepository.findAuthoredIssuesActiveSince(workspaceId, providerId, developerId, since, work)) {
            ObjectNode node = workNode(issues.addObject(), issue);
            node.put("resource", IssueDetailSource.resourceOf(issue.getId()));
            ArrayNode actions = node.putArray("actions");
            action(actions, "OPENED", issue.getCreatedAt(), since);
            action(actions, "CLOSED", issue.getClosedAt(), since);
        }
        ArrayNode reviews = root.putArray("reviewsGiven");
        for (PullRequestReview review :
                queryRepository.findReviewsGivenSince(workspaceId, providerId, developerId, since, rows)) {
            ObjectNode node = reviews.addObject();
            PullRequest reviewed = Objects.requireNonNull(review.getPullRequest());
            ObjectNode on = workNode(node.putObject("pullRequest"), reviewed);
            User author = reviewed.getAuthor();
            on.put("author", author == null ? null : author.getLogin());
            node.put("state", review.getState().name());
            Instant submittedAt = review.getSubmittedAt();
            node.put("submittedAt", submittedAt == null ? null : submittedAt.toString());
            node.put("url", review.getHtmlUrl());
            putWords(node, review.getBody());
        }
        ArrayNode comments = root.putArray("comments");
        for (IssueComment comment :
                queryRepository.findCommentsWrittenSince(workspaceId, providerId, developerId, since, rows)) {
            ObjectNode node = comments.addObject();
            Issue on = Objects.requireNonNull(comment.getIssue());
            workNode(node.putObject("on"), on).put("kind", on instanceof PullRequest ? "PULL_REQUEST" : "ISSUE");
            node.put(
                    "createdAt",
                    comment.getCreatedAt() == null
                            ? null
                            : comment.getCreatedAt().toString());
            node.put("url", comment.getHtmlUrl());
            putWords(node, comment.getBody());
        }
        ArrayNode inline = root.putArray("inlineComments");
        for (PullRequestReviewComment comment :
                queryRepository.findInlineCommentsWrittenSince(workspaceId, providerId, developerId, since, rows)) {
            ObjectNode node = inline.addObject();
            workNode(node.putObject("pullRequest"), Objects.requireNonNull(comment.getPullRequest()));
            node.put("path", comment.getPath());
            node.put(
                    "createdAt",
                    comment.getCreatedAt() == null
                            ? null
                            : comment.getCreatedAt().toString());
            node.put("url", comment.getHtmlUrl());
            putWords(node, comment.getBody());
        }
        ArrayNode commits = root.putArray("commits");
        for (Commit commit :
                queryRepository.findCommitsAuthoredSince(workspaceId, providerId, developerId, since, rows)) {
            ObjectNode node = commits.addObject();
            node.put("repository", commit.getRepository().getNameWithOwner());
            node.put(
                    "sha",
                    commit.getSha().substring(0, Math.min(7, commit.getSha().length())));
            node.put("headline", commit.getMessage());
            node.put(
                    "authoredAt",
                    commit.getAuthoredAt() == null
                            ? null
                            : commit.getAuthoredAt().toString());
            node.put("url", commit.getHtmlUrl());
        }
        ObjectNode limits = root.putObject("listLimits");
        limits.put("pullRequests", MAX_WORK).put("issues", MAX_WORK);
        LISTS.stream().skip(2).forEach(list -> limits.put(list, MAX_ROWS));
        return fitted(root);
    }

    private static ObjectNode workNode(ObjectNode node, Issue work) {
        node.put("artifactId", work.getId());
        node.put("number", work.getNumber());
        node.put("title", work.getTitle());
        RecentAuthoredWorkContentSource.putRepository(node, work);
        node.put("url", work.getHtmlUrl());
        node.put("state", work.getState().name());
        return node;
    }

    /** Adds the action when it happened inside the window; an older one is not this window's. */
    private static void action(ArrayNode actions, String kind, @Nullable Instant at, Instant since) {
        if (at != null && !at.isBefore(since)) {
            actions.addObject().put("kind", kind).put("at", at.toString());
        }
    }

    private static void putWords(ObjectNode node, @Nullable String words) {
        if (words != null && !words.isBlank()) {
            MergeReadinessContentSource.putText(node, "words", words, MAX_WORDS_CHARS);
        }
    }

    /** Leaves out the words first, then the oldest rows of the longest list, each count named in the file. */
    private ObjectNode fitted(ObjectNode root) {
        if (fits(root)) {
            return root;
        }
        LISTS.forEach(list -> root.path(list).forEach(row -> {
            if (row instanceof ObjectNode entry && entry.has("words")) {
                entry.remove(List.of("words", "wordsTruncated"));
                entry.put("wordsNotLoaded", true);
            }
        }));
        ObjectNode omitted = objectMapper.createObjectNode();
        while (!fits(root)) {
            Optional<String> longest = LISTS.stream()
                    .filter(list -> !root.path(list).isEmpty())
                    .max(Comparator.comparingInt(list -> root.path(list).size()));
            if (longest.isEmpty()) {
                break;
            }
            ((ArrayNode) root.path(longest.get()))
                    .remove(root.path(longest.get()).size() - 1);
            omitted.put(longest.get(), omitted.path(longest.get()).asInt() + 1);
        }
        root.set("omittedForSize", omitted);
        return root;
    }

    private boolean fits(JsonNode root) {
        return objectMapper.writeValueAsString(root).length() <= MAX_CHARS;
    }
}
