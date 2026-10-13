package de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest;

/**
 * Where the one note that a weekly digest is ready goes, chosen once when the digest is written: the first, in
 * declaration order, whose transport reaches the developer. There is no fallback after a failure: the digest is
 * always in the app.
 */
public enum WeeklyDigestPointer {
    /** A top-level message in the developer's Slack DM with Heph. */
    SLACK,
    /** An email. */
    EMAIL,
    /** No note: the digest is only in the conversation. */
    NONE,
}
