package de.tum.cit.aet.hephaestus.integration.slack.mentor;

import com.slack.api.model.block.LayoutBlock;
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
        try {
            SlackMessageService.Posted posted = slack.postMessage(
                    workspaceId,
                    post.channelId(),
                    post.threadTs(),
                    post.blocks(),
                    post.text(),
                    SlackMentorPosts.metadata(entryId));
            delivery.delivered(workspaceId, entryId, post.shows(), posted.ts());
            return new Result(Outcome.DELIVERED, posted.channelId());
        } catch (SlackSendException e) {
            return failed(workspaceId, entryId, e);
        }
    }

    /**
     * Settles an entry that the reconciler took and whose post may or may not have happened: the post is in the Slack
     * history since {@code oldestTs} → delivered; not there → posted again. Slack history that cannot be read leaves
     * the entry uncertain.
     */
    public Result reconcile(long workspaceId, UUID entryId, Post post, String oldestTs) {
        Optional<String> found;
        try {
            found = slack
                    .findWorkspaceMessages(
                            workspaceId,
                            post.channelId(),
                            post.threadTs(),
                            oldestTs,
                            message -> SlackMentorPosts.isPostOf(message, entryId))
                    .stream()
                    .findFirst();
        } catch (SlackSendException e) {
            log.warn(
                    "Slack history unreadable; Heph post stays uncertain: workspaceId={}, entryId={}, error={}",
                    workspaceId,
                    entryId,
                    e.slackError());
            return Result.of(Outcome.UNKNOWN);
        }
        if (found.isPresent()) {
            delivery.delivered(workspaceId, entryId, post.shows(), found.get());
            return new Result(Outcome.DELIVERED, post.channelId());
        }
        return send(workspaceId, entryId, post);
    }

    /** Whether a failed post is uncertain ({@link #UNKNOWN_EFFECT}). */
    static boolean leavesEffectUnknown(SlackSendException e) {
        return e.isRateLimited() || UNKNOWN_EFFECT.contains(e.slackError());
    }

    private Result failed(long workspaceId, UUID entryId, SlackSendException e) {
        if (SILENT_MODE.equals(e.slackError())) {
            delivery.settle(workspaceId, entryId, MentorEntry.SlackDelivery.FAILED);
            return Result.of(Outcome.SILENCED);
        }
        if (leavesEffectUnknown(e)) {
            delivery.markUncertain(workspaceId, entryId);
            log.warn(
                    "Heph Slack post outcome unknown: workspaceId={}, entryId={}, error={}",
                    workspaceId,
                    entryId,
                    e.slackError());
            return Result.of(Outcome.UNKNOWN);
        }
        delivery.settle(workspaceId, entryId, MentorEntry.SlackDelivery.FAILED);
        log.warn("Heph Slack post failed: workspaceId={}, entryId={}, error={}", workspaceId, entryId, e.slackError());
        return Result.of(Outcome.FAILED);
    }
}
