package de.tum.cit.aet.hephaestus.agent.conversation;

import java.time.Instant;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

/**
 * Agent-owned SPI: projects the Slack channel-ingest substrate into the thread-grouped conversation payloads the
 * agent materialises into the sandbox context. Implemented by {@code integration.slack} (the owner of the
 * {@code slack_thread}/{@code slack_message}/{@code slack_monitored_channel} tables); the agent content sources
 * ({@code SlackConversationContentSource}, {@code ConversationThreadContentSource}) consume it and never touch
 * the Slack schema.
 *
 * <p>The implementation owns the privacy invariants: the participant firewall on the developer view, the
 * {@code consent_state = 'ACTIVE'} gate applied on the raw message read (so a channel paused/revoked between
 * enqueue and execution yields an empty payload), the non-tombstoned filter, and the untrusted-content
 * quarantine envelope.
 */
public interface ConversationThreadProjection {
    /** Streams all permitted messages from this workspace; no review history window applies. */
    void forEachWorkspaceMessage(long workspaceId, Consumer<ObjectNode> consumer);

    /** The message still passes the source owner's consent, participant and erasure gates. */
    boolean isMessageReadable(long workspaceId, String channelId, String messageTs);

    /**
     * The Slack threads the requesting developer participated in, from channels whose consent is
     * {@code ACTIVE}, non-tombstoned messages only. Wrapped in the untrusted-content quarantine envelope.
     *
     * @param developerId the mentor-chat requester's SCM user id — the participant-firewall audience
     */
    ObjectNode buildPayload(long workspaceId, long developerId);

    /**
     * The ordered-turns payload for a single settled thread under conversation review. No participant
     * firewall — the review judges the thread as reviewed work — but the same
     * {@code consent_state = 'ACTIVE'} gate and quarantine envelope apply.
     */
    ObjectNode buildThreadPayload(long workspaceId, String channelId, String threadTs);

    /**
     * Whether the thread exists and its channel's consent is {@code ACTIVE}.
     *
     * <p>{@link #buildThreadPayload} answers a withheld channel, a tombstoned thread, and a thread where
     * nobody spoke with the same empty payload. Reporting the first two as an empty conversation would
     * have the model judge a developer's communication on evidence it was never allowed to see.
     */
    default ThreadReadability threadReadability(long workspaceId, String channelId, String threadTs) {
        return ThreadReadability.READABLE;
    }

    enum ThreadReadability {
        READABLE,
        /** Consent is not ACTIVE; messages must not be read. */
        CONSENT_NOT_ACTIVE,
        NOT_FOUND,
    }

    default @Nullable Instant sourceEffectiveAt(@Nullable String sourceEventId) {
        return null;
    }
}
