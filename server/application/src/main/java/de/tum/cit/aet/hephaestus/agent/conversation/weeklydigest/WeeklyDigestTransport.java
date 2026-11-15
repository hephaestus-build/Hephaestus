package de.tum.cit.aet.hephaestus.agent.conversation.weeklydigest;

/** A transport that can carry the note that a weekly digest is ready. */
public interface WeeklyDigestTransport {

    WeeklyDigestPointer pointer();

    /** Whether the note can reach the account through this transport now. */
    boolean reaches(long workspaceId, long accountId);
}
