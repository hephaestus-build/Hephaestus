package de.tum.cit.aet.hephaestus.practices.usage;

import io.swagger.v3.oas.annotations.media.Schema;

/** Whether a released period of the public usage page shows its figures. */
@Schema(name = "PublicUsageStatus")
public enum PublicUsageStatus {
    /** The period passed the floors. Each figure still shows only if it passed its own rules. */
    PUBLISHED,
    /** Fewer than 5 workspaces or fewer than 5 developers: the period publishes no figure. */
    NOT_ENOUGH_ACTIVITY,
    /** An instance admin withdrew the period, or its stored row cannot be read. */
    NOT_PUBLISHED,
}
