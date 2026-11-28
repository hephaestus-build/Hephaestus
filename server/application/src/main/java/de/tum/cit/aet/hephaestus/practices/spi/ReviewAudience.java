package de.tum.cit.aet.hephaestus.practices.spi;

import io.swagger.v3.oas.annotations.media.Schema;

/** Who reads a review (ADR 0167 decision 3). The server sets it once, when it creates the review. */
// ReviewAudienceDTO is the audience; this enum is its kind.
@Schema(name = "ReviewAudienceKind")
public enum ReviewAudience {
    /** The workspace admins and owners, as for a scan. */
    OPERATIONAL,
    /** The current members of one team project. */
    TEAM,
    /** The readers of one person's work. */
    PERSONAL;

    /**
     * The audience of a review that names none: a review created before reviews stored one, and a new review that no
     * setup starts. A scan or a trial is for admins, and any other review is about a person's work. It is never
     * {@link #TEAM}.
     */
    public static ReviewAudience ofOlder(ReviewKind kind, ReviewOrigin origin) {
        return kind == ReviewKind.SCAN || origin == ReviewOrigin.TRIAL ? OPERATIONAL : PERSONAL;
    }
}
