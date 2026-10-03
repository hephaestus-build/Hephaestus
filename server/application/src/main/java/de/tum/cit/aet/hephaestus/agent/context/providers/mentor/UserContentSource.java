package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import de.tum.cit.aet.hephaestus.agent.context.ContentSource;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest.MentorChatRequest;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Materialises {@code inputs/context/user.json} for {@link MentorChatRequest}.
 *
 * <p>The provider does NOT decide whether a piece of data is relevant — the agent does, given the
 * full week/last-week numbers and a small set of pre-generated insight strings.
 *
 * <p>Personal context is read for each turn, not retained in a formatted-content cache.
 */
@Component
@RequiredArgsConstructor
public class UserContentSource implements ContentSource {

    /** Workspace-relative output key. Whitelisted in {@code MentorContextKeys#ALLOWED_OUTPUT_KEYS}. */
    public static final String OUTPUT_KEY = OUTPUT_PREFIX + "user.json";

    /** Open-PR threshold above which we surface "you have a lot of open PRs" advice. */
    private static final int OPEN_PR_WARNING_THRESHOLD = 3;

    private final UserRepository userRepository;
    private final MentorContextQueryRepository queryRepository;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(ContextRequest request) {
        return request instanceof MentorChatRequest;
    }

    @Override
    public boolean required() {
        // Content sources are best-effort. If user data is unavailable, the agent should
        // still be able to chat — it just loses week-over-week framing.
        return false;
    }

    /** Tx-on-contribute / not-on-buildPayload AOP convention documented at {@link MentorContextKeys}. */
    @Override
    @Transactional(readOnly = true)
    public void contribute(ContextRequest request, Map<String, byte[]> files) {
        MentorChatRequest req = (MentorChatRequest) request;
        ObjectNode payload = buildPayload(req.workspaceId(), req.developerId());
        try {
            files.put(OUTPUT_KEY, objectMapper.writeValueAsBytes(payload));
        } catch (JacksonException e) {
            // An ObjectNode of longs/strings is effectively always serializable, so this is defensive only.
            throw new IllegalStateException("Failed to serialize user context", e);
        }
    }

    /** Reads the current bounded context; formatted personal copies are not retained between turns. */
    public ObjectNode buildPayload(Long workspaceId, Long developerId) {
        User user = userRepository
                .findById(developerId)
                .orElseThrow(() -> new EntityNotFoundException("User", developerId.toString()));

        Instant now = Instant.now();
        Instant weekAgo = now.minus(7, ChronoUnit.DAYS);
        Instant twoWeeksAgo = now.minus(14, ChronoUnit.DAYS);

        MentorUserCounts c = queryRepository.fetchUserCounts(workspaceId, developerId, twoWeeksAgo, weekAgo, now);
        long openPRs = c.openPRs();
        long mergedThisWeek = c.mergedThisWeek();
        long mergedLastWeek = c.mergedLastWeek();
        long openIssues = c.openIssues();
        long reviewsGivenThisWeek = c.reviewsGivenThisWeek();
        long reviewsGivenLastWeek = c.reviewsGivenLastWeek();
        long reviewsReceivedThisWeek = c.reviewsReceivedThisWeek();
        long pendingReviewRequests = c.pendingReviewRequests();
        long unresolvedThreads = c.unresolvedThreads();

        ObjectNode root = objectMapper.createObjectNode();

        ObjectNode userNode = root.putObject("user");
        userNode.put("login", user.getLogin());
        userNode.put("name", user.getName());

        ObjectNode thisWeek = root.putObject("thisWeek");
        thisWeek.put("prsMerged", mergedThisWeek);
        thisWeek.put("prsOpen", openPRs);
        thisWeek.put("issuesOpen", openIssues);
        thisWeek.put("reviewsGiven", reviewsGivenThisWeek);
        thisWeek.put("reviewsReceived", reviewsReceivedThisWeek);
        thisWeek.put("pendingReviewRequests", pendingReviewRequests);
        thisWeek.put("unresolvedThreads", unresolvedThreads);

        ObjectNode lastWeek = root.putObject("lastWeek");
        lastWeek.put("prsMerged", mergedLastWeek);
        lastWeek.put("reviewsGiven", reviewsGivenLastWeek);

        ActivityInsights insights = generateInsights(
                openPRs,
                mergedThisWeek,
                mergedLastWeek,
                reviewsGivenThisWeek,
                reviewsGivenLastWeek,
                pendingReviewRequests,
                unresolvedThreads);
        ArrayNode insightsArray = root.putArray("insights");
        insights.insights().forEach(insightsArray::add);
        ArrayNode reflectionArray = root.putArray("suggestedReflectionTopics");
        insights.reflectionTopics().forEach(reflectionArray::add);

        return root;
    }

    /**
     * Pure function turning the raw counts into a small set of hand-crafted nudges. Heuristics live
     * here (not in the agent) so the wire payload is identical across deployments.
     */
    static ActivityInsights generateInsights(
            long openPRs,
            long mergedThisWeek,
            long mergedLastWeek,
            long reviewsGivenThisWeek,
            long reviewsGivenLastWeek,
            long pendingReviewRequests,
            long unresolvedThreads) {
        List<String> insights = new ArrayList<>();
        List<String> reflection = new ArrayList<>();

        if (mergedThisWeek > mergedLastWeek && mergedLastWeek > 0) {
            insights.add("Shipping velocity increased: " + mergedThisWeek
                    + " PRs merged this week vs "
                    + mergedLastWeek
                    + " last week.");
        } else if (mergedThisWeek < mergedLastWeek && mergedLastWeek > 0) {
            insights.add("Shipping slowed: " + mergedThisWeek + " PRs merged this week vs " + mergedLastWeek
                    + " last week.");
            reflection.add("What affected your shipping pace this week?");
        }

        if (openPRs > OPEN_PR_WARNING_THRESHOLD) {
            insights.add("You have " + openPRs
                    + " open PRs. Consider focusing on getting these merged before starting new work.");
            reflection.add("Which open PR is closest to being merge-ready?");
        }

        if (pendingReviewRequests > 0) {
            insights.add(pendingReviewRequests + " teammates are waiting for your review.");
            reflection.add("Could unblocking a teammate be today's quick win?");
        }

        if (unresolvedThreads > 0) {
            insights.add(unresolvedThreads + " review threads on your open PRs need attention.");
        }

        if (reviewsGivenThisWeek == 0 && reviewsGivenLastWeek > 0) {
            insights.add("No reviews given this week. Reviewing helps the team and your own learning.");
        }

        if (insights.isEmpty()) {
            insights.add("Steady week with consistent activity.");
        }

        return new ActivityInsights(insights, reflection);
    }

    /** Pair of generated nudges; freed for unit testing the insight heuristics in isolation. */
    public record ActivityInsights(List<String> insights, List<String> reflectionTopics) {
        public ActivityInsights {
            insights = List.copyOf(insights);
            reflectionTopics = List.copyOf(reflectionTopics);
        }
    }
}
