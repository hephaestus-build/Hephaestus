package de.tum.cit.aet.hephaestus.agent.job;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import org.jspecify.annotations.NonNull;

@Schema(description = "Counts of practice reviews by status")
public record ReviewRunCountsDTO(
        @NonNull Long queued,
        @NonNull Long running,
        @NonNull Long completed,
        @NonNull Long failed,
        @NonNull Long timedOut,
        @NonNull Long cancelled) {
    static ReviewRunCountsDTO from(Map<AgentJobStatus, Long> counts) {
        return new ReviewRunCountsDTO(
                counts.getOrDefault(AgentJobStatus.QUEUED, 0L),
                counts.getOrDefault(AgentJobStatus.RUNNING, 0L),
                counts.getOrDefault(AgentJobStatus.COMPLETED, 0L),
                counts.getOrDefault(AgentJobStatus.FAILED, 0L),
                counts.getOrDefault(AgentJobStatus.TIMED_OUT, 0L),
                counts.getOrDefault(AgentJobStatus.CANCELLED, 0L));
    }
}
