package de.tum.cit.aet.hephaestus.integration.scm.context;

import de.tum.cit.aet.hephaestus.evidence.capture.ContentSource;
import de.tum.cit.aet.hephaestus.evidence.capture.MentorContextPaths;
import de.tum.cit.aet.hephaestus.evidence.capture.MentorContextRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.Milestone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The on-demand {@code inputs/context/issue/<sourceId>.json}: one issue the developer authored or is assigned to, as
 * stored — its body, the latest comments, its place among other issues, and the pull requests the provider records
 * closing it. Published as the {@code resource} of the issue rows in {@code recent_authored_work.json},
 * {@code workspace.json} and {@code activity.json}; anything else gets {@code NOT_FOUND}. It writes no document of its
 * own. It is the stored copy, not a live read.
 */
@Component
@RequiredArgsConstructor
public class IssueDetailSource implements ContentSource {

    /** One item path, which a {@code read} of the turn's context folder stages on demand. */
    private static final Pattern ITEM_KEY = Pattern.compile("inputs/context/issue/([1-9][0-9]{0,18})\\.json");

    private static final int MAX_BODY_CHARS = 4_000;
    private static final int MAX_COMMENTS = 10;
    private static final int MAX_RELATED = 10;
    private static final int MAX_CLOSING_PULL_REQUESTS = 5;

    private final ScmMentorWorkQuery work;
    private final MentorContextQueryRepository queryRepository;
    private final IssueCommentRepository commentRepository;
    private final ObjectMapper objectMapper;

    /** The on-demand key of one issue's detail, published as the {@code resource} of its rows. */
    public static String resourceOf(long issueId) {
        return MentorContextPaths.issue(issueId);
    }

    /** The issue id an on-demand key names, if {@code key} is one. */
    public static Optional<Long> issueIdOf(String key) {
        Matcher matcher = ITEM_KEY.matcher(key);
        if (!matcher.matches()) return Optional.empty();
        try {
            return Optional.of(Long.parseLong(matcher.group(1)));
        } catch (NumberFormatException invalid) {
            return Optional.empty();
        }
    }

    @Override
    public boolean supports(MentorContextRequest request) {
        return false;
    }

    @Override
    public boolean required() {
        return false;
    }

    @Override
    public void contribute(MentorContextRequest request, Map<String, byte[]> files) {
        // Read on demand only: no fixed document.
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public Optional<JsonNode> read(long workspaceId, long accountId, String path) {
        return issueIdOf(path).map(id -> inspectPayload(workspaceId, accountId, id));
    }

    /** One issue the developer authored or is assigned to, in any stored state; {@code NOT_FOUND} otherwise. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public ObjectNode inspect(long workspaceId, long accountId, long issueId) {
        return inspectPayload(workspaceId, accountId, issueId);
    }

    private ObjectNode inspectPayload(long workspaceId, long accountId, long issueId) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("readAt", Instant.now().toString());
        root.put("providerFreshness", "UNKNOWN");
        Optional<ScmMentorWorkQuery.Actors> actors = work.actors(workspaceId, accountId);
        // A list: two monitor rows with one path would make a single-result query throw.
        Optional<Issue> found = actors.filter(scope -> !scope.userIds().isEmpty())
                .flatMap(scope ->
                        queryRepository
                                .findOwnIssueOnInstance(workspaceId, scope.userIds(), scope.providerId(), issueId)
                                .stream()
                                .findFirst());
        if (actors.isEmpty() || found.isEmpty()) {
            root.put("status", "NOT_FOUND");
            root.put(
                    "reason",
                    "No issue this developer authored or is assigned to has that sourceId in this workspace. Copy the "
                            + "resource of an issue in recent_authored_work.json, workspace.json or activity.json.");
            return root;
        }
        describe(root.putObject("issue"), workspaceId, actors.get(), found.get());
        return root;
    }

    /** Every related item the issue names is read under the scope the issue was read with. */
    private void describe(ObjectNode node, long workspaceId, ScmMentorWorkQuery.Actors scope, Issue issue) {
        List<Long> developer = scope.userIds();
        node.put("sourceId", issue.getId());
        node.put("number", issue.getNumber());
        node.put("title", issue.getTitle());
        node.put("url", issue.getHtmlUrl());
        RecentAuthoredWorkContentSource.putRepository(node, issue);
        node.put("state", issue.getState().name());
        Issue.StateReason stateReason = issue.getStateReason();
        if (stateReason != null) {
            node.put("stateReason", stateReason.name());
        }
        User author = issue.getAuthor();
        node.put("author", author == null ? null : author.getLogin());
        ArrayNode relation = node.putArray("developerRelation");
        if (isDeveloper(author, developer)) {
            relation.add("AUTHOR");
        }
        ArrayNode assignees = node.putArray("assignees");
        boolean assigned = false;
        for (User assignee : issue.getAssignees().stream()
                .sorted(Comparator.comparing(User::getLogin))
                .toList()) {
            assignees.add(assignee.getLogin());
            assigned |= isDeveloper(assignee, developer);
        }
        if (assigned) {
            relation.add("ASSIGNEE");
        }
        RecentAuthoredWorkContentSource.putLabels(node, issue);
        Milestone milestone = issue.getMilestone();
        if (milestone != null) {
            ObjectNode m = node.putObject("milestone").put("title", milestone.getTitle());
            RecentAuthoredWorkContentSource.putInstant(m, "dueOn", milestone.getDueOn());
        }
        RecentAuthoredWorkContentSource.putDates(node, issue);
        String body = issue.getBody();
        if (body != null && !body.isBlank()) {
            MergeReadinessContentSource.putText(node, "body", body, MAX_BODY_CHARS);
        }

        List<IssueComment> latest =
                commentRepository.findRecentByIssueIdWithAuthor(issue.getId(), PageRequest.of(0, MAX_COMMENTS + 1));
        boolean commentsCut = latest.size() > MAX_COMMENTS;
        ArrayNode comments = node.putArray("comments");
        // Newest first from the query; listed oldest first.
        for (IssueComment comment :
                latest.subList(0, Math.min(latest.size(), MAX_COMMENTS)).reversed()) {
            String words = comment.getBody();
            if (words == null || words.isBlank()) {
                continue;
            }
            commentsCut |= MergeReadinessContentSource.putComment(
                    comments, author, comment.getAuthor(), words, comment.getCreatedAt(), comment.getHtmlUrl());
        }
        Integer expectedComments = issue.getCommentsCount();
        node.put(
                "commentsStatus",
                commentsCut
                        ? "TRUNCATED"
                        : expectedComments == null || expectedComments > latest.size() ? "PARTIAL" : "COMPLETE");
        node.put("commentsTotal", issue.getCommentsCount());

        PageRequest related = PageRequest.of(0, MAX_RELATED + 1);
        long providerId = scope.providerId();
        queryRepository.findParentInScope(workspaceId, providerId, issue.getId(), related).stream()
                .findFirst()
                .ifPresent(parent -> putRelated(node.putObject("parentIssue"), parent));
        ObjectNode subIssues = node.putObject("subIssues");
        Integer subIssuesTotal = issue.getSubIssuesTotal();
        if (subIssuesTotal != null) {
            subIssues.put("total", subIssuesTotal);
        }
        Integer subIssuesCompleted = issue.getSubIssuesCompleted();
        if (subIssuesCompleted != null) {
            subIssues.put("completed", subIssuesCompleted);
        }
        putRelatedList(
                subIssues,
                "items",
                queryRepository.findSubIssuesInScope(workspaceId, providerId, issue.getId(), related));
        putRelatedList(
                node,
                "blockedBy",
                queryRepository.findBlockersInScope(workspaceId, providerId, issue.getId(), related));
        putRelatedList(
                node, "blocking", queryRepository.findBlockedInScope(workspaceId, providerId, issue.getId(), related));

        List<PullRequest> closing = queryRepository.findPullRequestsClosingIssue(
                workspaceId, providerId, issue.getId(), PageRequest.of(0, MAX_CLOSING_PULL_REQUESTS + 1));
        ArrayNode closedBy = node.putArray("closingPullRequests");
        for (PullRequest pr : closing.subList(0, Math.min(closing.size(), MAX_CLOSING_PULL_REQUESTS))) {
            ObjectNode entry = closedBy.addObject();
            entry.put("number", pr.getNumber());
            entry.put("title", pr.getTitle());
            entry.put("url", pr.getHtmlUrl());
            entry.put("state", pr.getState().name());
            User prAuthor = pr.getAuthor();
            entry.put("author", prAuthor == null ? null : prAuthor.getLogin());
            if (isDeveloper(prAuthor, developer)) {
                entry.put("resource", MergeReadinessContentSource.resourceOf(pr.getId()));
            }
        }
        node.put("closingPullRequestsTruncated", closing.size() > MAX_CLOSING_PULL_REQUESTS);
    }

    private static boolean isDeveloper(@Nullable User user, List<Long> developer) {
        return user != null && user.getId() != null && developer.contains(user.getId());
    }

    /** Related issues read in scope, at most {@link #MAX_RELATED}; the query read one more to tell a cut list. */
    private static void putRelatedList(ObjectNode node, String key, List<Issue> inScope) {
        ArrayNode array = node.putArray(key);
        inScope.stream().limit(MAX_RELATED).forEach(related -> putRelated(array.addObject(), related));
        if (inScope.size() > MAX_RELATED) {
            node.put(key + "Truncated", true);
        }
    }

    private static void putRelated(ObjectNode node, Issue related) {
        node.put("number", related.getNumber());
        node.put("title", related.getTitle());
        node.put("url", related.getHtmlUrl());
        node.put("state", related.getState().name());
        RecentAuthoredWorkContentSource.putRepository(node, related);
    }
}
