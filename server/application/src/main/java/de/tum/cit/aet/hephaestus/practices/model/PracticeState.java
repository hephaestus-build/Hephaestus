package de.tum.cit.aet.hephaestus.practices.model;

import de.tum.cit.aet.hephaestus.core.Lifecycle;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Whether a practice is in use. A draft practice exists to be tried: no event, schedule, scan or request selects it,
 * and only workspace admins see it (ADR 0118 decision 7). A draft can be put in use; a practice in use never becomes a
 * draft again, because it can have observations.
 */
@Schema(name = "PracticeState")
public enum PracticeState implements Lifecycle<PracticeState> {
    ACTIVE,
    DRAFT;

    @Override
    public boolean canTransitionTo(PracticeState next) {
        return this == DRAFT && next == ACTIVE;
    }
}
