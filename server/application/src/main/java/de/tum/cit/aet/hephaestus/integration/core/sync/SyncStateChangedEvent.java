package de.tum.cit.aet.hephaestus.integration.core.sync;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;

/** A committed sync-state invalidation consumed by the SSE push layer. */
public record SyncStateChangedEvent(long workspaceId, long connectionId, IntegrationKind kind, Scope scope) {
    public enum Scope {
        JOB,
        RESOURCES,
        CONNECTION,
        ACTIVITY;

        /** Stable lowercase token carried on the wire ({@code SyncEventHintDTO.scope()}); the frontend keys off it. */
        public String wireValue() {
            return switch (this) {
                case JOB -> "job";
                case RESOURCES -> "resources";
                case CONNECTION -> "connection";
                case ACTIVITY -> "activity";
            };
        }
    }
}
