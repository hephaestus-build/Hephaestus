package de.tum.cit.aet.hephaestus.integration.chat.item;

import java.time.Instant;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

/** Chat-domain records supplied by the provider that owns the thread and its consent. */
public interface ThreadProjection {
    /** Streams all permitted messages from this workspace; no review history window applies. */
    void forEachWorkspaceMessage(long workspaceId, Consumer<ObjectNode> consumer);

    /** The message still passes the source owner's consent, participant and erasure gates. */
    boolean isMessageReadable(long workspaceId, String channelId, String messageTs);

    /**
     * The Slack threads the requesting developer participated in, from channels whose consent is
     * {@code ACTIVE}, non-tombstoned messages only. Wrapped in the untrusted-content quarantine envelope.
     *
     * @param developerId the conversation-chat requester's account id — the participant-firewall audience
     */
    ObjectNode buildPayload(long workspaceId, long developerId);

    /** The complete thread; authors remain distinct and no participant-specific verdict view is created. */
    ObjectNode threadPayload(long workspaceId, String channelId, String threadTs);

    /** Readability is separate from the payload so withholding never looks like an empty conversation. */
    ThreadReadability threadReadability(long workspaceId, String channelId, String threadTs);

    enum ThreadReadability {
        READABLE,
        CONSENT_NOT_ACTIVE,
        NOT_FOUND,
    }

    @Nullable
    Instant sourceEffectiveAt(@Nullable String sourceEventId);
}
