package de.tum.cit.aet.hephaestus.practices.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Whether a practice is in use. A draft practice exists to be tried: no event, schedule, scan or request selects it,
 * and only workspace admins see it (ADR 0118 decision 7).
 */
@Schema(name = "PracticeState")
public enum PracticeState {
    ACTIVE,
    DRAFT
}
