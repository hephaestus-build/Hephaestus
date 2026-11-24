package de.tum.cit.aet.hephaestus.integration.core.spi;

import org.jspecify.annotations.Nullable;

/**
 * Vendor-neutral projection of one monitored repository's historical-backfill progress — exactly the
 * fields {@link de.tum.cit.aet.hephaestus.integration.core.spi.BackfillSummary} rollup needs, decoupled
 * from the {@code workspace} entity that stores them.
 *
 * <p>Both SCM vendors persist the same {@code repository_to_monitor} columns (issue/PR high-water-mark and
 * the initialized/complete/remaining bookkeeping). The vendor-neutral rollup ({@code ScmBackfillRollup})
 * consumes this projection so it stays free of a {@code workspace} dependency; the per-vendor providers —
 * which are permitted to touch {@code workspace} — map their monitored-repository rows into it.
 *
 * @param initialized              whether backfill has started for this repository
 * @param complete                 whether backfill has finished for this repository
 * @param issueHighWaterMark       highest issue number reached, if any
 * @param pullRequestHighWaterMark highest pull-request number reached, if any
 * @param remaining                total items still to backfill (issues + pull requests)
 */
@SourceControlContract
public record RepoBackfillProgress(
        boolean initialized,
        boolean complete,
        @Nullable Integer issueHighWaterMark,
        @Nullable Integer pullRequestHighWaterMark,
        int remaining) {}
