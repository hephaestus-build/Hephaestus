package de.tum.cit.aet.hephaestus.integration.core.handler;

import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One provider delivery as the receiver verified and stored it: the subject it routes on, the headers the receiver
 * wrote, the payload bytes the provider signed, and when Hephaestus received it.
 */
@SuppressWarnings(
        "ArrayRecordComponent") // The payload is read once, by the handler that parses it; no caller compares events.
public record ProviderEvent(String subject, Map<String, String> headers, byte[] payload, Instant receivedAt) {

    /** Exact source namespace from the verified connection, retained after that connection is removed. */
    public static final String SOURCE_SERVER_URL = "Hephaestus-Source-Server-Url";

    public ProviderEvent {
        headers = Map.copyOf(headers);
    }

    /** A header the receiver wrote, or {@code null}. */
    public @Nullable String header(String name) {
        return headers.get(name);
    }
}
