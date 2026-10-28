package de.tum.cit.aet.hephaestus.agent.proxy;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How long one model call of a Heph turn may hear nothing from its provider, bound from
 * {@code hephaestus.mentor.turn.*}. A provider can queue a request before it sends its response headers, so the first
 * byte gets its own, longer bound.
 *
 * @param modelFirstByteTimeout until the response headers
 * @param modelIdleTimeout between two reads of the response body; an SSE comment counts
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.mentor.turn")
public record HephCallProperties(
        @DefaultValue("PT5M") @NotNull @DurationMin(seconds = 1)
        Duration modelFirstByteTimeout,

        @DefaultValue("PT2M") @NotNull @DurationMin(seconds = 1)
        Duration modelIdleTimeout) {}
