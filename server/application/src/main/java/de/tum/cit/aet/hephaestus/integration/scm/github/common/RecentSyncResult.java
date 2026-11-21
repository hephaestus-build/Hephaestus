package de.tum.cit.aet.hephaestus.integration.scm.github.common;

import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import org.jspecify.annotations.Nullable;

/**
 * A recent issue or pull request pass over GitHub's newest-first list.
 *
 * @param result       the pass's outcome
 * @param resumeCursor set only when the pass stopped at the page cap and every item it read is complete: the next
 *                     run resumes the window at this cursor instead of reading its newest pages again
 */
public record RecentSyncResult(SyncResult result, @Nullable String resumeCursor) {}
