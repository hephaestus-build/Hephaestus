package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import de.tum.cit.aet.hephaestus.mentor.ThreadSurface;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One mentor turn, transport-neutral. Carries only what a turn needs — the developer is resolved from the
 * security context inside the turn body, not from here. {@code surface} lets the shared turn body adapt
 * output style per client (web vs Slack DM) without any transport branching.
 */
public record MentorTurnRequest(
        long workspaceId,
        @NonNull UUID threadId,
        @NonNull String userMessage,
        @Nullable UUID clientUserMessageId,
        @NonNull ThreadSurface surface,
        @Nullable UUID retryOfAssistantMessageId) {
    /** A turn from the webapp SSE surface; {@code retryOfAssistantMessageId} names a failed reply to answer again. */
    public static MentorTurnRequest web(
            long workspaceId,
            UUID threadId,
            String userMessage,
            @Nullable UUID clientUserMessageId,
            @Nullable UUID retryOfAssistantMessageId) {
        return new MentorTurnRequest(
                workspaceId, threadId, userMessage, clientUserMessageId, ThreadSurface.WEB, retryOfAssistantMessageId);
    }

    /** A turn from a Slack DM surface. Lets callers construct one without naming the {@code mentor}-module enum. */
    public static MentorTurnRequest slackDm(
            long workspaceId, UUID threadId, String userMessage, @Nullable UUID clientUserMessageId) {
        return new MentorTurnRequest(
                workspaceId, threadId, userMessage, clientUserMessageId, ThreadSurface.SLACK_DM, null);
    }

    MentorTurnRequest withUserMessage(String storedPrompt) {
        return new MentorTurnRequest(
                workspaceId, threadId, storedPrompt, clientUserMessageId, surface, retryOfAssistantMessageId);
    }
}
