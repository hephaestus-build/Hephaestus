package de.tum.cit.aet.hephaestus.config;

import de.tum.cit.aet.hephaestus.core.runtime.ReceiverComponent;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe configuration properties for CORS settings.
 *
 * <p>Binds to the {@code hephaestus.cors} prefix in application configuration.
 * Uses Spring Boot's {@link ConfigurationProperties} for proper binding and validation,
 * avoiding the pitfalls of {@code @Value} annotations with environment variable placeholders.
 *
 * <h2>Configuration Example</h2>
 * <pre>{@code
 * hephaestus:
 *   cors:
 *     allowed-origins:
 *       - https://hephaestus.example.com
 *       - https://app.example.com
 * }</pre>
 *
 * @param allowedOrigins Browsers at these origins can make cross-origin requests to this instance.
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.cors")
@ReceiverComponent
public record CorsProperties(
        @NotEmpty(message = "CORS allowed origins must not be empty")
        List<String> allowedOrigins) {}
