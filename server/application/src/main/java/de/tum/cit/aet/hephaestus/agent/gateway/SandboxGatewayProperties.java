package de.tum.cit.aet.hephaestus.agent.gateway;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The worker's sandbox gateway, the API that sandboxed practice reviews and Heph turns call.
 *
 * @param port Port of the extra worker connector that serves the sandbox gateway. The gateway API answers only on this
 *     port. The value 0 binds a free port at start.
 * @param maxRequestBytes Largest request body in bytes that the sandbox gateway accepts. A larger request gets HTTP
 *     413.
 * @param requestsPerMinute Most requests per minute that each sandbox caller can send to the gateway. Further requests
 *     get HTTP 429 with a Retry-After header. The default is four times {@code ProxyCallsInFlight.REVIEW_CALLS}: a
 *     review at its most calls in flight can start each again every 15 seconds, about the shortest model call of a
 *     practice session (estimated). So this limit does not set how many practices a review runs at once.
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.sandbox.gateway")
public record SandboxGatewayProperties(
        @DefaultValue("8081") @Min(0) @Max(65535) int port,
        @DefaultValue("4194304") @Min(1) int maxRequestBytes,
        @DefaultValue("696") @Min(1) int requestsPerMinute) {}
