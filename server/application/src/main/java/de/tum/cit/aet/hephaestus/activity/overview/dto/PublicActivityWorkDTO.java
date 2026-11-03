package de.tum.cit.aet.hephaestus.activity.overview.dto;

import de.tum.cit.aet.hephaestus.activity.overview.dto.PublicActivityDTO.PublicActivityRepositoryDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.WorkItemDTO.WorkItemType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue.State;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NonNull;

/** One piece of the selected person's published work; no account, author or other participant data. */
public record PublicActivityWorkDTO(
        @NonNull String id,
        @NonNull PublicActivityWorkSummaryDTO work,
        @NonNull List<ActivityActionDTO> actions,
        @NonNull Instant lastOccurredAt) {

    public record PublicActivityWorkSummaryDTO(
            @NonNull WorkItemType type,
            @NonNull Integer number,
            @NonNull String title,
            @NonNull State state,
            @NonNull Boolean isDraft,
            @NonNull String htmlUrl,
            @NonNull PublicActivityRepositoryDTO repository) {}
}
