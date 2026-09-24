package de.tum.cit.aet.hephaestus.integration.scm.domain.common;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Unified context for processing git provider data from any source (sync or webhook).
 * <p>
 * This context carries all the information needed to process data consistently,
 * regardless of whether it came from a scheduled GraphQL sync or a webhook
 * event.
 *
 * <h2>Transaction Requirements</h2>
 * <p>
 * <b>Important:</b> The {@code repository} field contains a JPA entity reference.
 * This context MUST only be used within an active transaction/session. Accessing
 * lazy-loaded relationships outside a transaction will cause
 * {@code LazyInitializationException}.
 * <p>
 * For event publishing across transaction boundaries, use
 * {@link de.tum.cit.aet.hephaestus.integration.core.events.EventContext#from(ProcessingContext)}
 * to create an immutable, serializable context that is safe for async handling.
 *
 * <h2>Future ETL Extraction</h2>
 * <p>
 * When extracting integration.scm as a standalone ETL component, this record should
 * be refactored to remove the JPA entity dependency. Consider:
 * <ul>
 *   <li>Replace {@code Repository} with a {@code RepositoryRef} or ID reference</li>
 *   <li>Create separate contexts for sync vs. event publishing</li>
 * </ul>
 *
 * @param scopeId        The scope this data belongs to
 * @param repository     The repository being processed (JPA entity - transaction required)
 * @param provider       The git provider instance (e.g., github.com, gitlab.lrz.de)
 * @param startedAt      When processing started
 * @param correlationId  Unique ID for distributed tracing - correlates all log
 *                       entries and events from a single webhook or sync operation
 * @param webhookAction  The webhook action (e.g. "opened", "closed") if from webhook
 * @param source         Whether data came from sync or webhook
 * @param actorUserId    Local user ID of the webhook sender, not the issue author; absent for sync
 */
public record ProcessingContext(
        @Nullable Long scopeId,
        @Nullable Repository repository,
        @Nullable IdentityProvider provider,
        Instant startedAt,
        String correlationId,
        @Nullable String webhookAction,
        DataSource source,
        @Nullable Long actorUserId) {
    public ProcessingContext(
            @Nullable Long scopeId,
            @Nullable Repository repository,
            @Nullable IdentityProvider provider,
            Instant startedAt,
            String correlationId,
            @Nullable String webhookAction,
            DataSource source) {
        this(scopeId, repository, provider, startedAt, correlationId, webhookAction, source, null);
    }

    /** The webhook sender is not necessarily the developer whose work is reviewed. */
    public ProcessingContext withActorUserId(@Nullable Long actorUserId) {
        return new ProcessingContext(
                scopeId, repository, provider, startedAt, correlationId, webhookAction, source, actorUserId);
    }
    /**
     * Returns the provider's database ID for use in upsert queries.
     */
    public @Nullable Long providerId() {
        return provider != null ? provider.getId() : null;
    }

    /**
     * Creates a context for scheduled sync operations.
     */
    public static ProcessingContext forSync(@Nullable Long scopeId, @Nullable Repository repository) {
        return new ProcessingContext(
                scopeId,
                repository,
                repository != null ? repository.getProvider() : null,
                Instant.now(),
                UUID.randomUUID().toString(),
                null,
                DataSource.GRAPHQL_SYNC);
    }

    /**
     * Creates a context for sync operations that are not repository-scoped
     * (e.g., organization-level project sync, team sync).
     */
    public static ProcessingContext forSync(@Nullable Long scopeId, IdentityProvider provider) {
        return new ProcessingContext(
                scopeId, null, provider, Instant.now(), UUID.randomUUID().toString(), null, DataSource.GRAPHQL_SYNC);
    }

    /**
     * Creates a context for webhook event processing.
     */
    public static ProcessingContext forWebhook(@Nullable Long scopeId, Repository repository, String action) {
        return new ProcessingContext(
                scopeId,
                repository,
                repository != null ? repository.getProvider() : null,
                Instant.now(),
                UUID.randomUUID().toString(),
                action,
                DataSource.WEBHOOK);
    }

    /**
     * Creates a context for webhook events that are not repository-scoped
     * (e.g., organization-level project events).
     */
    public static ProcessingContext forWebhook(@Nullable Long scopeId, IdentityProvider provider, String action) {
        return new ProcessingContext(
                scopeId, null, provider, Instant.now(), UUID.randomUUID().toString(), action, DataSource.WEBHOOK);
    }

    /**
     * Checks if this processing was triggered by a webhook.
     */
    public boolean isWebhook() {
        return source == DataSource.WEBHOOK;
    }

    /**
     * Checks if this processing was triggered by a scheduled sync.
     */
    public boolean isSync() {
        return source == DataSource.GRAPHQL_SYNC;
    }
}
