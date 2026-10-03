package de.tum.cit.aet.hephaestus.integration.core.sync.push;

/**
 * Wire payload for one {@code sync} SSE event — an invalidation hint, not data. Clients refetch the
 * relevant REST resource rather than trusting a carried DTO, so this record stays
 * intentionally thin and vendor/DTO-shape agnostic.
 *
 * @param scope the changed scope as a wire token — {@code "job"}, {@code "resources"}, {@code "connection"},
 *              or {@code "activity"} (see {@code SyncStateChangedEvent.Scope#wireValue()}). All four are
 *              emitted today: {@code ConnectionActivityRecorder} publishes {@code "activity"} on every
 *              processed webhook event, the others on job and resource state changes.
 * @param connectionId the connection this hint concerns — the only field the client keys its
 *                      query-invalidation off (alongside {@code scope}); no vendor/kind is carried
 *                      because the hint is an invalidation trigger, not data.
 */
public record SyncEventHint(String scope, Long connectionId) {}
