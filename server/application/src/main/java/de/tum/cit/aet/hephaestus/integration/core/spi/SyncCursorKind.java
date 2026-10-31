package de.tum.cit.aet.hephaestus.integration.core.spi;

/**
 * The repository-scoped entity whose pagination cursor a {@link BackfillStateProvider}
 * persists, keyed by {@code syncTargetId}.
 *
 * <p>{@link BackfillStateProvider#updateSyncCursor} takes this kind and the implementer
 * switches on it to reach the correct persisted column — impl-internal column routing, not
 * vendor branching.
 */
public enum SyncCursorKind {
    ISSUE,
    /** Pull request / merge request. */
    PULL_REQUEST,
}
