package de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The account-level weekly digest choice. Off by default; one switch covers every workspace. */
public interface WeeklyDigestSubscriptions {

    List<Long> subscribedAccountIds();

    /** Since when the account has the weekly digest on; empty while it is off. */
    Optional<Instant> enabledSince(long accountId);

    /** Turns the weekly digest off, as the Slack button and the email link do. */
    void turnOff(long accountId);
}
