package de.tum.cit.aet.hephaestus.integration.core.webhook;

import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import io.nats.client.Connection;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compares what {@code WebhookPayloadSizeFilter} accepts against what the broker will actually take.
 *
 * <p>{@code max_payload} is a server-side NATS setting that defaults to 1 MB, an order of magnitude
 * below the 25 MiB the receiver accepts. Every delivery in the gap is verified, admitted, and then
 * refused by the broker at publish time, which the provider sees as a 503 — recoverable for the
 * events a provider retries, permanent for pushes, and invisible unless someone reads the publish
 * failure metric. The gap is a deployment fact the application cannot fix on its own, so it is read
 * from the live connection and reported rather than inferred from configuration.
 */
class WebhookPayloadCapacityCheck {

    private static final Logger log = LoggerFactory.getLogger(WebhookPayloadCapacityCheck.class);

    /** The broker counts a message's headers in {@code max_payload}, and the receiver adds the provider's headers. */
    static final long PUBLISH_HEADER_ROOM_BYTES = 65536;

    private final Connection connection;
    private final WebhookProperties properties;

    WebhookPayloadCapacityCheck(Connection connection, WebhookProperties properties) {
        this.connection = connection;
        this.properties = properties;
    }

    @PostConstruct
    void verify() {
        long accepted = properties.http().maxPayloadBytes() + PUBLISH_HEADER_ROOM_BYTES;
        long publishable = connection.getMaxPayload();
        if (publishable > 0 && accepted > publishable) {
            log.error(
                    "The receiver accepts webhooks up to {} bytes, which with their headers need messages of up to {} "
                            + "bytes, but this NATS server caps a message at {}: a delivery it accepts above that is "
                            + "verified and then lost at publish. Raise max_payload on the broker to at least {}, or "
                            + "lower hephaestus.webhook.http.max-payload-bytes so such deliveries are refused at the "
                            + "edge instead.",
                    properties.http().maxPayloadBytes(),
                    accepted,
                    publishable,
                    accepted);
        }
    }
}
