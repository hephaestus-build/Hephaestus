package de.tum.cit.aet.hephaestus.integration.core.handler;

import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;

/**
 * Unified, vendor-neutral message-handler contract.
 *
 * <p>This is the SPI side of the {@code IntegrationMessageHandlerRegistry} — one handler
 * per {@link EventTypeKey}. Concrete implementations live under
 * {@code integration/<kind>/...} and almost always extend
 * {@link AbstractIntegrationMessageHandler}, which folds in the reusable concerns
 * (subject-suffix validation, Jackson deserialization, transaction wrapping). The bare
 * interface is the contract; the abstract base is the convenience.
 *
 * <p>The contract intentionally stays minimal:
 * <ul>
 *   <li>{@link #key()} — the registry index; one key maps to at most one handler.</li>
 *   <li>{@link #onMessage(ProviderEvent)} — the entry point for one stored delivery.</li>
 * </ul>
 *
 * <p><b>Threading.</b> {@link #onMessage(ProviderEvent)} runs on the consumer thread that claimed the event;
 * implementations must be safe to call concurrently for distinct keys but may assume serial delivery per
 * workspace and subject scope, which the consumer's claim enforces.
 *
 * <p><b>Error handling.</b> Throwing from {@code onMessage} surfaces the error to the
 * consumer dispatcher, which decides between ACK, NACK, and dead-lettering. Handlers
 * should NOT swallow exceptions silently.
 */
public interface IntegrationMessageHandler {
    /**
     * @return the registry index for this handler; must be stable across the bean's
     *     lifetime and unique within the application context.
     */
    EventTypeKey key();

    /**
     * Process one stored delivery. The dispatcher guarantees the event's subject was routed via the per-kind
     * {@code SubjectParser} that produced this handler's {@link #key()}, so implementations may skip re-validating
     * the subject prefix.
     */
    void onMessage(ProviderEvent event);

    /**
     * Whether this handler is currently active. A handler may declare itself disabled —
     * e.g. when its feature flag is off — in which case the dispatcher treats it as if no
     * handler were registered for the key (ACK and skip the message). Defaults to
     * {@code true}; override to consult a runtime toggle.
     *
     * @return {@code true} if the handler should process messages; {@code false} to be
     *     treated as no handler.
     */
    default boolean isEnabled() {
        return true;
    }
}
