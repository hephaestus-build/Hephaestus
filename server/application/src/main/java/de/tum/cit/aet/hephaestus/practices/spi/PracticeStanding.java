package de.tum.cit.aet.hephaestus.practices.spi;

import io.swagger.v3.oas.annotations.media.Schema;

// The DTO that carries it already publishes this class name.
@Schema(name = "Standing")
public enum PracticeStanding {
    DEVELOPING,
    STRENGTH,
    MIXED,
    NOT_OBSERVED,
    NO_OPPORTUNITY;
}
