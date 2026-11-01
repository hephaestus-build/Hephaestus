package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import de.tum.cit.aet.hephaestus.agent.gateway.ConversationLoopPrincipal;
import java.util.Objects;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * A verified gateway caller: a review job attempt, a conversation turn attempt, or the release's conversation loop.
 * Contains no bearer credential or frozen configuration.
 *
 * @param loop the release's conversation loop, which reaches only the conversation assignments
 */
record ProxyIdentity(
        @Nullable AttemptJwt job,
        @Nullable AttemptJwt turn,
        @Nullable ConversationLoopPrincipal loop) {
    ProxyIdentity {
        if (Stream.of(job, turn, loop).filter(Objects::nonNull).count() != 1)
            throw new IllegalArgumentException("Exactly one proxy identity is required");
    }

    static ProxyIdentity of(AttemptJwt attempt) {
        return switch (attempt.kind()) {
            case REVIEW -> new ProxyIdentity(attempt, null, null);
            case TURN -> new ProxyIdentity(null, attempt, null);
        };
    }

    static ProxyIdentity conversationLoop() {
        return new ProxyIdentity(null, null, new ConversationLoopPrincipal());
    }

    /** Names the caller for logs and rate limits, never with its credential. */
    String description() {
        if (job != null) return "job:" + job.id();
        if (turn != null) return "mentor-turn:" + turn.id();
        return "conversation-loop";
    }
}
