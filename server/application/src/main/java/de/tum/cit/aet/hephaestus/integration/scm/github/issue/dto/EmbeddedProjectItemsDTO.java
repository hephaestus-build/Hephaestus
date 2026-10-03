package de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto;

import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHOrganization;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2Item;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2ItemConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHUser;
import de.tum.cit.aet.hephaestus.integration.scm.github.project.dto.GitHubProjectItemDTO;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * DTO for embedded project items fetched inline with issues/PRs.
 * <p>
 * Contains the project items fetched in the initial issue/PR query plus pagination info
 * to determine if additional API calls are needed for issues/PRs in many projects.
 * <p>
 * This follows the same pattern as {@link EmbeddedCommentsDTO} for comment pagination.
 * <p>
 * <h2>Architecture Notes</h2>
 * Project items are synced FROM the issue/PR side (embedded in issue/PR queries) rather
 * than from the project side. This design enables:
 * <ul>
 *   <li>Historical backfill: Project items are naturally backfilled as issues/PRs are backfilled</li>
 *   <li>Efficient sync: Items updated with their parent issue/PR are captured automatically</li>
 *   <li>Proper filtering: GitHub's filterBy.since on issues/PRs filters items transitively</li>
 * </ul>
 * <p>
 * Draft Issues are an exception - they have no parent Issue and must be synced from project side.
 */
public record EmbeddedProjectItemsDTO(
        List<EmbeddedProjectItem> items,
        int totalCount,
        boolean hasNextPage,
        @Nullable String endCursor) {
    /**
     * Creates an EmbeddedProjectItemsDTO from a GraphQL GHProjectV2ItemConnection.
     *
     * @param connection the GraphQL connection (may be null)
     * @param context    contextual description for overflow logging (e.g. "PR #42 in owner/repo")
     * @return EmbeddedProjectItemsDTO or empty DTO if connection is null
     */
    public static EmbeddedProjectItemsDTO fromConnection(
            @Nullable GHProjectV2ItemConnection connection, String context) {
        if (connection == null) {
            return empty();
        }

        List<EmbeddedProjectItem> items = connection.getNodes() != null
                ? connection.getNodes().stream()
                        .map(EmbeddedProjectItem::fromProjectV2Item)
                        .filter(Objects::nonNull)
                        .toList()
                : Collections.emptyList();

        boolean hasNextPage = connection.getPageInfo() != null
                && Boolean.TRUE.equals(connection.getPageInfo().getHasNextPage());

        String endCursor =
                connection.getPageInfo() != null ? connection.getPageInfo().getEndCursor() : null;

        return new EmbeddedProjectItemsDTO(items, connection.getTotalCount(), hasNextPage, endCursor);
    }

    /**
     * Returns an empty EmbeddedProjectItemsDTO.
     */
    public static EmbeddedProjectItemsDTO empty() {
        return new EmbeddedProjectItemsDTO(Collections.emptyList(), 0, false, null);
    }

    /**
     * Checks if there are more project items to fetch beyond what's embedded.
     */
    public boolean needsPagination() {
        return hasNextPage;
    }

    /**
     * Container for an embedded project item with its project reference.
     * <p>
     * This includes the project info needed to link the item to the correct project
     * without requiring additional API calls.
     */
    public record EmbeddedProjectItem(
            @Nullable GitHubProjectItemDTO item, @Nullable EmbeddedProjectReference project) {
        /**
         * Creates an EmbeddedProjectItem from a GraphQL GHProjectV2Item.
         *
         * @param graphQlItem the GraphQL item (may be null)
         * @return EmbeddedProjectItem or null if item is null
         */
        @Nullable
        public static EmbeddedProjectItem fromProjectV2Item(@Nullable GHProjectV2Item graphQlItem) {
            if (graphQlItem == null) {
                return null;
            }

            GitHubProjectItemDTO itemDto = GitHubProjectItemDTO.fromProjectV2Item(graphQlItem);
            if (itemDto == null) {
                return null;
            }

            EmbeddedProjectReference projectRef = EmbeddedProjectReference.fromProjectV2(graphQlItem.getProject());

            return new EmbeddedProjectItem(itemDto, projectRef);
        }
    }

    /**
     * Reference to the project that contains an embedded item.
     * <p>
     * Contains just enough information to identify and link to the project
     * without duplicating the full project entity.
     */
    public record EmbeddedProjectReference(
            String nodeId,
            @Nullable Long databaseId,
            int number,
            String title,
            @Nullable String url,
            @Nullable String ownerLogin,
            @Nullable Long ownerDatabaseId,
            @Nullable String ownerType) {
        /**
         * Creates an EmbeddedProjectReference from a GraphQL GHProjectV2.
         *
         * @param project the GraphQL project (may be null)
         * @return EmbeddedProjectReference or null if project is null
         */
        @Nullable
        public static EmbeddedProjectReference fromProjectV2(@Nullable GHProjectV2 project) {
            if (project == null || project.getId() == null) {
                return null;
            }

            Long databaseId = project.getFullDatabaseId() != null
                    ? project.getFullDatabaseId().longValue()
                    : null;

            // Extract owner info
            String ownerLogin = null;
            Long ownerDatabaseId = null;
            String ownerType = null;

            var owner = project.getOwner();
            if (owner != null) {
                // GHProjectV2Owner is a union type - check concrete types
                if (owner instanceof GHOrganization org) {
                    ownerLogin = org.getLogin();
                    ownerDatabaseId = org.getDatabaseId() != null ? (long) org.getDatabaseId() : null;
                    ownerType = "ORGANIZATION";
                } else if (owner instanceof GHUser user) {
                    ownerLogin = user.getLogin();
                    ownerDatabaseId = user.getDatabaseId() != null ? (long) user.getDatabaseId() : null;
                    ownerType = "USER";
                }
            }

            return new EmbeddedProjectReference(
                    project.getId(),
                    databaseId,
                    project.getNumber(),
                    project.getTitle(),
                    project.getUrl() != null ? project.getUrl().toString() : null,
                    ownerLogin,
                    ownerDatabaseId,
                    ownerType);
        }
    }
}
