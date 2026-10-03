package de.tum.cit.aet.hephaestus.integration.scm.github.project.dto;

import static de.tum.cit.aet.hephaestus.integration.scm.domain.common.DateTimeUtils.toInstant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2StatusUpdate;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2StatusUpdateStatus;
import de.tum.cit.aet.hephaestus.integration.scm.github.project.ProjectStatusUpdate;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Domain DTO for GitHub Projects V2 status updates.
 * <p>
 * This is the unified model used by both GraphQL sync and webhook handlers.
 * It can be constructed from any source (GraphQL, REST, webhook payload).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Slf4j
public record GitHubProjectStatusUpdateDTO(
        @JsonProperty("id") @Nullable Long id,
        @JsonProperty("database_id") @Nullable Long databaseId,
        @JsonProperty("node_id") String nodeId,
        @JsonProperty("body") String body,
        @JsonProperty("body_html") String bodyHtml,
        @JsonProperty("start_date") @Nullable LocalDate startDate,
        @JsonProperty("target_date") @Nullable LocalDate targetDate,
        @JsonProperty("status") @Nullable String status,
        @JsonProperty("creator") @Nullable GitHubUserDTO creator,
        @JsonProperty("created_at") @Nullable Instant createdAt,
        @JsonProperty("updated_at") @Nullable Instant updatedAt) {
    /**
     * Get the database ID, preferring databaseId over id for GraphQL responses.
     */
    public @Nullable Long getDatabaseId() {
        return databaseId != null ? databaseId : id;
    }

    /**
     * Get the status as a ProjectStatusUpdate.Status enum.
     *
     * @return the status enum value, or null if status is null or unrecognized
     */
    public ProjectStatusUpdate.@Nullable Status getStatusEnum() {
        if (status == null) {
            return null;
        }
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "INACTIVE" -> ProjectStatusUpdate.Status.INACTIVE;
            case "ON_TRACK" -> ProjectStatusUpdate.Status.ON_TRACK;
            case "AT_RISK" -> ProjectStatusUpdate.Status.AT_RISK;
            case "OFF_TRACK" -> ProjectStatusUpdate.Status.OFF_TRACK;
            case "COMPLETE" -> ProjectStatusUpdate.Status.COMPLETE;
            default -> null;
        };
    }

    // STATIC FACTORY METHODS FOR GRAPHQL RESPONSES

    /**
     * Creates a GitHubProjectStatusUpdateDTO from a GraphQL GHProjectV2StatusUpdate model.
     *
     * @param statusUpdate the GraphQL GHProjectV2StatusUpdate (may be null)
     * @return GitHubProjectStatusUpdateDTO or null if statusUpdate is null
     */
    @Nullable
    public static GitHubProjectStatusUpdateDTO fromStatusUpdate(@Nullable GHProjectV2StatusUpdate statusUpdate) {
        if (statusUpdate == null) {
            return null;
        }

        return new GitHubProjectStatusUpdateDTO(
                null,
                toLong(statusUpdate.getFullDatabaseId()),
                statusUpdate.getId(),
                statusUpdate.getBody(),
                statusUpdate.getBodyHTML(),
                statusUpdate.getStartDate(),
                statusUpdate.getTargetDate(),
                statusToString(statusUpdate.getStatus()),
                GitHubUserDTO.fromActor(statusUpdate.getCreator()),
                toInstant(statusUpdate.getCreatedAt()),
                toInstant(statusUpdate.getUpdatedAt()));
    }

    // CONVERSION HELPERS

    @Nullable
    private static Long toLong(@Nullable BigInteger value) {
        if (value == null) {
            return null;
        }
        try {
            return value.longValueExact();
        } catch (ArithmeticException e) {
            log.error("GitHub database ID {} exceeds Long.MAX_VALUE, truncating to {}", value, value.longValue());
            return value.longValue();
        }
    }

    @Nullable
    private static String statusToString(@Nullable GHProjectV2StatusUpdateStatus status) {
        if (status == null) {
            return null;
        }
        return status.toString();
    }
}
