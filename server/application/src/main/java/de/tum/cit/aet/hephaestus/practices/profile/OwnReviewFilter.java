package de.tum.cit.aet.hephaestus.practices.profile;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import java.time.Instant;
import java.util.Collection;
import org.jspecify.annotations.Nullable;

/**
 * How the reader's reviews are narrowed and ordered: by kinds of work, one work, a group, statuses and a
 * start time, newest first or oldest first. A null or empty narrowing does not narrow.
 */
public record OwnReviewFilter(
        @Nullable Collection<String> itemKinds,
        @Nullable Long itemId,
        @Nullable String groupSlug,
        @Nullable Collection<ReviewStatus> statuses,
        @Nullable Instant since,
        boolean oldestFirst) {}
