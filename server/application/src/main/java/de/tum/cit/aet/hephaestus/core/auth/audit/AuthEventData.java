package de.tum.cit.aet.hephaestus.core.auth.audit;

import org.jspecify.annotations.Nullable;

/**
 * Immutable carrier of an {@link AuthEvent}'s business fields — the parameter object that
 * keeps {@link AuthEvent#create} and {@link AuthEventWriter#write} under the 6-parameter
 * limit. Request-derived metadata (ip, user agent, id, timestamp) is supplied separately
 * by the writer.
 *
 * @param elevatedViaInstanceAdmin resolved by {@link AuthEventLogger.Draft#record()} from the
 *     request's security context; not something a caller may set
 */
public record AuthEventData(
        AuthEvent.EventType type,
        AuthEvent.Result result,
        @Nullable Long accountId,
        @Nullable Long actingAccountId,
        @Nullable String failureReason,
        @Nullable Long gitProviderId,
        @Nullable Long workspaceId,
        @Nullable Long identityLinkId,
        @Nullable String details,
        boolean elevatedViaInstanceAdmin) {}
