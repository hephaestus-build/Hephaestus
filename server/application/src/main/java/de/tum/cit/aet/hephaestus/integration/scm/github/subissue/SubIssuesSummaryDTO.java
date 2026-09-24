package de.tum.cit.aet.hephaestus.integration.scm.github.subissue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO for sub-issues summary data from webhook events ({@code sub_issues_summary} on an issue).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SubIssuesSummaryDTO(
        @JsonProperty("total") Integer total,
        @JsonProperty("completed") Integer completed,
        @JsonProperty("percent_completed") Integer percentCompleted) {
    /**
     * Creates an empty summary with zero values.
     */
    public static SubIssuesSummaryDTO empty() {
        return new SubIssuesSummaryDTO(0, 0, 0);
    }
}
