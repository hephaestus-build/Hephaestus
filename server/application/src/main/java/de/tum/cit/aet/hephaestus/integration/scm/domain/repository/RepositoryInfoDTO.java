package de.tum.cit.aet.hephaestus.integration.scm.domain.repository;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelInfoDTO;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.permission.TeamRepositoryPermission;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Information about a git repository.
 *
 * <h2>ETL Extraction Note</h2>
 * <p>
 * The {@code hiddenFromContributions} field is a scope-specific concept that
 * does not belong in the integration.scm domain. During ETL extraction, this field
 * should be moved to a scope-specific DTO in the workspace module:
 * <pre>
 * public record ScopedRepositoryDTO(RepositoryInfoDTO repository, boolean hiddenFromContributions) {}
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@Schema(description = "Information about a git repository")
public record RepositoryInfoDTO(
        @NonNull @Schema(description = "Unique identifier of the repository")
        Long id,

        @NonNull @Schema(description = "Name of the repository")
        String name,

        @NonNull @Schema(description = "Full name including owner (e.g., 'owner/repo')")
        String nameWithOwner,

        @Nullable @Schema(description = "Description of the repository")
        String description,

        @NonNull @Schema(description = "URL to the repository on the git provider")
        String htmlUrl,

        @Nullable @Schema(description = "Labels defined in the repository")
        List<LabelInfoDTO> labels,
        /**
         * Whether activity in this repository is left out of a team's activity.
         * <p>
         * <b>Note:</b> This field is scope-specific business logic and should be moved
         * to a workspace-specific DTO during ETL extraction.
         */
        @NonNull @Schema(description = "Whether activity in this repository is left out of a team's activity")
        Boolean hiddenFromContributions) {
    @Nullable
    public static RepositoryInfoDTO fromRepository(@Nullable Repository repository) {
        if (repository == null) {
            return null;
        }
        // Avoid circular references by setting the nested repository reference in LabelInfoDTO to null
        final List<LabelInfoDTO> labelDtos = repository.getLabels() != null
                ? repository.getLabels().stream()
                        .map(l -> new LabelInfoDTO(l.getId(), l.getName(), l.getColor(), null))
                        .toList()
                : List.of();
        return fromRepository(repository, labelDtos);
    }

    /**
     * The repository without its labels. Reading them here loads a workspace-scoped table lazily,
     * outside any scoped query, which tenancy enforcement rejects; a caller that renders no labels
     * must not ask for them.
     */
    @Nullable
    public static RepositoryInfoDTO fromRepositoryWithoutLabels(@Nullable Repository repository) {
        return repository == null ? null : fromRepository(repository, null);
    }

    private static RepositoryInfoDTO fromRepository(Repository repository, @Nullable List<LabelInfoDTO> labels) {
        return new RepositoryInfoDTO(
                repository.getId(),
                repository.getName(),
                repository.getNameWithOwner(),
                repository.getDescription(),
                repository.getHtmlUrl(),
                labels,
                Boolean.FALSE);
    }

    /**
     * Creates a RepositoryInfoDTO from a TeamRepositoryPermission using scope-specific settings.
     *
     * <p>This method applies scope-specific visibility settings,
     * enabling different configurations for the same repository across multiple scopes.
     *
     * @param permission the team repository permission
     * @param hiddenFromContributions whether this repository is hidden from contributions in the scope
     * @return the DTO with scope-specific settings applied, or null if permission or repository is null
     */
    @Nullable
    public static RepositoryInfoDTO fromPermissionWithScopeSettings(
            @Nullable TeamRepositoryPermission permission, boolean hiddenFromContributions) {
        if (permission == null) {
            return null;
        }
        final Repository repository = permission.getRepository();
        if (repository == null) {
            return null;
        }
        // Avoid circular references by setting the nested repository reference in LabelInfoDTO to null
        final List<LabelInfoDTO> labelDtos = repository.getLabels() != null
                ? repository.getLabels().stream()
                        .map(l -> new LabelInfoDTO(l.getId(), l.getName(), l.getColor(), null))
                        .toList()
                : List.of();

        return new RepositoryInfoDTO(
                repository.getId(),
                repository.getName(),
                repository.getNameWithOwner(),
                repository.getDescription(),
                repository.getHtmlUrl(),
                labelDtos,
                hiddenFromContributions);
    }
}
