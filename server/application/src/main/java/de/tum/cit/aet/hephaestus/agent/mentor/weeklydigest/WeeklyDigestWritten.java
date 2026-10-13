package de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest;

import java.time.Instant;
import java.util.UUID;

/**
 * A weekly digest was written into a developer's Heph conversation. Published in the write's transaction; the
 * listener of the chosen {@link WeeklyDigestPointer} sends the note, after it checks again that the account still
 * wants it.
 *
 * @param weekDays the week's days as the note names them, such as "5–11 October"
 * @param requestedAt when the digest was written; a later opt-in does not revive this note
 */
public record WeeklyDigestWritten(
        UUID entryId,
        long workspaceId,
        long accountId,
        String weekDays,
        WeeklyDigestPointer pointer,
        Instant requestedAt) {}
