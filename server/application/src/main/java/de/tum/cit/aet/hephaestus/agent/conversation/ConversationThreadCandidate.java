package de.tum.cit.aet.hephaestus.agent.conversation;

import org.jspecify.annotations.Nullable;

/**
 * One settled-thread candidate surfaced by {@link ConversationCandidateSource} for the conversation review scheduler.
 * {@code participantMemberIds} is the resolved {@code bigint[]} of workspace member ids the review jobs are filed
 * against; {@code lastReviewedTs} is the growth watermark (null before the first review).
 */
public record ConversationThreadCandidate(
        long workspaceId,
        long threadId,
        String channelId,
        @Nullable String channelName,
        String threadTs,
        String lastTs,
        @Nullable String lastReviewedTs,
        long[] participantMemberIds) {}
