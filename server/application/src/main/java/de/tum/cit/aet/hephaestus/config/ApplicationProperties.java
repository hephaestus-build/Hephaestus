package de.tum.cit.aet.hephaestus.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.URL;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe configuration properties for general application settings.
 *
 * <p>Binds to the {@code hephaestus} prefix in application configuration.
 * Contains application-wide settings that don't belong to a specific feature.
 *
 * <h2>Configuration Example</h2>
 * <pre>{@code
 * hephaestus:
 *   host-url: https://hephaestus.example.com
 *   webapp:
 *     url: https://app.example.com
 * }</pre>
 *
 * @param hostUrl Set the public URL of this Hephaestus instance for links in notifications.
 * @param webapp  webapp configuration
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus")
public record ApplicationProperties(
        @Nullable @URL(message = "Host URL must be a valid URL if provided") @DefaultValue("http://localhost:8080")
        String hostUrl,

        @Valid Webapp webapp) {
    public ApplicationProperties {
        if (webapp == null) {
            webapp = new Webapp("http://localhost:4200");
        }
    }

    public record Webapp(
            @NotBlank(message = "Webapp URL must not be blank")
            @URL(regexp = "^https?://.*$", message = "Webapp URL must be a valid HTTP(S) URL")
            @DefaultValue("http://localhost:4200")
            String url) {}
}
