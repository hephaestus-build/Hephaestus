package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.exception.AccessForbiddenException;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.events.BotCommandReceivedEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScmCommentReplySink;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequest;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequestStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

/** Turns {@code /hephaestus review} on a pull or merge request into the review request the work page makes. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ReviewCommandListener {
    private static final Logger log = LoggerFactory.getLogger(ReviewCommandListener.class);
    private static final String REVIEW = "/hephaestus review";

    private final ReviewRequestService requests;
    private final List<ScmCommentReplySink> replies;
    private final ReviewRepository reviews;
    private final ReviewItemRepository memberships;

    // Not joined: a refused request rolls back its own transaction, and the refusal is the handled outcome.
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(BotCommandReceivedEvent command) {
        String text = command.noteBody().strip().toLowerCase(Locale.ROOT);
        if (!text.equals(REVIEW) && !text.startsWith(REVIEW + " ")) {
            log.debug(
                    "Ignored an unknown bot command: workspaceId={}, itemId={}",
                    command.workspaceId(),
                    command.itemId());
            return;
        }
        var key = UUID.nameUUIDFromBytes((command.kind() + ":" + command.providerId() + ":" + command.commentId())
                .getBytes(StandardCharsets.UTF_8));
        var answer = command.replyAddress() == null
                ? null
                : new ReviewRequest.CommentAnswer(command.kind(), command.replyAddress());
        String refusal;
        try {
            var outcome = requests.requestByComment(
                    command.workspaceId(),
                    command.itemId(),
                    new PersonIdentity(command.providerId(), Long.toString(command.authorNativeId()), null),
                    key,
                    answer);
            log.info(
                    "Review command: status={}, reason={}, reviewId={}, workspaceId={}, itemId={}",
                    outcome.status(),
                    outcome.reason(),
                    outcome.reviewId(),
                    command.workspaceId(),
                    command.itemId());
            if (outcome.status() != ReviewRequestStatus.REFUSED) return;
            // A comment delivered again finds its own review busy or recent: that review answers it.
            if (reviews.findByWorkspaceIdAndRequestKey(command.workspaceId(), key)
                    .isPresent()) return;
            refusal = refused(outcome.reason(), command.kind());
        } catch (AccessForbiddenException forbidden) {
            refused(command, forbidden);
            refusal = "only the author or an assignee of this " + work(command.kind())
                    + ", or a workspace admin, can ask for a review, and only from a " + provider(command.kind())
                    + " account that is linked to their Hephaestus account.";
        } catch (EntityNotFoundException | ItemCaptureException unreadable) {
            refused(command, unreadable);
            refusal = "it could not read this " + work(command.kind()) + ".";
        } catch (ReviewRequestRejected rejected) {
            refused(command, rejected);
            refusal = refused(rejected, command.kind());
        }
        if (answer != null)
            answer(answer, command.workspaceId(), command.itemId(), "Hephaestus did not start a review: " + refusal);
    }

    /**
     * A review that a comment requested answers that comment when it measured nothing, because then it sends no
     * feedback. One that measured something answers with its feedback.
     */
    @ApplicationModuleListener
    public void on(CommentRequestedReviewEnded ended) {
        var review = reviews.findByIdAndWorkspaceId(ended.reviewId(), ended.workspaceId())
                .orElse(null);
        if (review == null || review.getKind() != ReviewKind.ITEM) return;
        var stored = review.getChecks().path(ReviewServiceImpl.COMMENT_ANSWER);
        if (!stored.path("address").isString()) return;
        var counts = memberships.stateCounts(ended.workspaceId(), ended.reviewId());
        if (counts.getJudged() > 0) return;
        boolean kept = review.getStatus() == ReviewStatus.COMPLETE
                && counts.getReused() > 0
                && counts.getReused() == counts.getListed();
        var kind = IntegrationKind.valueOf(stored.path("kind").asString());
        answer(
                new ReviewRequest.CommentAnswer(kind, stored.path("address").asString()),
                ended.workspaceId(),
                review.getItemId(),
                kept
                        ? "Nothing that the practices read has changed since the last review, so Hephaestus did not"
                                + " review this " + work(kind) + " again. Its observations and feedback still stand."
                        : "Hephaestus could not review this " + work(kind) + " this time, so it has no new feedback."
                                + " The review in Hephaestus says why.");
    }

    private void answer(ReviewRequest.CommentAnswer answer, long workspaceId, long itemId, String text) {
        replies.stream()
                .filter(sink -> sink.kind() == answer.kind())
                .findFirst()
                .ifPresentOrElse(
                        sink -> sink.reply(workspaceId, itemId, answer.address(), text),
                        () -> log.warn("No way to answer a request comment: kind={}", answer.kind()));
    }

    private static void refused(BotCommandReceivedEvent command, RuntimeException reason) {
        log.warn(
                "Review command refused: workspaceId={}, itemId={}, reasonType={}",
                command.workspaceId(),
                command.itemId(),
                reason.getClass().getName());
    }

    /** The provider's own word for the work a command comment is on. */
    private static String work(IntegrationKind kind) {
        return kind == IntegrationKind.GITHUB ? "pull request" : "merge request";
    }

    private static String provider(IntegrationKind kind) {
        return kind == IntegrationKind.GITHUB ? "GitHub" : "GitLab";
    }

    private static String refused(@Nullable ReviewRequestReason reason, IntegrationKind kind) {
        if (reason == null) return "it could not be started.";
        return switch (reason) {
            case REQUEST_COOLDOWN_ACTIVE ->
                "a review of this " + work(kind) + " was requested a short time ago. Ask again later.";
            case REQUESTER_QUOTA_EXHAUSTED ->
                "you have asked for the most reviews allowed in one hour. Ask again later.";
            case CONCURRENT_DUPLICATE -> "a review of this " + work(kind) + " is already running.";
            case MEMBER_AI_DECLINED -> "your account does not take part in AI practice reviews in this workspace.";
            case WORKSPACE_INACTIVE -> "the workspace is not active.";
            case PRACTICES_DISABLED -> "practice review is turned off for this workspace.";
            case GATE_WITHHELD -> "requested reviews are turned off for this workspace.";
            default -> "it could not be started.";
        };
    }

    private static String refused(ReviewRequestRejected rejected, IntegrationKind kind) {
        return switch (rejected.reason()) {
            case WORKSPACE_INACTIVE -> "the workspace is not active.";
            case BUDGET_UNAVAILABLE -> "the workspace's review budget is used up.";
            case MODEL_UNAVAILABLE -> "no AI model is available for practice review.";
            case ITEM_INELIGIBLE ->
                rejected.eventReason() == null
                        ? "no practice reviews this " + work(kind) + "."
                        : refused(rejected.eventReason(), kind);
            case REQUEST_CONFLICT -> "a review of this " + work(kind) + " is already running.";
            default -> "it could not be started.";
        };
    }
}
