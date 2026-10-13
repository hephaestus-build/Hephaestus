package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import de.tum.cit.aet.hephaestus.agent.context.ContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.Milestone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The on-demand {@code inputs/context/issue/<artifactId>.json}: one issue the developer authored or is assigned to,
 * as stored — its body, the latest comments, its place among other issues, and the pull requests the provider
 * records closing it. Published as the {@code resource} of the issue rows in {@code recent_authored_work.json} and
 * {@code workspace.json}; anything else gets {@code NOT_FOUND}. It is the stored copy, not a live read.
 */
@Component
@RequiredArgsConstructor
public class IssueDetailSource {

    /** Mirrored by {@code ISSUE_ITEM} in {@code heph-host-protocol.ts}. */
    private static final Pattern ITEM_KEY = Pattern.compile(ContentSource.OUTPUT_PREFIX + "issue/(\\d{1,18})\\.json");

    private static final int MAX_BODY_CHARS = 4_000;
    private static final int MAX_COMMENTS = 10;
    private static final int MAX_RELATED = 10;
    private static final int MAX_CLOSING_PULL_REQUESTS = 5;

    private final WorkspaceActorSelector actorSelector;
    private final MentorContextQueryRepository queryRepository;
    private final IssueCommentRepository commentRepository;
    private final ObjectMapper objectMapper;

    /** The on-demand key of one issue's detail, published as the {@code resource} of its rows. */
    public static String resourceOf(long artifactId) {
        return ContentSource.OUTPUT_PREFIX + "issue/" + artifactId + ".json";
    }

    /** The artifact id an on-demand key names, if {@code key} is one. */
    public static Optional<Long> artifactIdOf(String key) {
        Matcher matcher = ITEM_KEY.matcher(key);
        return matcher.matches() ? Optional.of(Long.parseLong(matcher.group(1))) : Optional.empty();
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public ObjectNode inspect(long workspaceId, long developerId, long artifactId) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("readAt", Instant.now().toString());
        root.put("providerFreshness", "UNKNOWN");
        Optional<Long> provider = actorSelector.connectedProviderId(workspaceId);
        Optional<Issue> found = provider.flatMap(providerId ->
                queryRepository.findOwnIssueOnInstance(workspaceId, developerId, providerId, artifactId).stream()
                        .findFirst());
        if (provider.isEmpty() || found.isEmpty()) {
            root.put("status", "NOT_FOUND");
            root.put(
                    "reason",
                    "No issue this developer authored or is assigned to has that artifactId in this workspace. Copy "
                            + "the resource of an issue in recent_authored_work.json or workspace.json.");
            return root;
        }
        describe(root.putObject("issue"), new Scope(workspaceId, provider.get(), developerId), found.get());
        return root;
    }

    /** Where the issue was read: every related item it names is read under the same scope. */
    private record Scope(long workspaceId, long providerId, long developerId) {}

    private void describe(ObjectNode node, Scope scope, Issue issue) {
        long developerId = scope.developerId();
        node.put("artifactId", issue.getId());
        node.put("number", issue.getNumber());
        node.put("title", issue.getTitle());
        node.put("url", issue.getHtmlUrl());
        RecentAuthoredWorkContentSource.putRepository(node, issue);
        node.put("state", issue.getState().name());
        if (issue.getStateReason() != null) {
            node.put("stateReason", issue.getStateReason().name());
        }
        User author = issue.getAuthor();
        node.put("author", author == null ? null : author.getLogin());
        ArrayNode relation = node.putArray("developerRelation");
        if (author != null && Objects.equals(author.getId(), developerId)) {
            relation.add("AUTHOR");
        }
        ArrayNode assignees = node.putArray("assignees");
        issue.getAssignees().stream()
                .sorted(Comparator.comparing(User::getLogin))
                .forEach(assignee -> {
                    assignees.add(assignee.getLogin());
                    if (Objects.equals(assignee.getId(), developerId)) {
                        relation.add("ASSIGNEE");
                    }
                });
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
            if (comment.getBody() == null || comment.getBody().isBlank()) {
                continue;
            }
            commentsCut |= MergeReadinessContentSource.putComment(
                    comments,
                    author,
                    comment.getAuthor(),
                    comment.getBody(),
                    comment.getCreatedAt(),
                    comment.getHtmlUrl());
        }
        node.put("commentsStatus", commentsCut ? "TRUNCATED" : "COMPLETE");
        node.put("commentsTotal", issue.getCommentsCount());

        PageRequest related = PageRequest.of(0, MAX_RELATED + 1);
        long workspaceId = scope.workspaceId();
        long providerId = scope.providerId();
        queryRepository.findParentInScope(workspaceId, providerId, issue.getId(), related).stream()
                .findFirst()
                .ifPresent(parent -> putRelated(node.putObject("parentIssue"), parent));
        ObjectNode subIssues = node.putObject("subIssues");
        if (issue.getSubIssuesTotal() != null) {
            subIssues.put("total", issue.getSubIssuesTotal());
        }
        if (issue.getSubIssuesCompleted() != null) {
            subIssues.put("completed", issue.getSubIssuesCompleted());
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
            if (prAuthor != null && Objects.equals(prAuthor.getId(), developerId)) {
                entry.put("resource", MergeReadinessContentSource.resourceOf(pr.getId()));
            }
        }
        node.put("closingPullRequestsTruncated", closing.size() > MAX_CLOSING_PULL_REQUESTS);
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
