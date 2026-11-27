package de.tum.cit.aet.hephaestus.practices.spi;

/** Who may read advice. Only {@link #OWNER} is built; a read for {@link #TEAM} returns nothing. */
public enum AdviceAudience {
    OWNER,
    /** Reserved for team mentoring (ADR 0162). Not built and not readable. */
    TEAM
}
