package de.tum.cit.aet.hephaestus.core.release;

import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * What the deployment tells the process about the release it started. Both identity values are the
 * lock renderer's own lines ({@code scripts/release-image-lock.ts}, {@code reconcile-deployment.ts})
 * passed through Compose, so a value that is present but malformed is a broken lock env and refuses
 * startup rather than being reported as if it were an observation.
 *
 * @param commit       the source commit the lock names, or empty outside a lock-driven deployment
 * @param image        the digest reference this process's container was started from, or empty
 * @param checkEnabled whether the server role may ask GitHub for the latest published release
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.release")
public record ReleaseProperties(
        @Pattern(regexp = "|[a-f0-9]{40}", message = "must be empty or a full lowercase commit SHA") @DefaultValue("")
        String commit,

        @Pattern(
                regexp = "|[a-z0-9][a-z0-9._/-]*@sha256:[a-f0-9]{64}",
                message = "must be empty or an image reference pinned by sha256 digest")
        @DefaultValue("")
        String image,

        @DefaultValue("true") boolean checkEnabled) {}
