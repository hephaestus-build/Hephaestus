package de.tum.cit.aet.hephaestus.mentor.spi;

import de.tum.cit.aet.hephaestus.mentor.MentorLeaseLostException;
import java.util.UUID;

/**
 * The run that leases a turn's work: who may drive the turn now, under which epoch, and until when. The ledger owns
 * the turn; the agent runtime owns its run and implements this port. Every call runs in the caller's transaction,
 * after the caller locked the turn, so the turn and its run move together.
 */
public interface TurnRuns {

    /** Queues the run of a new turn of the conversation, and returns its id. */
    UUID enqueue(long workspaceId, UUID conversationId);

    /**
     * Holds the run under {@code epoch} until the caller's transaction ends.
     *
     * @throws MentorLeaseLostException when that claim no longer holds the run
     */
    void requireCurrent(long workspaceId, UUID turnId, UUID runId, int epoch);

    /** Ends the run of a turn whose holder ended it under {@code epoch}; the caller checked the claim before. */
    void ended(long workspaceId, UUID runId, int epoch);

    /**
     * A queued turn stopped: its run ends, since nothing ran. A running turn stopped: its run may still be renewed for
     * a grace period, so the holder can end the turn with what it wrote, and then the server ends it.
     */
    void stopped(long workspaceId, UUID runId, boolean wasRunning);

    /**
     * Moves the queued runs of one conversation's turns to another conversation, whose turns they become: they then
     * wait behind its turns. A stopped turn whose holder may still end it keeps its run where it is.
     */
    void moveQueued(long workspaceId, UUID fromConversationId, UUID toConversationId);

    /** Returns a running turn's run to the queue without spending a retry, for the turn to start again. */
    void requeue(long workspaceId, UUID turnId, UUID runId, int epoch);
}
