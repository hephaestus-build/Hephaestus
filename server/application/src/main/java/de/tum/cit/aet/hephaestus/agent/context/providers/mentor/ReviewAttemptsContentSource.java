package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.context.ContentSource;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest.MentorChatRequest;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceCollectionException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository.ReviewAttemptRow;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository.ScmWork;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunLookup.ReviewRunFacts;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Materialises {@code inputs/context/review_attempts.json}: the newest retained practice reviews of the pull requests
 * and issues the developer authored, and on demand those of one such work. It is Hephaestus's record that a review was
 * submitted and where its retained run stands — queued or running, completed or failed — not an observation, evidence,
 * outcome or feedback delivery, so it carries no captured content, and a review whose capture or execution failed is
 * listed like any other. One {@code reviewId} is one retained run in its latest state, not each container attempt.
 *
 * <p>Bounded, never all-time: reviews retained in the last {@value #LOOKBACK_DAYS} days, at most
 * {@value #MAX_ATTEMPTS} of them, of conversations and documents none. Every read applies the work's current gates
 * afresh — author, monitored repository, connected provider, not hidden, not deleted — so a published link grants
 * nothing a later read would not, and a work the developer may not read answers the same {@code NOT_FOUND} as one that
 * does not exist.
 */
@Component
@RequiredArgsConstructor
public class ReviewAttemptsContentSource implements ContentSource {

    public static final String OUTPUT_KEY = OUTPUT_PREFIX + "review_attempts.json";

    /** Mirrored by {@code REVIEW_ATTEMPTS_ITEM} in {@code pi-mentor-protocol.ts}. */
    private static final Pattern WORK_KEY =
            Pattern.compile("inputs/context/review_attempts/(pull_request|issue)/(\\d{1,18})\\.json");

    static final int LOOKBACK_DAYS = 90;
    static final int MAX_ATTEMPTS = 20;

    private final AgentJobRepository jobRepository;
    private final ReviewRunLookup reviewRuns;
    private final WorkspaceActorSelector actorSelector;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(ContextRequest request) {
        return request instanceof MentorChatRequest;
    }

    @Override
    public boolean required() {
        return false;
    }

    /** Not cached: a review's status changes while the developer talks about it. */
    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public void contribute(ContextRequest request, Map<String, byte[]> files) {
        MentorChatRequest req = (MentorChatRequest) request;
        try {
            files.put(OUTPUT_KEY, objectMapper.writeValueAsBytes(overview(req.workspaceId(), req.developerId())));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize review attempts context", e);
        }
    }

    /** The on-demand key of one pull request's or issue's reviews, published as its {@code reviewsResource}. */
    public static String resourceOf(ArtifactKind kind, long artifactId) {
        String segment;
        if (ArtifactKinds.PULL_REQUEST.equals(kind)) {
            segment = "pull_request";
        } else if (ArtifactKinds.ISSUE.equals(kind)) {
            segment = "issue";
        } else {
            throw new IllegalArgumentException("Review attempts are listed for pull requests and issues only: " + kind);
        }
        return OUTPUT_PREFIX + "review_attempts/" + segment + "/" + artifactId + ".json";
    }

    /** The work an on-demand key names, if {@code key} is one. */
    public static Optional<ScmWork> workOf(String key) {
        Matcher matcher = WORK_KEY.matcher(key);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        AgentJobType type =
                "issue".equals(matcher.group(1)) ? AgentJobType.ISSUE_REVIEW : AgentJobType.PULL_REQUEST_REVIEW;
        return Optional.of(new ScmWork(type, Long.parseLong(matcher.group(2))));
    }

    ObjectNode overview(long workspaceId, long developerId) {
        Instant readAt = Instant.now();
        ObjectNode root = objectMapper.createObjectNode().put("readAt", readAt.toString());
        Optional<Long> providerId = actorSelector.connectedProviderId(workspaceId);
        if (providerId.isEmpty()) {
            root.put("status", "UNAVAILABLE");
            root.put("reason", "The workspace has no active GitHub or GitLab connection with synced work.");
            return root;
        }
        List<ReviewAttemptRow> rows = jobRepository.findOwnScmReviewAttempts(
                workspaceId, developerId, providerId.get(), since(readAt), MAX_ATTEMPTS + 1);
        return render(root, workspaceId, rows);
    }

    /**
     * The retained reviews of one pull request or issue the developer authored. An eligible work without any is an
     * empty list; anything else — another developer's, another workspace's or provider's, hidden, deleted, or the
     * workspace disconnected — gets the same {@code NOT_FOUND}.
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public ObjectNode inspect(long workspaceId, long developerId, ScmWork work) {
        Instant readAt = Instant.now();
        ObjectNode root = objectMapper.createObjectNode().put("readAt", readAt.toString());
        List<ReviewAttemptRow> rows = actorSelector
                .connectedProviderId(workspaceId)
                .map(providerId -> jobRepository.findOwnScmReviewAttemptsOfWork(
                        workspaceId, developerId, providerId, work, since(readAt), MAX_ATTEMPTS + 1))
                .orElse(List.of());
        if (rows.isEmpty()) {
            root.put("status", "NOT_FOUND");
            root.put(
                    "reason",
                    "No pull request or issue of this developer has that id in this workspace. Copy the "
                            + "reviewsResource of an entry in recent_authored_work.json or review_attempts.json.");
            return root;
        }
        putWork(root.putObject("work"), rows.getFirst());
        return render(
                root,
                workspaceId,
                rows.stream().filter(row -> row.getReviewId() != null).toList());
    }

    private ObjectNode render(ObjectNode root, long workspaceId, List<ReviewAttemptRow> rows) {
        List<ReviewAttemptRow> shown = rows.subList(0, Math.min(rows.size(), MAX_ATTEMPTS));
        Map<UUID, ReviewRunFacts> facts = reviewRuns.findFacts(
                workspaceId,
                shown.stream()
                        .map(row -> Objects.requireNonNull(row.getReviewId()))
                        .toList());
        ObjectNode coverage = root.putObject("coverage");
        coverage.put("scope", "RETAINED_REVIEWS_OF_OWN_AUTHORED_SCM_WORK");
        coverage.putArray("workKinds").add(ArtifactKinds.PULL_REQUEST.value()).add(ArtifactKinds.ISSUE.value());
        coverage.put("lookbackDays", LOOKBACK_DAYS);
        coverage.put("maxEntries", MAX_ATTEMPTS);
        coverage.put("hasMore", rows.size() > MAX_ATTEMPTS);
        ArrayNode attempts = root.putArray("attempts");
        for (ReviewAttemptRow row : shown) {
            UUID reviewId = Objects.requireNonNull(row.getReviewId());
            ReviewRunFacts fact = facts.get(reviewId);
            if (fact == null) {
                // Dropping it would list fewer reviews than were found, as if the rest never ran.
                throw new EvidenceCollectionException("A retained review has no run facts: reviewId=" + reviewId, null);
            }
            ObjectNode node = attempts.addObject();
            node.put("reviewId", reviewId.toString());
            putWork(node, row);
            node.put("status", fact.status().name());
            node.put("triggerMode", fact.triggerMode().name());
            Instant createdAt = row.getCreatedAt();
            if (createdAt != null) {
                node.put("createdAt", createdAt.toString());
            }
            Instant completedAt = row.getCompletedAt();
            if (completedAt != null) {
                node.put("completedAt", completedAt.toString());
            }
        }
        return root;
    }

    private static void putWork(ObjectNode node, ReviewAttemptRow row) {
        ArtifactKind kind = "ISSUE".equals(row.getWorkType()) ? ArtifactKinds.ISSUE : ArtifactKinds.PULL_REQUEST;
        node.put("artifactKind", kind.value());
        node.put("artifactId", row.getArtifactId());
        node.put("number", row.getNumber());
        node.put("url", row.getUrl());
        node.put("state", row.getState());
        node.put("reviewsResource", resourceOf(kind, row.getArtifactId()));
    }

    private static Instant since(Instant readAt) {
        return readAt.minus(LOOKBACK_DAYS, ChronoUnit.DAYS);
    }
}
