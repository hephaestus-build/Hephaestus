package de.tum.cit.aet.hephaestus.integration.core.spi;

import org.jspecify.annotations.Nullable;

/**
 * A recent issue or pull request pass over a provider's newest-first list.
 *
 * @param result       the pass's outcome
 * @param resumeCursor set only when the pass stopped at the page cap and every item it read is complete: the next
 *                     run resumes the window at this cursor instead of reading its newest pages again
 */
public record RecentSyncResult(SyncResult result, @Nullable String resumeCursor) {}
