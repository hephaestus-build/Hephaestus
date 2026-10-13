package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import de.tum.cit.aet.hephaestus.agent.context.ContentSource;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest.HephTurnRequest;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Materialises {@code inputs/context/workspace.json} for {@link HephTurnRequest}.
 *
 * <p>Open assigned issues and pending review requests with the age of each pull request, in one document so that Heph reads
 * them with one call.
 *
 * <p>Personal context is read for each turn, not retained in a formatted-content cache.
 */
@Component
@RequiredArgsConstructor
public class WorkspaceContentSource implements ContentSource {

    /** The most assigned issues and the most review requests listed; a longer list is marked truncated. */
    private static final int MAX_LISTED = 30;

    private static final Logger log = LoggerFactory.getLogger(WorkspaceContentSource.class);

    /** Workspace-relative output key. Whitelisted in {@code MentorContextKeys#ALLOWED_OUTPUT_KEYS}. */
    public static final String OUTPUT_KEY = OUTPUT_PREFIX + "workspace.json";

    private static final int REVIEW_WAIT_URGENCY_DAYS = 3;

    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final MentorContextQueryRepository queryRepository;
    private final WorkspaceActorSelector actorSelector;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(ContextRequest request) {
        return request instanceof HephTurnRequest;
    }

    @Override
    public boolean required() {
        return false;
    }

    /** Tx-on-contribute / not-on-buildPayload AOP convention documented at {@link MentorContextKeys}. */
    @Override
    @Transactional(readOnly = true)
    public void contribute(ContextRequest request, Map<String, byte[]> files) {
        HephTurnRequest req = (HephTurnRequest) request;
        ObjectNode payload = buildPayload(req.workspaceId(), req.developerId());
        try {
            files.put(OUTPUT_KEY, objectMapper.writeValueAsBytes(payload));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize workspace context", e);
        }
    }

    /** Reads the current context; formatted personal copies are not retained between turns. */
    public ObjectNode buildPayload(Long workspaceId, Long developerId) {
        User user = userRepository
                .findById(developerId)
                .orElseThrow(() -> new EntityNotFoundException("User", developerId.toString()));
        Workspace workspace = workspaceRepository
                .findById(workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceId.toString()));

        ObjectNode root = objectMapper.createObjectNode();
        root.putObject("user").put("login", user.getLogin()).put("name", user.getName());

        ObjectNode workspaceNode = root.putObject("workspace");
        workspaceNode.put("slug", workspace.getWorkspaceSlug());
        workspaceNode.put("displayName", workspace.getDisplayName());

        // Each sub-context is independent and best-effort: a failing query degrades only its own
        // section to an empty array, never blanking the whole workspace context. The real cause is
        // logged here so an unavailable section does not hide the cause.
        // A repository path names a repository only on one instance: no lists without the connected one.
        Optional<Long> provider = actorSelector.connectedProviderId(workspaceId);
        if (provider.isEmpty()) {
            root.put("status", "UNAVAILABLE");
            root.put("reason", "The workspace has no connected GitHub or GitLab instance.");
        }
        List<PullRequest> reviewPrs = provider.isEmpty()
                ? List.of()
                : guardedQuery(
                        "pendingReviewRequests",
                        () -> queryRepository.findPendingReviewRequestPrs(workspaceId, provider.get(), developerId));
        List<Issue> assigned = provider.isEmpty()
                ? List.of()
                : guardedQuery(
                        "assignedIssues",
                        () -> queryRepository.findAssignedOpenIssues(workspaceId, provider.get(), developerId));
        guarded("assignedIssues", () -> addAssignedIssues(root, assigned), () -> root.putArray("assignedIssues"));
        guarded(
                "pendingReviewRequests",
                () -> addPendingReviewRequests(root, reviewPrs),
                () -> root.putArray("pendingReviewRequests"));
        guarded(
                "focusSuggestions",
                () -> addFocusSuggestions(root, assigned, reviewPrs),
                () -> root.putArray("focusSuggestions"));

        return root;
    }

    /** Run a best-effort context step; on failure log the real cause and apply the empty-fallback. */
    private void guarded(String context, Runnable step, Runnable fallback) {
        try {
            step.run();
        } catch (RuntimeException e) {
            log.warn("Mentor workspace sub-context '{}' failed, degrading to empty: {}", context, e.toString(), e);
            try {
                fallback.run();
            } catch (RuntimeException ignored) {
                // fallback is a pure putArray; nothing further to do
            }
        }
    }

    /** Best-effort query; on failure log the real cause and return an empty list. */
    private <T> List<T> guardedQuery(String context, Supplier<List<T>> query) {
        try {
            return query.get();
        } catch (RuntimeException e) {
            log.warn("Mentor workspace query '{}' failed, degrading to empty: {}", context, e.toString(), e);
            return List.of();
        }
    }

    private void addAssignedIssues(ObjectNode root, List<Issue> issues) {
        ArrayNode arr = root.putArray("assignedIssues");
        root.put("assignedIssuesTruncated", issues.size() > MAX_LISTED);
        for (Issue issue : issues.subList(0, Math.min(issues.size(), MAX_LISTED))) {
            ObjectNode node = arr.addObject();
            node.put("artifactId", issue.getId());
            node.put("resource", IssueDetailSource.resourceOf(issue.getId()));
            node.put("number", issue.getNumber());
            node.put("title", issue.getTitle());
            node.put("url", issue.getHtmlUrl());
            if (issue.getRepository() != null) {
                node.put("repository", issue.getRepository().getNameWithOwner());
            }
            if (issue.getMilestone() != null) {
                node.put("milestoneTitle", issue.getMilestone().getTitle());
                if (issue.getMilestone().getDueOn() != null) {
                    node.put("milestoneDueOn", issue.getMilestone().getDueOn().toString());
                }
            }
            RecentAuthoredWorkContentSource.putDates(node, issue);
            RecentAuthoredWorkContentSource.putLabels(node, issue);
        }
    }

    private void addPendingReviewRequests(ObjectNode root, List<PullRequest> prs) {
        ArrayNode arr = root.putArray("pendingReviewRequests");
        root.put("pendingReviewRequestsTruncated", prs.size() > MAX_LISTED);
        Instant now = Instant.now();
        for (PullRequest pr : prs.subList(0, Math.min(prs.size(), MAX_LISTED))) {
            ObjectNode node = arr.addObject();
            node.put("prNumber", pr.getNumber());
            node.put("prTitle", pr.getTitle());
            node.put("url", pr.getHtmlUrl());
            if (pr.getAuthor() != null) {
                node.put("author", pr.getAuthor().getLogin());
            }
            if (pr.getRepository() != null) {
                node.put("repository", pr.getRepository().getNameWithOwner());
            }
            // When the review was requested is not stored; the pull request's age is.
            if (pr.getCreatedAt() != null) {
                node.put("prCreatedAt", pr.getCreatedAt().toString());
                node.put("prOpenDays", Duration.between(pr.getCreatedAt(), now).toDays());
            }
        }
    }

    private void addFocusSuggestions(ObjectNode root, List<Issue> issues, List<PullRequest> reviewPrs) {
        ArrayNode arr = root.putArray("focusSuggestions");
        List<String> suggestions = computeFocusSuggestions(issues, reviewPrs);
        suggestions.forEach(arr::add);
    }

    /**
     * Pure function; extracted for unit-testability. Two simple heuristics: stale review
     * requests + issues with milestone deadlines.
     */
    static List<String> computeFocusSuggestions(List<Issue> issues, List<PullRequest> reviewPrs) {
        List<String> out = new ArrayList<>();
        Instant now = Instant.now();
        long stale = reviewPrs.stream()
                .filter(p -> p.getCreatedAt() != null)
                .filter(p -> Duration.between(p.getCreatedAt(), now).toDays() >= REVIEW_WAIT_URGENCY_DAYS)
                .count();
        if (stale > 0) {
            out.add(stale + " review request(s) waiting " + REVIEW_WAIT_URGENCY_DAYS + "+ days.");
        }
        long withDeadlines = issues.stream()
                .filter(i -> i.getMilestone() != null && i.getMilestone().getDueOn() != null)
                .count();
        if (withDeadlines > 0) {
            out.add(withDeadlines + " assigned issue(s) have milestone deadlines.");
        }
        return out;
    }
}
