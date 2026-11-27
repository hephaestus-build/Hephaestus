package de.tum.cit.aet.hephaestus.mentor.spi;

/**
 * Whether an answer's evidence basis is still current for its audience and scope. Database only, in the caller's
 * transaction; it projects no text. It takes the guard locks that the writers of its source revisions take, and holds
 * them until the caller's transaction ends: a snapshot read proves nothing at the write. An unwired basis denies.
 */
public interface MentorEvidenceBasis {
    boolean isCurrent(BasisRef basis, Audience audience, ScopeStamp scope, ProviderObservation observed);
}
