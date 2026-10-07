package de.tum.cit.aet.hephaestus.agent.context.providers;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceCollectionException;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceContribution;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceSource;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.DeliveredPullRequestCommentLookup;
import de.tum.cit.aet.hephaestus.integration.scm.context.WorkspaceScmProjection;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
@Order(210)
public class GeneralReviewCommentContentSource implements EvidenceSource {

    public static final SourceKind KIND = new SourceKind("scm.general-review-comments");

    @Override
    public Set<SourceKind> sourceKinds() {
        return Set.of(KIND);
    }

    @Override
    public SourceKind sourceKindFor(String path) {
        return KIND;
    }

    private static final Logger log = LoggerFactory.getLogger(GeneralReviewCommentContentSource.class);

    static final String FILE_NAME = "general_comments.json";

    private final ObjectMapper objectMapper;
    private final DeliveredPullRequestCommentLookup deliveredCommentLookup;
    private final IssueCommentRepository issueCommentRepository;
    private final PullRequestRepository pullRequestRepository;

    public GeneralReviewCommentContentSource(
            ObjectMapper objectMapper,
            IssueCommentRepository issueCommentRepository,
            PullRequestRepository pullRequestRepository,
            DeliveredPullRequestCommentLookup deliveredCommentLookup) {
        this.objectMapper = objectMapper;
        this.issueCommentRepository = issueCommentRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.deliveredCommentLookup = deliveredCommentLookup;
    }

    @Override
    public boolean supports(ContextRequest request) {
        return request instanceof ContextRequest.PracticeReviewRequest;
    }

    @Override
    public boolean required() {
        return false;
    }

    /**
     * Derives completeness/emptiness from the payload itself rather than the default: the file is
     * always written, even with zero comments, so the default's file-presence check would report
     * NON_EMPTY on an empty result.
     */
    @Override
    public EvidenceContribution capture(ContextRequest request, Set<SourceKind> selectedKinds) {
        if (!selectedKinds.contains(KIND) || !(request instanceof ContextRequest.PracticeReviewRequest review)) {
            return new EvidenceContribution(Map.of(), Map.of());
        }
        var metadata = review.job().getMetadata();
        // A missing key is a malformed job; failing loud avoids silently telling the model there
        // were no comments (as ReviewThreadContentSource does for its own metadata key).
        if (metadata == null || metadata.isNull() || metadata.isMissingNode()) {
            throw new EvidenceCollectionException("Review-comment collection has no job metadata", null);
        }
        Long pullRequestId = MetaJson.optLong(metadata, "pull_request_id");
        if (pullRequestId == null) {
            throw new EvidenceCollectionException("Review-comment collection has no pull_request_id", null);
        }
        if (!pullRequestRepository.existsByIdAndDeletedAtIsNull(pullRequestId)) {
            return EvidenceContribution.unavailable(selectedKinds, SourceAbsenceReason.NOT_FOUND);
        }
        ObjectNode root = collect(review.job().getWorkspace().getId(), pullRequestId);
        boolean truncated = root.path("truncated").asBoolean(false);
        return new EvidenceContribution(
                Map.of(
                        OUTPUT_PREFIX + FILE_NAME,
                        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root)),
                Map.of(KIND, truncated ? SourceCompleteness.PARTIAL : SourceCompleteness.COMPLETE),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(
                        KIND,
                        root.path("comments").isEmpty() ? SourceContentState.EMPTY : SourceContentState.NON_EMPTY));
    }

    @Override
    public void contribute(ContextRequest request, Map<String, byte[]> files) {
        contributeSelected(request, sourceKinds(), files);
    }

    @Override
    public void contributeSelected(ContextRequest request, Set<SourceKind> selectedKinds, Map<String, byte[]> files) {
        files.putAll(capture(request, selectedKinds).files());
    }

    private ObjectNode collect(long workspaceId, long pullRequestId) {
        try {
            Set<Long> postedComments = deliveredCommentLookup
                    .findForPullRequest(workspaceId, pullRequestId)
                    .general();
            List<IssueComment> comments = new ArrayList<>(issueCommentRepository.findRecentHumanByIssueIdWithAuthor(
                    pullRequestId, WorkspaceScmProjection.HEPHAESTUS_MARKER, Pageable.unpaged()));
            comments.removeIf(comment -> {
                String body = comment == null ? null : comment.getBody();
                return body == null
                        || body.isBlank()
                        || body.contains(WorkspaceScmProjection.HEPHAESTUS_MARKER)
                        || (comment.getNativeId() != null && postedComments.contains(comment.getNativeId()));
            });
            boolean truncated = false;
            comments.sort(
                    Comparator.comparing(IssueComment::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));

            ArrayNode commentArray = objectMapper.createArrayNode();
            for (IssueComment c : comments) {
                commentArray.add(toComment(c, c.getBody()));
            }
            ObjectNode root = objectMapper.createObjectNode();
            root.set("comments", commentArray);
            root.put("truncated", truncated);
            log.info(
                    "GeneralReviewComments: prId={} emitted={} truncated={}",
                    pullRequestId,
                    commentArray.size(),
                    truncated);
            return root;
        } catch (Exception e) {
            throw new EvidenceCollectionException("General-review-comment collection failed", e);
        }
    }

    private ObjectNode toComment(IssueComment c, String body) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("nativeId", c.getNativeId());
        String author = login(c.getAuthor());
        if (author != null) {
            node.put("author", author);
            if (isBot(c.getAuthor())) {
                node.put("bot", true);
            }
        }
        if (c.getAuthor() != null) {
            node.put("authorId", c.getAuthor().getNativeId());
        }
        node.put("body", body);
        if (c.getCreatedAt() != null) {
            node.put("createdAt", c.getCreatedAt().toString());
        }
        if (c.getUpdatedAt() != null) {
            node.put("updatedAt", c.getUpdatedAt().toString());
        }
        return node;
    }

    /** The provider's own classification, as the adapter stored it. */
    private static boolean isBot(@Nullable User user) {
        return user != null && user.getType() == User.Type.BOT;
    }

    private static @Nullable String login(@Nullable User user) {
        if (user == null) {
            return null;
        }
        String login = user.getLogin();
        return (login != null && !login.isBlank()) ? login : null;
    }
}
