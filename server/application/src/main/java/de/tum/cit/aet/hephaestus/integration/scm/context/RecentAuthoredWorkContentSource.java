package de.tum.cit.aet.hephaestus.integration.scm.context;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountSummaryQuery;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.evidence.capture.ContentSource;
import de.tum.cit.aet.hephaestus.evidence.capture.ConversationContextPaths;
import de.tum.cit.aet.hephaestus.evidence.capture.ConversationContextRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Materialises {@code inputs/context/recent_authored_work.json}: the developer's own authored pull requests and
 * issues — number, title, URL, repository, state, size and dates — so Hephaestus can name and link the real work, recognise
 * what the conversation is about and bound it in time. Most recently active first; a repository a team hides from
 * contributions is left out. No diffs: one pull request's detail is read on demand from its {@code resource}.
 * Personal context is read for each turn, not cached.
 */
@Component
@RequiredArgsConstructor
public class RecentAuthoredWorkContentSource implements ContentSource {

    public static final String OUTPUT_KEY = ConversationContextPaths.RECENT_AUTHORED_WORK;

    private static final int MAX_PULL_REQUESTS = 20;
    private static final int MAX_ISSUES = 20;
    private final AccountSummaryQuery accounts;
    private final ConversationContextQueryRepository queryRepository;
    private final ScmConversationWorkQuery work;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(ConversationContextRequest request) {
        return true;
    }

    @Override
    public boolean required() {
        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public void contribute(ConversationContextRequest request, Map<String, byte[]> files) {
        ObjectNode payload = buildPayload(request.workspaceId(), request.developerId());
        try {
            files.put(OUTPUT_KEY, objectMapper.writeValueAsBytes(payload));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize recent authored work context", e);
        }
    }

    /** Reads the current bounded context; formatted personal copies are not retained between turns. */
    public ObjectNode buildPayload(Long workspaceId, Long developerId) {
        var account = accounts.findAllByIds(List.of(developerId)).get(developerId);
        if (account == null) throw new EntityNotFoundException("Account", developerId.toString());

        ObjectNode root = objectMapper.createObjectNode();
        root.putObject("account").put("displayName", account.displayName());

        // A repository path names a repository only on one instance: no work without the connected one.
        Optional<ScmConversationWorkQuery.Actors> actors = work.actors(workspaceId, developerId);
        if (actors.isEmpty()) {
            root.put("status", "UNAVAILABLE");
            root.put("reason", "The workspace has no connected GitHub or GitLab instance.");
        }
        List<PullRequest> prs = actors.filter(found -> !found.userIds().isEmpty())
                .map(found -> queryRepository.findRecentAuthoredPullRequests(
                        workspaceId, found.providerId(), found.userIds(), PageRequest.of(0, MAX_PULL_REQUESTS)))
                .orElse(List.of());
        List<Issue> issues = actors.filter(found -> !found.userIds().isEmpty())
                .map(found -> queryRepository.findRecentAuthoredIssues(
                        workspaceId, found.providerId(), found.userIds(), PageRequest.of(0, MAX_ISSUES)))
                .orElse(List.of());

        ArrayNode prArr = root.putArray("pullRequests");
        for (PullRequest pr : prs) {
            ObjectNode node = prArr.addObject();
            node.put("sourceId", pr.getId());
            node.put("resource", MergeReadinessContentSource.resourceOf(pr.getId()));
            node.put("reviewsResource", ConversationContextPaths.reviewAttempts("pull_request", pr.getId()));
            node.put("number", pr.getNumber());
            node.put("title", pr.getTitle());
            putRepository(node, pr);
            if (pr.getHtmlUrl() != null) {
                node.put("url", pr.getHtmlUrl());
            }
            if (pr.getState() != null) {
                node.put("state", pr.getState().name());
            }
            node.put("isDraft", pr.isDraft());
            putDates(node, pr);
            putInstant(node, "mergedAt", pr.getMergedAt());
            node.put("additions", pr.getAdditions());
            node.put("deletions", pr.getDeletions());
            if (pr.getHeadRefName() != null) {
                node.put("branch", pr.getHeadRefName());
            }
        }

        ArrayNode issueArr = root.putArray("issues");
        for (Issue issue : issues) {
            ObjectNode node = issueArr.addObject();
            node.put("sourceId", issue.getId());
            node.put("resource", IssueDetailSource.resourceOf(issue.getId()));
            node.put("reviewsResource", ConversationContextPaths.reviewAttempts("issue", issue.getId()));
            node.put("number", issue.getNumber());
            node.put("title", issue.getTitle());
            putRepository(node, issue);
            if (issue.getHtmlUrl() != null) {
                node.put("url", issue.getHtmlUrl());
            }
            if (issue.getState() != null) {
                node.put("state", issue.getState().name());
            }
            putDates(node, issue);
            putLabels(node, issue);
        }
        return root;
    }

    static void putRepository(ObjectNode node, Issue work) {
        Repository repository = work.getRepository();
        if (repository != null) {
            node.put("repository", repository.getNameWithOwner());
        }
    }

    /** When the work was opened, last changed and closed, as stored; an absent one is not known. */
    static void putDates(ObjectNode node, Issue work) {
        putInstant(node, "createdAt", work.getCreatedAt());
        putInstant(node, "updatedAt", work.getUpdatedAt());
        putInstant(node, "closedAt", work.getClosedAt());
    }

    static void putLabels(ObjectNode node, Issue work) {
        ArrayNode labels = node.putArray("labels");
        work.getLabels().stream().map(Label::getName).sorted().forEach(labels::add);
    }

    static void putInstant(ObjectNode node, String key, @Nullable Instant instant) {
        if (instant != null) {
            node.put(key, instant.toString());
        }
    }
}
