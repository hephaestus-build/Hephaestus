package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.Collection;

/** Erase derived conversation rows within the workspace; select threads by source id and people by developer id. */
public interface ConversationFeedbackErasure {
    int eraseForThreads(long workspaceId, Collection<Long> slackThreadIds);

    /**
     * As {@link #eraseForThreads}, when the threads reach the end of their retention. Advice from their results goes
     * stale as a retention, which keeps the answers that read it.
     */
    int expireForThreads(long workspaceId, Collection<Long> slackThreadIds);

    int eraseAllConversationForWorkspace(long workspaceId);

    int eraseConversationFeedbackAboutUser(long workspaceId, long aboutUserId);
}
