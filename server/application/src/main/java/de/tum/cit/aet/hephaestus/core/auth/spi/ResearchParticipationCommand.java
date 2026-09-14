package de.tum.cit.aet.hephaestus.core.auth.spi;

/** Research consent for a verified SCM actor, independent of the transport carrying the decision. */
public interface ResearchParticipationCommand {
    /** Unknown actors are a no-op; opting out also records the consent audit event. */
    void setForUserId(long userId, boolean participate, ConsentSource source);
}
