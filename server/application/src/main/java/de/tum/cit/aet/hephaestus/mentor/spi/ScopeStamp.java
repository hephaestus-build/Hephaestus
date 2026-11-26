package de.tum.cit.aet.hephaestus.mentor.spi;

/** The immutable scope of one admission. {@code MentorAuthorization} captures it and rechecks it live. For a shared Slack
 * request, {@code accountId} is the verified asker. */
public record ScopeStamp(
        long accountId, long accountGeneration, long readerEpoch, long consentEpoch, AuthorityScope authority) {}
