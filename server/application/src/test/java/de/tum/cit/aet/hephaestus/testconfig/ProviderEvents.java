package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.integration.core.handler.ProviderEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

/** Stored provider deliveries for handler tests. */
public final class ProviderEvents {
    private ProviderEvents() {}

    public static ProviderEvent event(String subject, byte[] payload) {
        return event(subject, payload, Map.of());
    }

    public static ProviderEvent event(String subject, String payload) {
        return event(subject, payload.getBytes(StandardCharsets.UTF_8), Map.of());
    }

    public static ProviderEvent event(String subject, byte[] payload, Map<String, String> headers) {
        return new ProviderEvent(subject, headers, payload, Instant.now());
    }
}
