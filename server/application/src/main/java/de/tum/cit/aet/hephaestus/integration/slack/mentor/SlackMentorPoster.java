package de.tum.cit.aet.hephaestus.integration.slack.mentor;

import com.slack.api.model.block.LayoutBlock;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackSendException;
import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import de.tum.cit.aet.hephaestus.mentor.MentorSlackDelivery;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Posts one Slack message for a ledger entry as intent, effect and outcome ({@link MentorSlackDelivery}), tagged with
 * {@link SlackMentorPosts#metadata}. A rate limit past the retry budget means that Slack stored nothing, because every
 * try got a 429, but it still leaves the entry {@code UNCERTAIN}: the conservative choice.
 */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackMentorPoster {

    private static final Logger log = LoggerFactory.getLogger(SlackMentorPoster.class);

    /** Silent Mode kept a Slack call from leaving the instance. */
    static final String SILENT_MODE = "silent_mode_engaged";

    /** Slack errors after which a post is uncertain: those that tell nothing about what Slack stored, and a 429. */
    private static final Set<String> UNKNOWN_EFFECT = Set.of(
            "transport_failure",
            "ratelimited",
            "internal_error",
            "fatal_error",
            "service_unavailable",
            "request_timeout");

    private final SlackMessageService slack;
    private final MentorSlackDelivery delivery;

    public SlackMentorPoster(SlackMessageService slack, MentorSlackDelivery delivery) {
        this.slack = slack;
        this.delivery = delivery;
    }

    /**
     * One message to post.
     *
     * @param threadTs the Slack thread to post in; {@code null} for the top level
     * @param text the notification and screen-reader text
     * @param shows the entry that the message shows ({@link MentorSlackDelivery#delivered})
     */
    public record Post(
            String channelId,
            @Nullable String threadTs,
            List<LayoutBlock> blocks,
            String text,
            @Nullable UUID shows) {}

    public enum Outcome {
        DELIVERED,
        /** Silent Mode kept the post from leaving the instance. */
        SILENCED,
        FAILED,
        /** Slack may have stored the post; the entry is {@code UNCERTAIN}. */
        UNKNOWN,
    }

    /** What a caller records with a post's outcome, in the transaction that stores the outcome. */
    @FunctionalInterface
    public interface Receipts {
        Receipts NONE = outcome -> {};

        void record(Outcome outcome);
    }

    /** @param channelId the conversation the post landed in, once it is known to be there */
    public record Result(Outcome outcome, @Nullable String channelId) {

        static Result of(Outcome outcome) {
            return new Result(outcome, null);
        }
    }

    /** Takes the intent and posts; empty when another caller took it first. */
    public Optional<Result> post(long workspaceId, UUID entryId, Post post) {
        if (!delivery.claim(workspaceId, entryId)) {
            return Optional.empty();
        }
        return Optional.of(send(workspaceId, entryId, post));
    }

    /** Posts for an entry whose intent the caller already holds. */
    public Result send(long workspaceId, UUID entryId, Post post) {
        return send(workspaceId, entryId, post, Receipts.NONE);
    }

    /** As {@link #send(long, UUID, Post)}, recording {@code receipts} with a settled outcome. */
    public Result send(long workspaceId, UUID entryId, Post post, Receipts receipts) {
        try {
            SlackMessageService.Posted posted = slack.postMessage(
                    workspaceId,
                    post.channelId(),
                    post.threadTs(),
                    post.blocks(),
                    post.text(),
                    SlackMentorPosts.metadata(entryId));
            delivery.delivered(
                    workspaceId, entryId, post.shows(), posted.ts(), () -> receipts.record(Outcome.DELIVERED));
            return new Result(Outcome.DELIVERED, posted.channelId());
        } catch (SlackSendException e) {
            return failed(workspaceId, entryId, e, receipts);
        }
    }

    /**
     * Settles an entry that the reconciler took and whose post may or may not have happened, by one read of the Slack
     * history since {@code oldestTs}: the post is there → delivered; it is not → failed, and it is not posted again,
     * because unknown visibility never authorizes another post. History that cannot be read, and a read page without
     * the post while Slack holds more, leave the entry uncertain.
     */
    public Result reconcile(long workspaceId, UUID entryId, Post post, String oldestTs) {
        return reconcile(workspaceId, entryId, post, oldestTs, Receipts.NONE);
    }

    /** As {@link #reconcile(long, UUID, Post, String)}, recording {@code receipts} with a settled outcome. */
    public Result reconcile(long workspaceId, UUID entryId, Post post, String oldestTs, Receipts receipts) {
        SlackMessageService.Matches matches;
        try {
            matches = slack.readWorkspaceMessages(
                    workspaceId,
                    post.channelId(),
                    post.threadTs(),
                    oldestTs,
                    message -> SlackMentorPosts.isPostOf(message, entryId));
        } catch (SlackSendException e) {
            log.warn(
                    "Slack history unreadable; Hephaestus post stays uncertain: workspaceId={}, entryId={}, error={}",
                    workspaceId,
                    entryId,
                    e.slackError());
            return Result.of(Outcome.UNKNOWN);
        }
        if (!matches.ts().isEmpty()) {
            delivery.delivered(
                    workspaceId,
                    entryId,
                    post.shows(),
                    matches.ts().getFirst(),
                    () -> receipts.record(Outcome.DELIVERED));
            return new Result(Outcome.DELIVERED, post.channelId());
        }
        if (matches.more()) {
            log.warn(
                    "Slack history has more pages; Hephaestus post stays uncertain: workspaceId={}, entryId={}",
                    workspaceId,
                    entryId);
            return Result.of(Outcome.UNKNOWN);
        }
        delivery.settle(workspaceId, entryId, MentorEntry.SlackDelivery.FAILED, () -> receipts.record(Outcome.FAILED));
        log.warn("Hephaestus Slack post is not in the Slack history: workspaceId={}, entryId={}", workspaceId, entryId);
        return Result.of(Outcome.FAILED);
    }

    /** Whether a failed post is uncertain ({@link #UNKNOWN_EFFECT}). */
    static boolean leavesEffectUnknown(SlackSendException e) {
        return e.isRateLimited() || UNKNOWN_EFFECT.contains(e.slackError());
    }

    private Result failed(long workspaceId, UUID entryId, SlackSendException e, Receipts receipts) {
        if (SILENT_MODE.equals(e.slackError())) {
            delivery.settle(
                    workspaceId, entryId, MentorEntry.SlackDelivery.FAILED, () -> receipts.record(Outcome.SILENCED));
            return Result.of(Outcome.SILENCED);
        }
        if (leavesEffectUnknown(e)) {
            delivery.markUncertain(workspaceId, entryId);
            log.warn(
                    "Hephaestus Slack post outcome unknown: workspaceId={}, entryId={}, error={}",
                    workspaceId,
                    entryId,
                    e.slackError());
            return Result.of(Outcome.UNKNOWN);
        }
        delivery.settle(workspaceId, entryId, MentorEntry.SlackDelivery.FAILED, () -> receipts.record(Outcome.FAILED));
        log.warn(
                "Hephaestus Slack post failed: workspaceId={}, entryId={}, error={}",
                workspaceId,
                entryId,
                e.slackError());
        return Result.of(Outcome.FAILED);
    }
}
