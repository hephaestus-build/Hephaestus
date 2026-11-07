package de.tum.cit.aet.hephaestus.integration.scm.domain.capture;

import static de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ScmCaptureContext.OUTPUT_PREFIX;

import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureContribution;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.DeliveredPullRequestCommentLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.scm.context.WorkspaceScmProjection;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
@ConditionalOnCaptureRole
@Order(210)
public class GeneralReviewCommentContentSource {

    public static final SourceKind KIND = new SourceKind("scm.general-review-comments");

    public Set<SourceKind> sourceKinds() {
        return Set.of(KIND);
    }

    public SourceKind sourceKindFor(String path) {
        var recordSource = ScmCitableTexts.recordSource(path);
        if (recordSource != null && sourceKinds().contains(recordSource)) return recordSource;
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

    /**
     * Derives completeness/emptiness from the payload itself rather than the default: the file is
     * always written, even with zero comments, so the default's file-presence check would report
     * NON_EMPTY on an empty result.
     */
    public CaptureContribution capture(ScmCaptureContext request, Set<SourceKind> selectedKinds) {
        if (!selectedKinds.contains(KIND) || !request.pullRequest()) {
            return CaptureContribution.empty();
        }
        var metadata = request.work();
        // A missing key is a malformed job; failing loud avoids silently telling the model there
        // were no comments (as ReviewThreadContentSource does for its own metadata key).
        Long pullRequestId = metadata.sourceId();
        if (pullRequestId == null) {
            throw new ItemCaptureException(
                    ItemCaptureCode.CAPTURE_FAILED, "Review-comment collection has no pull_request_id");
        }
        if (!pullRequestRepository.existsByIdAndDeletedAtIsNull(pullRequestId)) {
            return CaptureContribution.unavailable(selectedKinds, SourceAbsenceReason.NOT_FOUND);
        }
        ObjectNode root = collect(request.workspaceId(), pullRequestId);
        boolean truncated = root.path("truncated").asBoolean(false);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(
                OUTPUT_PREFIX + FILE_NAME,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
        return new CaptureContribution(
                files,
                Map.of(KIND, truncated ? SourceCompleteness.PARTIAL : SourceCompleteness.COMPLETE),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(KIND, root.path("comments").isEmpty() ? SourceContentState.EMPTY : SourceContentState.NON_EMPTY),
                Map.of(),
                Map.of(),
                null,
                truncated ? Map.of(KIND, List.of(ScmCapturedWork.DISCUSSION_NOT_READ_WHOLE)) : Map.of(),
                List.of(),
                List.of(),
                ScmCitableTexts.files(files, this::sourceKindFor, objectMapper));
    }

    private ObjectNode collect(long workspaceId, long pullRequestId) {
        try {
            Set<Long> postedComments = deliveredCommentLookup
                    .findForPullRequest(workspaceId, pullRequestId, ScmItemEvents.PULL_REQUEST)
                    .general();
            List<IssueComment> comments = new ArrayList<>(
                    issueCommentRepository.findRecentByIssueIdWithAuthor(pullRequestId, Pageable.unpaged()));
            comments.removeIf(comment -> {
                String body = comment == null ? null : comment.getBody();
                return body == null
                        || body.isBlank()
                        || (comment.getNativeId() != null && postedComments.contains(comment.getNativeId()));
            });
            boolean truncated = !pullRequestRepository.isDiscussionReadWhole(pullRequestId);
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
        } catch (JacksonException e) {
            throw new ItemCaptureException(
                    ItemCaptureCode.CAPTURE_FAILED, "General-review-comment collection failed", e);
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
        if (body.contains(WorkspaceScmProjection.HEPHAESTUS_MARKER)) node.put("quotesHephaestusMarker", true);
        if (c.getCreatedAt() != null) {
            node.put("createdAt", c.getCreatedAt().toString());
        }
        if (c.getUpdatedAt() != null) {
            node.put("updatedAt", c.getUpdatedAt().toString());
        }
        return node;
    }

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
