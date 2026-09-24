package de.tum.cit.aet.hephaestus.core.auth.spi;

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
}
