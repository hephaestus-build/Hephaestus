package de.tum.cit.aet.hephaestus.agent.context.providers;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceCollectionException;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceContribution;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceLimits;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceSource;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThreadRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
@Order(200)
public class ReviewThreadContentSource implements EvidenceSource {

    private static final SourceKind KIND = new SourceKind("scm.review-threads");

    @Override
    public Set<SourceKind> sourceKinds() {
        return Set.of(KIND);
    }

    @Override
    public SourceKind sourceKindFor(String path) {
        return KIND;
    }

    private static final Logger log = LoggerFactory.getLogger(ReviewThreadContentSource.class);

    static final String FILE_NAME = "review_threads.json";

    static final int MAX_THREADS = EvidenceLimits.MAX_ITEMS_PER_SOURCE;

    static final int MAX_DECISIONS = 30;

    private final ObjectMapper objectMapper;
    private final PullRequestRepository pullRequestRepository;
    private final PullRequestReviewThreadRepository threadRepository;
    private final PullRequestReviewRepository reviewRepository;

    public ReviewThreadContentSource(
            ObjectMapper objectMapper,
            PullRequestRepository pullRequestRepository,
            PullRequestReviewThreadRepository threadRepository,
            PullRequestReviewRepository reviewRepository) {
        this.objectMapper = objectMapper;
        this.pullRequestRepository = pullRequestRepository;
        this.threadRepository = threadRepository;
        this.reviewRepository = reviewRepository;
    }

    @Override
    public boolean supports(ContextRequest request) {
        return request instanceof ContextRequest.PracticeReviewRequest;
    }

    @Override
    public boolean required() {
        return false;
    }

    @Override
    public void contribute(ContextRequest request, Map<String, byte[]> files) {
        contributeSelected(request, sourceKinds(), files);
    }

    @Override
    public void contributeSelected(ContextRequest request, Set<SourceKind> selectedKinds, Map<String, byte[]> files) {
        files.putAll(capture(request, selectedKinds).files());
    }

    private ObjectNode collect(long pullRequestId) {
        try {
            List<Long> threadIds = new java.util.ArrayList<>(
                    threadRepository.findRecentIdsByPullRequestId(pullRequestId, PageRequest.of(0, MAX_THREADS + 1)));
            boolean threadsTruncated = threadIds.size() > MAX_THREADS;
            if (threadsTruncated) threadIds.remove(threadIds.size() - 1);
            List<PullRequestReviewThread> threads =
                    threadIds.isEmpty() ? List.of() : threadRepository.findAllByIdWithResolvedBy(threadIds);
            List<PullRequestReview> reviews =
                    new java.util.ArrayList<>(reviewRepository.findRecentByPullRequestIdWithAuthor(
                            pullRequestId,
                            Set.of(PullRequestReview.State.PENDING, PullRequestReview.State.UNKNOWN),
                            PageRequest.of(0, MAX_DECISIONS + 1)));
            if (reviews.size() > MAX_DECISIONS + 1) {
                reviews = new java.util.ArrayList<>(reviews.subList(0, MAX_DECISIONS + 1));
            }
            boolean decisionsTruncated = reviews.size() > MAX_DECISIONS;
            if (decisionsTruncated) reviews.remove(reviews.size() - 1);

            ObjectNode root = objectMapper.createObjectNode();

            ArrayNode threadArray = objectMapper.createArrayNode();
            for (PullRequestReviewThread t : threads) {
                if (t == null || isHephaestusThread(t)) {
                    continue;
                }
                threadArray.add(toThread(t));
            }
            root.set("threads", threadArray);

            ArrayNode decisionArray = objectMapper.createArrayNode();
            for (PullRequestReview review : reviews) {
                if (review == null || review.getState() == null) {
                    continue;
                }
                if (review.getState() == PullRequestReview.State.PENDING
                        || review.getState() == PullRequestReview.State.UNKNOWN) {
                    continue;
                }
                decisionArray.add(toDecision(review));
            }
            root.set("reviewDecisions", decisionArray);
            root.put("truncated", threadsTruncated || decisionsTruncated);

            log.info(
                    "ReviewThreads: prId={} threads={} decisions={}",
                    pullRequestId,
                    threadArray.size(),
                    decisionArray.size());
            return root;
        } catch (Exception e) {
            throw new EvidenceCollectionException("Review-thread collection failed", e);
        }
    }

    @Override
    public EvidenceContribution capture(ContextRequest request, Set<SourceKind> selectedKinds) {
        if (!selectedKinds.contains(KIND) || !(request instanceof ContextRequest.PracticeReviewRequest review)) {
            return new EvidenceContribution(Map.of(), Map.of());
        }
        var metadata = review.job().getMetadata();
        // Missing metadata is a malformed job, not a PR with no review threads — the two must not
        // collapse into the same "collection found nothing" fact.
        if (metadata == null || metadata.isNull() || metadata.isMissingNode()) {
            throw new EvidenceCollectionException("Review-thread collection has no job metadata", null);
        }
        Long pullRequestId = MetaJson.optLong(metadata, "pull_request_id");
        if (pullRequestId == null) {
            throw new EvidenceCollectionException("Review-thread collection has no pull_request_id", null);
        }
        var pullRequest = pullRequestRepository.findById(pullRequestId).orElse(null);
        if (pullRequest == null || pullRequest.getDeletedAt() != null) {
            return EvidenceContribution.unavailable(selectedKinds, SourceAbsenceReason.NOT_FOUND);
        }
        ObjectNode root = collect(pullRequestId);
        boolean empty =
                root.path("threads").isEmpty() && root.path("reviewDecisions").isEmpty();
        boolean truncated = root.path("truncated").asBoolean();
        return new EvidenceContribution(
                Map.of(
                        OUTPUT_PREFIX + FILE_NAME,
                        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root)),
                Map.of(KIND, truncated ? SourceCompleteness.PARTIAL : SourceCompleteness.COMPLETE),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(KIND, empty ? SourceContentState.EMPTY : SourceContentState.NON_EMPTY));
    }

    /**
     * Marker embedded in every Hephaestus practice-review note ({@code <!-- hephaestus-diff-note -->},
     * {@code <!-- hephaestus:practice-review:… -->}). A human reviewer never writes it, so a thread that
     * carries it is the tool's own posted finding, never a reviewer thread. The {@code rootComment} FK is
     * not populated by the sync, so we scan the thread's comment set for the marker.
     *
     * <p>The marker is the ONLY signal used: the mirror's Hephaestus identity is an opaque
     * {@code group_*_bot_*} access-token login indistinguishable from a human's by login alone, so a
     * login substring match would silently drop a genuine reviewer thread from anyone whose login happens
     * to contain "hephaestus" (e.g. a fork named {@code hephaestus-fan}) — masking a real review signal.
     */
    private static final String HEPHAESTUS_MARKER = "<!-- hephaestus";

    private static boolean isHephaestusThread(PullRequestReviewThread t) {
        var comments = t.getComments();
        if (comments == null || comments.isEmpty()) {
            return false;
        }
        for (var c : comments) {
            if (c == null) {
                continue;
            }
            String body = c.getBody();
            if (body != null && body.contains(HEPHAESTUS_MARKER)) {
                return true;
            }
        }
        return false;
    }

    private ObjectNode toThread(PullRequestReviewThread t) {
        ObjectNode node = objectMapper.createObjectNode();
        if (t.getPath() != null) {
            node.put("path", t.getPath());
        }
        if (t.getLine() != null) {
            node.put("line", t.getLine());
        }
        node.put("state", t.getState() == null ? "UNRESOLVED" : t.getState().name());
        String resolver = login(t.getResolvedBy());
        if (resolver != null) {
            node.put("resolvedBy", resolver);
        }
        if (t.getOutdated() != null) {
            node.put("outdated", t.getOutdated());
        }
        return node;
    }

    private ObjectNode toDecision(PullRequestReview review) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("state", review.getState().name());
        if (review.isDismissed()) {
            node.put("dismissed", true);
        }
        String author = login(review.getAuthor());
        if (author != null) {
            node.put("author", author);
        }
        // Raw timestamp so the agent can compute supersession (a later APPROVE by the same reviewer
        // overriding an earlier CHANGES_REQUESTED) downstream — this connector loads facts, it does not judge.
        if (review.getSubmittedAt() != null) {
            node.put("submittedAt", review.getSubmittedAt().toString());
        }
        return node;
    }

    private static @Nullable String login(@Nullable User user) {
        if (user == null) {
            return null;
        }
        String login = user.getLogin();
        return (login != null && !login.isBlank()) ? login : null;
    }
}
