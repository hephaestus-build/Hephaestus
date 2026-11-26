package de.tum.cit.aet.hephaestus.mentor.spi;

/** Why an effect may not render or write now. */
public enum ProofRefusal {
    STOPPED,
    SUPERSEDED,
    VERSION_STALE,
    AUTHORIZATION_CHANGED,
    BASIS_INVALID,
    DEADLINE_PASSED,
    READERS_UNPROVEN,
    OBSERVATION_MISMATCH,
    OBSERVATION_STALE,
    SUPPRESSED,
    HELD,
    NOTICE_UNCONFIRMED
}
