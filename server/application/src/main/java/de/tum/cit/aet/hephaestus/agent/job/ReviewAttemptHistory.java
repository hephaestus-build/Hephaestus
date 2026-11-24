package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/** Reads the retained metadata of review attempts. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ReviewAttemptHistory {
    private final ReviewJobRepository jobs;

    /** The newest runs of the workspace since {@code since}, with only the columns a run's history shows. */
    public List<RetainedAttempt> recent(long workspaceId, Instant since, Pageable page) {
        return jobs.findRecentAttempts(workspaceId, since, page);
    }

    /** A run as its history shows it, without the configuration, capture and output it stored. */
    public record RetainedAttempt(
            UUID id,
            UUID reviewId,
            ReviewJobStatus status,
            int attempt,
            ProcessingStatus processingStatus,
            Instant availableAt,
            @Nullable Instant startedAt,
            @Nullable Instant completedAt,
            ReviewJobInput input) {}
}
