package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import de.tum.cit.aet.hephaestus.agent.context.ContentSource;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest.HephTurnRequest;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
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
 * Materialises {@code inputs/context/recent_authored_work.json} for a {@link HephTurnRequest}.
 *
 * <p><b>Why this exists (the mentor must know the work, not just observations about it).</b> The other mentor
 * contexts describe the work only indirectly (observations, delivered feedback, counts), so a reference like "my
 * camera change" has no concrete anchor. This context supplies the inventory: the developer's own authored PRs
 * and issues — number, title, URL, repository, state, size and dates — drawn from the same tables practice reviews
 * use, so the mentor can name and link the real work, recognise what the conversation is about and bound it in time.
 * Most recently active first; a repository a team hides from contributions is left out.
 *
 * <p>The work itself only (no diffs — those are fetched on demand for a specific artifact, not pre-mounted
 * for every PR). Best-effort like its sibling contexts. Personal context is read for each turn, not cached.
 */
@Component
@RequiredArgsConstructor
public class RecentAuthoredWorkContentSource implements ContentSource {

    /** Workspace-relative output key. Whitelisted in {@code MentorContextKeys#ALLOWED_OUTPUT_KEYS}. */
    public static final String OUTPUT_KEY = OUTPUT_PREFIX + "recent_authored_work.json";

    private static final int MAX_PULL_REQUESTS = 20;
    private static final int MAX_ISSUES = 20;
    private final UserRepository userRepository;
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
            throw new IllegalStateException("Failed to serialize recent authored work context", e);
        }
    }

    /** Reads the current bounded context; formatted personal copies are not retained between turns. */
    public ObjectNode buildPayload(Long workspaceId, Long developerId) {
        User user = userRepository
                .findById(developerId)
                .orElseThrow(() -> new EntityNotFoundException("User", developerId.toString()));

        // A repository path names a repository only on one instance: no work without the connected one.
        Optional<Long> provider = actorSelector.connectedProviderId(workspaceId);
        List<PullRequest> prs = provider.map(providerId -> queryRepository.findRecentAuthoredPullRequests(
                        workspaceId, providerId, developerId, PageRequest.of(0, MAX_PULL_REQUESTS)))
                .orElse(List.of());
        List<Issue> issues = provider.map(providerId -> queryRepository.findRecentAuthoredIssues(
                        workspaceId, providerId, developerId, PageRequest.of(0, MAX_ISSUES)))
                .orElse(List.of());

        ObjectNode root = objectMapper.createObjectNode();
        root.putObject("user").put("login", user.getLogin()).put("name", user.getName());
        if (provider.isEmpty()) {
            root.put("status", "UNAVAILABLE");
            root.put("reason", "The workspace has no connected GitHub or GitLab instance.");
        }

        ArrayNode prArr = root.putArray("pullRequests");
        for (PullRequest pr : prs) {
            ObjectNode node = prArr.addObject();
            node.put("artifactId", pr.getId());
            node.put("resource", MergeReadinessContentSource.resourceOf(pr.getId()));
            node.put("reviewsResource", ReviewAttemptsContentSource.resourceOf(ArtifactKinds.PULL_REQUEST, pr.getId()));
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
            node.put("artifactId", issue.getId());
            node.put("resource", IssueDetailSource.resourceOf(issue.getId()));
            node.put("reviewsResource", ReviewAttemptsContentSource.resourceOf(ArtifactKinds.ISSUE, issue.getId()));
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
