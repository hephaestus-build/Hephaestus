package de.tum.cit.aet.hephaestus.core.webapp;

import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Where the built web app is and what the deployment adds to it.
 *
 * @param location        The directory of the built SPA, as a Spring resource location ending in {@code /}. A
 *                        location without {@code index.html}, such as a server built without the web app, serves no
 *                        web app.
 * @param securityContact The deployment's contact for {@code /.well-known/security.txt} (RFC 9116): one
 *                        {@code mailto:}, {@code https://} or {@code tel:} URI. Empty serves no file.
 * @param legalOverrides  A directory with the operator's {@code imprint.md} and {@code privacy.md}, served under
 *                        {@code /legal-overrides/}. Empty serves the built-in placeholders.
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.web-app")
public record WebAppProperties(
        @DefaultValue("classpath:/web-app/") String location,

        @Pattern(
                regexp = "|(?:mailto:|https://|tel:)\\S+",
                message = "must be empty or one mailto:, https:// or tel: URI")
        @DefaultValue("")
        String securityContact,

        @DefaultValue("") String legalOverrides) {}
