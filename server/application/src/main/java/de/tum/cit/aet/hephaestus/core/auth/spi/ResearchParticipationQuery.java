package de.tum.cit.aet.hephaestus.core.auth.spi;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Read side of research consent for modules that collect research data: who runs the study on this
 * instance, and whether an account has agreed to take part in it right now. {@code ConsentService}
 * defines what "right now" means.
 */
public interface ResearchParticipationQuery {
    /** The organisation running the study, or empty when this instance runs none. */
    Optional<String> researchOrganization();

    boolean participates(long accountId);

    /** The wording currently shown for the research scope, or empty when this instance runs no study. */
    Optional<ResearchQuestion> currentQuestion();

    /**
     * The decision that authorises research for each given account right now, keyed by account. An
     * account is absent unless its latest research decision covers the current scope and the
     * account is active. One read for the whole collection; joins the caller's transaction.
     */
    Map<Long, Long> currentGrants(Collection<Long> accountIds);

    /**
     * {@link #currentGrants} after share-locking the accounts in id order, the lock every consent
     * write and account purge takes first. Until the caller's transaction ends, no decision for these
     * accounts can be recorded and none of them can be erased. Requires an open read-write
     * transaction; keep it short.
     */
    Map<Long, Long> lockCurrentGrants(Collection<Long> accountIds);

    /**
     * How each earlier grant stands today, keyed by account. The value of {@code grantDecisionIds} is
     * the exact grant row that once authorised a use. Accounts with no ledger link left (erased) are
     * reported as {@link GrantStanding#ACCOUNT_ERASED}.
     */
    Map<Long, GrantStanding> grantStandings(Map<Long, Long> grantDecisionIds);

    record ResearchQuestion(String noticeVersion, String organization) {}

    enum GrantStanding {
        /** The grant row is still the latest decision, covers the current scope, and belongs to an active account. */
        CURRENT,
        /** A refusal was recorded after the grant. A later grant does not undo the earlier withdrawal. */
        WITHDRAWN,
        /** A different decision is now the latest, with no refusal in between. */
        SUPERSEDED,
        /** The grant row is still the latest, but its scope or organisation no longer covers the research. */
        QUESTION_CHANGED,
        /** The account is being erased or was erased. */
        ACCOUNT_ERASED,
        /** The account is suspended. Its earlier grant stands, but no new research use may start. */
        ACCOUNT_INACTIVE,
    }
}
