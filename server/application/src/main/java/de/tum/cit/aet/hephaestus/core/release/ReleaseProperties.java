package de.tum.cit.aet.hephaestus.core.release;

import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * What the deployment tells the process about the release it started. The identity values are lines
 * of the rendered lock env, so one that is present but malformed is a broken deployment and refuses
 * startup rather than being reported as an observation.
 *
 * @param commit       the source commit the lock names; empty outside a lock-driven deployment
 * @param image        the digest reference this container was started from; empty likewise
 * @param checkEnabled whether the server role may ask GitHub for the newest published release
 * @param environment  the name the deployment gives this instance, such as {@code production}; the
 *                     webapp, error reports and traces use the same name
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.release")
public record ReleaseProperties(
        @Pattern(regexp = "|[a-f0-9]{40}", message = "must be empty or a full lowercase commit SHA") @DefaultValue("")
        String commit,

        @Pattern(
                regexp = "|" + ImageReference.DIGEST_PINNED,
                message = "must be empty or an image reference pinned by sha256 digest")
        @DefaultValue("")
        String image,

        @DefaultValue("true") boolean checkEnabled,

        // Sentry refuses longer names; lowercase keeps the webapp's production check exact.
        @Pattern(
                regexp = "[a-z0-9][a-z0-9._-]{0,63}",
                message =
                        "DEPLOYMENT_ENVIRONMENT must be lowercase letters, digits, '.', '_' or '-', at most 64 characters")
        @DefaultValue("local")
        String environment) {}
