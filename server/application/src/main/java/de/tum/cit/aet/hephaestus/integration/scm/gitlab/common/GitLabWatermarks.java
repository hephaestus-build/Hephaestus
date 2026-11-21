package de.tum.cit.aet.hephaestus.integration.scm.gitlab.common;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;

/**
 * The watermarks of a GitLab project's recent sync. Issues and merge requests each read from their own watermark, as
 * on GitHub, so that one kind's open window never holds the other back. The project's watermark is the earlier of the
 * two, and the phases that have no watermark of their own read from it.
 */
public final class GitLabWatermarks {
    /** The overlap that includes updates whose GitLab clock is behind the recorded watermark. */
    private static final Duration BUFFER = Duration.ofMinutes(5);

    private GitLabWatermarks() {}

    /**
     * Where one kind reads from: its own watermark, or the project's for a sync target that has none yet, less the
     * overlap. Null reads everything.
     */
    public static @Nullable OffsetDateTime updatedAfter(@Nullable Instant kind, @Nullable Instant project) {
        Instant watermark = kind != null ? kind : project;
        return watermark == null ? null : watermark.minus(BUFFER).atOffset(ZoneOffset.UTC);
    }

    /** The project's watermark: the earliest of the kinds' watermarks that moved, and never after the pass start. */
    public static Instant earliest(Instant passStart, @Nullable Instant issues, @Nullable Instant mergeRequests) {
        Instant earliest = passStart;
        if (issues != null && issues.isBefore(earliest)) earliest = issues;
        if (mergeRequests != null && mergeRequests.isBefore(earliest)) earliest = mergeRequests;
        return earliest;
    }
}
