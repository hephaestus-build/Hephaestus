package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Local repository mirrors under the fabric root.
 *
 * @param enabled Keeps local Git mirrors of monitored repositories, which practice reviews read code from. Turn it on
 *     wherever agent jobs run. Without it, the practice review health check reports out of service.
 * @param maxConcurrentIngestions how many captured commits are written to PostgreSQL at once
 * @param maxSnapshotBytes The most disk that the checkout and reachable history of one review may use. Hephaestus
 *                         refuses a larger repository whole and does not capture part of it.
 * @param maxCacheBytes The most disk that Git mirrors, review checkouts and staged archives may use on this process's
 *     volume. Set it to the size of that volume. Above 80 % of it, Hephaestus deletes the mirrors it used least recently.
 *     A deleted mirror costs one full fetch at its next capture.
 */
@ConfigurationProperties(prefix = "hephaestus.git")
@Validated
public record GitRepositoryProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("2") @Min(1) int maxConcurrentIngestions,
        @DefaultValue("8589934592") @Min(1) long maxSnapshotBytes,
        @DefaultValue("10737418240") @Min(1) long maxCacheBytes) {}
