package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountSummaryQuery;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.evidence.capture.ContentSource;
import de.tum.cit.aet.hephaestus.evidence.capture.MentorContextPaths;
import de.tum.cit.aet.hephaestus.evidence.capture.MentorContextRequest;
import de.tum.cit.aet.hephaestus.integration.core.spi.MentorWorkQuery;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceSummaryQuery;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The developer's open assigned issues and the review requests that wait for them, with the age of each pull request,
 * in one document so that Heph reads them with one call. Each assigned issue names its detail as {@code resource}.
 */
// Personal workspace context is read per turn; it must not enter a shared formatted-content cache.
@Component
@RequiredArgsConstructor
public class WorkspaceContentSource implements ContentSource {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceContentSource.class);

    /** Workspace-relative output key. Whitelisted in {@code MentorContextKeys#ALLOWED_OUTPUT_KEYS}. */
    public static final String OUTPUT_KEY = OUTPUT_PREFIX + "workspace.json";

    /** The most assigned issues and the most review requests listed; a longer list is marked truncated. */
    static final int MAX_LISTED = 30;

    private static final int REVIEW_WAIT_URGENCY_DAYS = 3;

    private final AccountSummaryQuery accounts;
    private final WorkspaceSummaryQuery workspaceSummaries;
    private final MentorWorkQuery queryRepository;
    private final WorkspaceActorSelector actorSelector;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(MentorContextRequest request) {
        return true;
    }

    @Override
    public boolean required() {
        return false;
    }

    /** Tx-on-contribute / not-on-buildPayload AOP convention documented at {@link MentorContextKeys}. */
    @Override
    @Transactional(readOnly = true)
    public void contribute(MentorContextRequest request, Map<String, byte[]> files) {
        ObjectNode payload = buildPayload(request.workspaceId(), request.developerId());
        try {
            files.put(OUTPUT_KEY, objectMapper.writeValueAsBytes(payload));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize workspace context", e);
        }
    }

    /** Reads the current context; formatted personal copies are not retained between turns. */
    public ObjectNode buildPayload(Long workspaceId, Long developerId) {
        var account = accounts.findAllByIds(List.of(developerId)).get(developerId);
        if (account == null) throw new EntityNotFoundException("Account", developerId.toString());
        var workspace = workspaceSummaries
                .findById(workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceId.toString()));

        ObjectNode root = objectMapper.createObjectNode();
        root.putObject("user").put("name", account.displayName());

        ObjectNode workspaceNode = root.putObject("workspace");
        workspaceNode.put("slug", workspace.slug());
        workspaceNode.put("displayName", workspace.displayName());

        // A repository path names a repository only on one instance: no lists without the connected one.
        boolean connected = actorSelector.connectedProviderId(workspaceId).isPresent();
        if (!connected) {
            root.put("status", "UNAVAILABLE");
            root.put("reason", "The workspace has no connected GitHub or GitLab instance.");
        }
        // Each sub-context is independent and best-effort: a failing query degrades only its own
        // section to an empty array, never blanking the whole workspace context. The real cause is
        // logged here so an unavailable section does not hide the cause.
        List<MentorWorkQuery.RequestedReview> reviewPrs = connected
                ? guardedQuery(
                        "pendingReviewRequests",
                        () -> queryRepository.findPendingReviewRequestPrs(workspaceId, developerId))
                : List.of();
        List<MentorWorkQuery.AssignedIssue> assigned = connected
                ? guardedQuery("assignedIssues", () -> queryRepository.findAssignedOpenIssues(workspaceId, developerId))
                : List.of();
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
            log.warn(
                    "Mentor workspace sub-context '{}' failed, degrading to empty: {}",
                    context,
                    e.getClass().getName(),
                    e);
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
            log.warn(
                    "Mentor workspace query '{}' failed, degrading to empty: {}",
                    context,
                    e.getClass().getName(),
                    e);
            return List.of();
        }
    }

    private void addAssignedIssues(ObjectNode root, List<MentorWorkQuery.AssignedIssue> issues) {
        ArrayNode arr = root.putArray("assignedIssues");
        root.put("assignedIssuesTruncated", issues.size() > MAX_LISTED);
        for (var issue : issues.subList(0, Math.min(issues.size(), MAX_LISTED))) {
            ObjectNode node = arr.addObject();
            node.put("sourceId", issue.id());
            node.put("resource", MentorContextPaths.issue(issue.id()));
            node.put("number", issue.number());
            node.put("title", issue.title());
            node.put("url", issue.url());
            if (issue.repository() != null) node.put("repository", issue.repository());
            if (issue.milestoneTitle() != null) node.put("milestoneTitle", issue.milestoneTitle());
            putInstant(node, "milestoneDueOn", issue.milestoneDueOn());
            putInstant(node, "createdAt", issue.createdAt());
            putInstant(node, "updatedAt", issue.updatedAt());
            ArrayNode labels = node.putArray("labels");
            issue.labels().forEach(labels::add);
        }
    }

    private void addPendingReviewRequests(ObjectNode root, List<MentorWorkQuery.RequestedReview> reviews) {
        ArrayNode arr = root.putArray("pendingReviewRequests");
        root.put("pendingReviewRequestsTruncated", reviews.size() > MAX_LISTED);
        Instant now = Instant.now();
        for (var review : reviews.subList(0, Math.min(reviews.size(), MAX_LISTED))) {
            ObjectNode node = arr.addObject();
            node.put("prNumber", review.number());
            node.put("prTitle", review.title());
            node.put("url", review.url());
            if (review.author() != null) node.put("author", review.author());
            if (review.repository() != null) node.put("repository", review.repository());
            // When the review was requested is not stored; the pull request's age is.
            Instant created = review.createdAt();
            if (created != null) {
                node.put("prCreatedAt", created.toString());
                node.put("prOpenDays", Duration.between(created, now).toDays());
            }
        }
    }

    private static void putInstant(ObjectNode node, String key, @Nullable Instant instant) {
        if (instant != null) node.put(key, instant.toString());
    }

    private void addFocusSuggestions(
            ObjectNode root,
            List<MentorWorkQuery.AssignedIssue> issues,
            List<MentorWorkQuery.RequestedReview> reviews) {
        computeFocusSuggestions(issues, reviews).forEach(root.putArray("focusSuggestions")::add);
    }

    static List<String> computeFocusSuggestions(
            List<MentorWorkQuery.AssignedIssue> issues, List<MentorWorkQuery.RequestedReview> reviews) {
        List<String> out = new ArrayList<>();
        Instant now = Instant.now();
        long stale = reviews.stream()
                .map(MentorWorkQuery.RequestedReview::createdAt)
                .filter(created ->
                        created != null && Duration.between(created, now).toDays() >= REVIEW_WAIT_URGENCY_DAYS)
                .count();
        if (stale > 0) out.add(stale + " review request(s) waiting " + REVIEW_WAIT_URGENCY_DAYS + "+ days.");
        long deadlines =
                issues.stream().filter(issue -> issue.milestoneDueOn() != null).count();
        if (deadlines > 0) out.add(deadlines + " assigned issue(s) have milestone deadlines.");
        return out;
    }
}
