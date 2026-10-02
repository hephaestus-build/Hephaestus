package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import de.tum.cit.aet.hephaestus.agent.context.ContentSource;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest.MentorChatRequest;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Materialises {@code inputs/context/recent_authored_work.json} for a {@link MentorChatRequest}.
 *
 * <p><b>Why this exists (the mentor must know the work, not just observations about it).</b> The other mentor
 * contexts describe the work only indirectly (observations, delivered feedback, counts), so a reference like "my
 * camera change" has no concrete anchor. This context supplies the inventory: the developer's own authored PRs
 * and issues — number, title, URL, state, size — drawn from the same tables practice reviews use, so the
 * mentor can name and link the real work and recognise what the conversation is about.
 *
 * <p>The work itself only (no diffs — those are fetched on demand for a specific artifact, not pre-mounted
 * for every PR). Best-effort like its sibling contexts. Cache key: {@code workspaceId + ":" + developerId}.
 */
@Component
@RequiredArgsConstructor
public class RecentAuthoredWorkContentSource implements ContentSource {

    /** Workspace-relative output key. Whitelisted in {@code MentorContextKeys#ALLOWED_OUTPUT_KEYS}. */
    public static final String OUTPUT_KEY = OUTPUT_PREFIX + "recent_authored_work.json";

    private static final int MAX_PULL_REQUESTS = 20;
    private static final int MAX_ISSUES = 20;
    private static final String CACHE_NAME = "mentor_authored_work_context";

    private final UserRepository userRepository;
    private final MentorContextQueryRepository queryRepository;
    private final ObjectMapper objectMapper;
    private final CacheManager cacheManager;

    @Override
    public boolean supports(ContextRequest request) {
        return request instanceof MentorChatRequest;
    }

    @Override
    public boolean required() {
        return false;
    }

    /** Tx-on-contribute / not-on-buildPayload AOP convention documented at {@link MentorContextKeys}. */
    @Override
    @Transactional(readOnly = true)
    public void contribute(ContextRequest request, Map<String, byte[]> files) {
        MentorChatRequest req = (MentorChatRequest) request;
        String key = req.workspaceId() + ":" + req.developerId();
        Cache cache = cacheManager.getCache(CACHE_NAME);
        ObjectNode payload = (cache != null)
                ? cache.get(key, () -> buildPayload(req.workspaceId(), req.developerId()))
                : buildPayload(req.workspaceId(), req.developerId());
        try {
            files.put(OUTPUT_KEY, objectMapper.writeValueAsBytes(payload));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize recent authored work context", e);
        }
    }

    /** Pure function of (workspaceId, developerId). Callers cache through {@link CacheManager}. */
    public ObjectNode buildPayload(Long workspaceId, Long developerId) {
        User user = userRepository
                .findById(developerId)
                .orElseThrow(() -> new EntityNotFoundException("User", developerId.toString()));

        List<PullRequest> prs = queryRepository.findRecentAuthoredPullRequests(
                workspaceId, developerId, PageRequest.of(0, MAX_PULL_REQUESTS));
        List<Issue> issues =
                queryRepository.findRecentAuthoredIssues(workspaceId, developerId, PageRequest.of(0, MAX_ISSUES));

        ObjectNode root = objectMapper.createObjectNode();
        root.putObject("user").put("login", user.getLogin()).put("name", user.getName());

        ArrayNode prArr = root.putArray("pullRequests");
        for (PullRequest pr : prs) {
            ObjectNode node = prArr.addObject();
            node.put("artifactId", pr.getId());
            node.put("resource", MergeReadinessContentSource.resourceOf(pr.getId()));
            node.put("reviewsResource", ReviewAttemptsContentSource.resourceOf(ArtifactKinds.PULL_REQUEST, pr.getId()));
            node.put("number", pr.getNumber());
            node.put("title", pr.getTitle());
            if (pr.getHtmlUrl() != null) {
                node.put("url", pr.getHtmlUrl());
            }
            if (pr.getState() != null) {
                node.put("state", pr.getState().name());
            }
            node.put("isDraft", pr.isDraft());
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
            node.put("reviewsResource", ReviewAttemptsContentSource.resourceOf(ArtifactKinds.ISSUE, issue.getId()));
            node.put("number", issue.getNumber());
            node.put("title", issue.getTitle());
            if (issue.getHtmlUrl() != null) {
                node.put("url", issue.getHtmlUrl());
            }
            if (issue.getState() != null) {
                node.put("state", issue.getState().name());
            }
        }
        return root;
    }
}
