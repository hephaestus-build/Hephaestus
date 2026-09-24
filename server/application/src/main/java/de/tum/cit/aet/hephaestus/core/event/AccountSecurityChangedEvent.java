package de.tum.cit.aet.hephaestus.core.event;

import java.time.Instant;

/** A committed security-relevant account change; contact details are resolved only at delivery. */
public record AccountSecurityChangedEvent(long accountId, Kind kind, Instant occurredAt) {
    public enum Kind {
        IDENTITY_LINKED,
        IDENTITY_UNLINKED,
        APP_ROLE_CHANGED
    }
}
