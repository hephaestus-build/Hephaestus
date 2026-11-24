package de.tum.cit.aet.hephaestus.core.settings.spi;

import io.swagger.v3.oas.annotations.media.Schema;

/** The figures of one released period of the public usage page (ADR 0153 decision 2). There are no others. */
@Schema(name = "UsageFigure")
public enum UsageFigure {
    WORKSPACES,
    REVIEWED_PEOPLE,
    REVIEWED_WORK,
    PRACTICE_REVIEWS,
    FEEDBACK_DELIVERED,
}
