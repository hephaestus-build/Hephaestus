package de.tum.cit.aet.hephaestus.integration.core.handler;

import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Cross-vendor message handler registry, indexed by {@link EventTypeKey}. The unified
 * resolution surface for the integration NATS consumer fleet.
 *
 * <p>Constructor-injects every {@link IntegrationMessageHandler} bean and builds an
 * immutable map. Duplicate keys are a fatal {@link IllegalStateException} naming both
 * offending bean classes — routing must be unambiguous, so we fail at boot rather than
 * shadow one handler with another.
 */
@Component
public class IntegrationMessageHandlerRegistry {

    private static final Logger log = LoggerFactory.getLogger(IntegrationMessageHandlerRegistry.class);

    private final Map<EventTypeKey, IntegrationMessageHandler> handlers;

    public IntegrationMessageHandlerRegistry(List<IntegrationMessageHandler> all) {
        Map<EventTypeKey, IntegrationMessageHandler> map = new HashMap<>(all.size() * 2);
        for (IntegrationMessageHandler handler : all) {
            EventTypeKey key = handler.key();
            if (key == null) {
                throw new IllegalStateException(
                        handler.getClass().getName() + " returned null from key() — every handler must declare a key");
            }
            IntegrationMessageHandler previous = map.putIfAbsent(key, handler);
            if (previous != null) {
                throw new IllegalStateException("Duplicate IntegrationMessageHandler for key " + key
                        + ": "
                        + previous.getClass().getName()
                        + " conflicts with "
                        + handler.getClass().getName());
            }
        }
        this.handlers = Collections.unmodifiableMap(map);
        log.info("Registered {} IntegrationMessageHandler bean(s) in unified registry", handlers.size());
    }

    /**
     * Resolve the handler bound to the given key.
     */
    public Optional<IntegrationMessageHandler> resolve(@Nullable EventTypeKey key) {
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(handlers.get(key));
    }

    /**
     * @return the number of registered handlers; useful for metrics and startup
     *     diagnostics. Stable for the lifetime of the bean.
     */
    public int handlerCount() {
        return handlers.size();
    }
}
