package de.tum.cit.aet.hephaestus.agent.run;

import java.util.Objects;
import java.util.UUID;

/**
 * What a conversation run answers: the account that owns the conversation, and the ledger position of the question or
 * digest entry, which is the conversation and the entry's {@code seq}. The caller that queues the run knows all three
 * from the conversation that it holds locked.
 */
public record AgentRunStimulus(long accountId, UUID conversationId, long subjectSeq) {

    public AgentRunStimulus {
        if (accountId <= 0) throw new IllegalArgumentException("A conversation run has an owner");
        Objects.requireNonNull(conversationId, "A conversation run answers an entry of a conversation");
        if (subjectSeq <= 0) throw new IllegalArgumentException("A ledger position starts at 1");
    }
}
