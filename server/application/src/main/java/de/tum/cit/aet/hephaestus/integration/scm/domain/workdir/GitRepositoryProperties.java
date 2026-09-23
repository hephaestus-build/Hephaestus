package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Local repository mirrors under the fabric root.
 *
 * @param maxConcurrentIngestions how many captured commits are written to PostgreSQL at once
 * @param maxSnapshotBytes the most a review's checkout plus reachable history may occupy; a repository
 *     above it is refused whole rather than captured in part
 */
@ConfigurationProperties(prefix = "hephaestus.git")
@Validated
public record GitRepositoryProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("2") @Min(1) int maxConcurrentIngestions,
        @DefaultValue("8589934592") @Min(1) long maxSnapshotBytes) {}
