package de.tum.cit.aet.hephaestus.practices.spi;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Where the review stands.")
public enum ReviewStatus {
    QUEUED,
    RUNNING,
    COMPLETE,
    PARTIAL,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETE || this == PARTIAL || this == FAILED || this == CANCELLED;
    }
}
