package de.tum.cit.aet.hephaestus.integration.chat.item;

import java.util.List;
import org.jspecify.annotations.Nullable;

public record ThreadCandidate(
        long workspaceId,
        long threadId,
        String channelId,
        @Nullable String channelName,
        String threadTs,
        String lastTs,
        @Nullable String lastReviewedTs,
        List<Long> participantAccountIds) {
    public ThreadCandidate {
        participantAccountIds = List.copyOf(participantAccountIds);
    }
}
