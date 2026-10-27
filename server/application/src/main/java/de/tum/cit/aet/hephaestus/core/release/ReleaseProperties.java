package de.tum.cit.aet.hephaestus.core.release;

import de.tum.cit.aet.hephaestus.core.runtime.ReceiverComponent;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * What the deployment tells the process about the release it started. The identity values are lines
 * of the rendered lock env, so one that is present but malformed is a broken deployment and refuses
 * startup rather than being reported as an observation.
 *
 * @param commit       The source commit that the release lock names. It is empty outside a deployment from a release
 *                     lock.
 * @param image        The digest reference of the image that this container started from. It is empty outside a
 *                     deployment from a release lock.
 * @param checkEnabled whether the server role may ask GitHub for the newest published release
 * @param environment  The name of this deployment, such as {@code production}. The webapp, log lines and traces use
 *                     the same name.
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.release")
@ReceiverComponent
public record ReleaseProperties(
        @Pattern(regexp = "|[a-f0-9]{40}", message = "must be empty or a full lowercase commit SHA") @DefaultValue("")
        String commit,

        @Pattern(
                regexp = "|" + ImageReference.DIGEST_PINNED,
                message = "must be empty or an image reference pinned by sha256 digest")
        @DefaultValue("")
        String image,

        @DefaultValue("true") boolean checkEnabled,

        // A log collector may index this value, so it stays short and bounded; lowercase keeps the webapp's
        // production check exact.
        @Pattern(
                regexp = "[a-z0-9][a-z0-9._-]{0,63}",
                message =
                        "DEPLOYMENT_ENVIRONMENT must be lowercase letters, digits, '.', '_' or '-', at most 64 characters")
        @DefaultValue("local")
        String environment) {}
