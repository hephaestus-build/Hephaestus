package de.tum.cit.aet.hephaestus.integration.scm.github.milestone.dto;

import static de.tum.cit.aet.hephaestus.integration.scm.domain.common.DateTimeUtils.toInstant;
import static de.tum.cit.aet.hephaestus.integration.scm.domain.common.DateTimeUtils.uriToString;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHMilestone;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHMilestoneState;
import java.time.Instant;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * DTO for GitHub milestones.
 * <p>
 * Provides factory methods for creating from both REST (webhook) and GraphQL responses.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubMilestoneDTO(
        @JsonProperty("id") @Nullable Long id,
        @JsonProperty("number") int number,
        @JsonProperty("title") String title,
        @JsonProperty("description") @Nullable String description,
        @JsonProperty("state") @Nullable String state,
        @JsonProperty("due_on") @Nullable Instant dueOn,
        @JsonProperty("html_url") @Nullable String htmlUrl,
        @JsonProperty("open_issues") Integer openIssuesCount,
        @JsonProperty("closed_issues") Integer closedIssuesCount,
        @JsonProperty("created_at") @Nullable Instant createdAt,
        @JsonProperty("updated_at") @Nullable Instant updatedAt,
        @JsonProperty("closed_at") @Nullable Instant closedAt) {
    // STATIC FACTORY METHODS FOR GRAPHQL RESPONSES

    /**
     * Creates a GitHubMilestoneDTO from a GraphQL GHMilestone model.
     */
    @Nullable
    public static GitHubMilestoneDTO fromMilestone(@Nullable GHMilestone milestone) {
        if (milestone == null) {
            return null;
        }
        return new GitHubMilestoneDTO(
                null,
                milestone.getNumber(),
                milestone.getTitle(),
                milestone.getDescription(),
                convertState(milestone.getState()),
                toInstant(milestone.getDueOn()),
                uriToString(milestone.getUrl()),
                milestone.getOpenIssueCount(),
                milestone.getClosedIssueCount(),
                toInstant(milestone.getCreatedAt()),
                toInstant(milestone.getUpdatedAt()),
                toInstant(milestone.getClosedAt()));
    }

    @Nullable
    private static String convertState(@Nullable GHMilestoneState state) {
        if (state == null) {
            return null; // Let processor handle missing state with appropriate logging
        }
        return state.name().toLowerCase(Locale.ROOT);
    }
}
