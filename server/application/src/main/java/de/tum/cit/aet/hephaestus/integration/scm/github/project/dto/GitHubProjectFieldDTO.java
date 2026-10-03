package de.tum.cit.aet.hephaestus.integration.scm.github.project.dto;

import static de.tum.cit.aet.hephaestus.integration.scm.domain.common.DateTimeUtils.toInstant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2Field;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2FieldConfiguration;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2IterationField;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2SingleSelectField;
import de.tum.cit.aet.hephaestus.integration.scm.github.project.ProjectField;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Domain DTO for GitHub Projects V2 field configuration.
 * <p>
 * Fields define the columns/properties that can be set on project items.
 * GitHub provides various field types: text, number, date, single-select, iteration.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Slf4j
public record GitHubProjectFieldDTO(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("data_type") @Nullable String dataType,
        @JsonProperty("options") @Nullable List<Option> options,
        @JsonProperty("created_at") @Nullable Instant createdAt,
        @JsonProperty("updated_at") @Nullable Instant updatedAt) {
    // Trivial serialization (List<Option> → JSON string column); no global mapper config needed.
    private static final ObjectMapper objectMapper = JsonMapper.builder().build();

    /**
     * Option for single-select fields.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Option(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("color") @Nullable String color,
            @JsonProperty("description") @Nullable String description) {}

    /**
     * Get the data type as an enum.
     * Handles both custom field types and built-in system field types.
     */
    public ProjectField.@Nullable DataType getDataTypeEnum() {
        if (dataType == null) {
            return null;
        }
        return switch (dataType.toUpperCase(Locale.ROOT)) {
            // Custom field types
            case "TEXT" -> ProjectField.DataType.TEXT;
            case "NUMBER" -> ProjectField.DataType.NUMBER;
            case "DATE" -> ProjectField.DataType.DATE;
            case "SINGLE_SELECT", "SINGLESELECT" -> ProjectField.DataType.SINGLE_SELECT;
            case "ITERATION" -> ProjectField.DataType.ITERATION;
            // Built-in system field types
            case "TITLE" -> ProjectField.DataType.TITLE;
            case "ASSIGNEES" -> ProjectField.DataType.ASSIGNEES;
            case "LABELS" -> ProjectField.DataType.LABELS;
            case "LINKED_PULL_REQUESTS" -> ProjectField.DataType.LINKED_PULL_REQUESTS;
            case "MILESTONE" -> ProjectField.DataType.MILESTONE;
            case "REPOSITORY" -> ProjectField.DataType.REPOSITORY;
            case "REVIEWERS" -> ProjectField.DataType.REVIEWERS;
            case "PARENT_ISSUE" -> ProjectField.DataType.PARENT_ISSUE;
            case "SUB_ISSUES_PROGRESS" -> ProjectField.DataType.SUB_ISSUES_PROGRESS;
            case "ISSUE_TYPE" -> ProjectField.DataType.ISSUE_TYPE;
            case "TRACKED_BY" -> ProjectField.DataType.TRACKED_BY;
            default -> {
                log.warn("Unknown field data type: {}, treating as TEXT", dataType);
                yield ProjectField.DataType.TEXT;
            }
        };
    }

    /**
     * Convert options to JSON string for storage.
     */
    public @Nullable String getOptionsJson() {
        if (options == null || options.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(options);
        } catch (JacksonException e) {
            log.error("Failed to serialize options to JSON", e);
            return null;
        }
    }

    // STATIC FACTORY METHODS FOR GRAPHQL RESPONSES

    /**
     * Creates a GitHubProjectFieldDTO from a GraphQL field configuration.
     * <p>
     * Handles different field types: ProjectV2Field, ProjectV2SingleSelectField, ProjectV2IterationField.
     *
     * @param field the GraphQL field configuration (may be null)
     * @return GitHubProjectFieldDTO or null if field is null
     */
    @Nullable
    public static GitHubProjectFieldDTO fromFieldConfiguration(@Nullable GHProjectV2FieldConfiguration field) {
        if (field == null) {
            return null;
        }

        // Handle different field types through instanceof checks
        if (field instanceof GHProjectV2SingleSelectField ssField) {
            return fromSingleSelectField(ssField);
        } else if (field instanceof GHProjectV2IterationField iterField) {
            return fromIterationField(iterField);
        } else if (field instanceof GHProjectV2Field basicField) {
            return fromBasicField(basicField);
        }

        log.warn("Unknown field configuration type: {}", field.getClass().getSimpleName());
        return null;
    }

    private static GitHubProjectFieldDTO fromBasicField(GHProjectV2Field field) {
        String dataType = field.getDataType() != null ? field.getDataType().name() : "TEXT";
        return new GitHubProjectFieldDTO(
                field.getId(),
                field.getName(),
                dataType,
                Collections.emptyList(),
                toInstant(field.getCreatedAt()),
                toInstant(field.getUpdatedAt()));
    }

    private static GitHubProjectFieldDTO fromSingleSelectField(GHProjectV2SingleSelectField field) {
        List<Option> options = Collections.emptyList();
        if (field.getOptions() != null) {
            options = field.getOptions().stream()
                    .map(opt -> new Option(
                            opt.getId(),
                            opt.getName(),
                            opt.getColor() != null ? opt.getColor().name() : null,
                            opt.getDescription()))
                    .toList();
        }
        return new GitHubProjectFieldDTO(
                field.getId(),
                field.getName(),
                "SINGLE_SELECT",
                options,
                toInstant(field.getCreatedAt()),
                toInstant(field.getUpdatedAt()));
    }

    private static GitHubProjectFieldDTO fromIterationField(GHProjectV2IterationField field) {
        List<Option> options = Collections.emptyList();
        if (field.getConfiguration() != null && field.getConfiguration().getIterations() != null) {
            options = field.getConfiguration().getIterations().stream()
                    .map(iter -> new Option(iter.getId(), iter.getTitle(), null, null))
                    .toList();
        }
        return new GitHubProjectFieldDTO(
                field.getId(),
                field.getName(),
                "ITERATION",
                options,
                toInstant(field.getCreatedAt()),
                toInstant(field.getUpdatedAt()));
    }
}
