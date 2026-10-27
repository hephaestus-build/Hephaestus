package de.tum.cit.aet.hephaestus.integration.scm.events;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.LazyInitializationException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The event actor initiated the action and need not be the developer whose work is reviewed. */
public record EventContext(
        @NonNull UUID eventId,
        @NonNull Instant occurredAt,
        @Nullable Long scopeId,
        @Nullable RepositoryRef repository,
        @NonNull DataSource source,
        @Nullable String webhookAction,
        @NonNull String correlationId,
        @Nullable IdentityProviderType providerType,
        @Nullable Long actorUserId,
        @Nullable String sourceKey) {
    private static final Logger log = LoggerFactory.getLogger(EventContext.class);

    public EventContext(
            @NonNull UUID eventId,
            @NonNull Instant occurredAt,
            @Nullable Long scopeId,
            @Nullable RepositoryRef repository,
            @NonNull DataSource source,
            @Nullable String webhookAction,
            @NonNull String correlationId,
            @Nullable IdentityProviderType providerType,
            @Nullable Long actorUserId) {
        this(
                eventId,
                occurredAt,
                scopeId,
                repository,
                source,
                webhookAction,
                correlationId,
                providerType,
                actorUserId,
                null);
    }

    public EventContext(
            @NonNull UUID eventId,
            @NonNull Instant occurredAt,
            @Nullable Long scopeId,
            @Nullable RepositoryRef repository,
            @NonNull DataSource source,
            @Nullable String webhookAction,
            @NonNull String correlationId,
            @Nullable IdentityProviderType providerType) {
        this(eventId, occurredAt, scopeId, repository, source, webhookAction, correlationId, providerType, null);
    }

    public static EventContext from(ProcessingContext ctx) {
        // ProcessingContext.provider() may hold a detached JPA proxy when the
        // surrounding transaction has already committed. Best-effort eager-resolve;
        // fall back to a null provider type rather than propagating the proxy
        // (which would explode again the next time a downstream listener touched it).
        IdentityProviderType resolvedType = null;
        if (ctx.provider() != null) {
            try {
                resolvedType = ctx.provider().getType();
            } catch (LazyInitializationException e) {
                log.debug(
                        "Could not resolve provider type from detached proxy, correlationId={}: {}",
                        ctx.correlationId(),
                        e.getClass().getSimpleName());
            }
        }
        return new EventContext(
                UUID.randomUUID(),
                ctx.observedAt(),
                ctx.scopeId(),
                ctx.repository() != null ? RepositoryRef.from(ctx.repository()) : null,
                ctx.source(),
                ctx.webhookAction(),
                ctx.correlationId(),
                resolvedType,
                ctx.actorUserId());
    }

    public static EventContext forSync(
            @Nullable Long scopeId,
            @Nullable RepositoryRef repository,
            @Nullable IdentityProviderType providerType,
            Instant occurredAt) {
        return new EventContext(
                UUID.randomUUID(),
                occurredAt,
                scopeId,
                repository,
                DataSource.GRAPHQL_SYNC,
                null,
                UUID.randomUUID().toString(),
                providerType);
    }

    /**
     * This context for the provider event {@code sourceKey} names: its own identity, such as the action and the
     * payload's {@code updated_at}. An occasion recorded under it is recorded once, however often the event arrives.
     */
    public EventContext withSourceKey(@Nullable String sourceKey) {
        return new EventContext(
                eventId,
                occurredAt,
                scopeId,
                repository,
                source,
                webhookAction,
                correlationId,
                providerType,
                actorUserId,
                sourceKey);
    }

    public boolean isWebhook() {
        return source == DataSource.WEBHOOK;
    }

    public boolean isSync() {
        return source == DataSource.GRAPHQL_SYNC || source == DataSource.REST_SYNC;
    }

    public boolean isGitHub() {
        return providerType == IdentityProviderType.GITHUB;
    }

    public boolean isGitLab() {
        return providerType == IdentityProviderType.GITLAB;
    }
}
