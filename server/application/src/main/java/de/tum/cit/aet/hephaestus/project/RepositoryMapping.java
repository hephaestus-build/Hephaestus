package de.tum.cit.aet.hephaestus.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A repository that a workspace admin mapped to a team project, so that a review of the repository can have the team as
 * its audience (ADR 0167 decision 3). Nothing writes it yet. Unmapping deletes the row, and each review keeps the
 * audience that it was created with. The database deletes the row with its team. The repository item is a plain id
 * (ADR 0138), which the writer checks through the item port.
 */
@Entity
@Table(
        name = "project_team_repository",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_project_team_repository",
                        columnNames = {"team_id", "repository_item_id"}),
        indexes = {
            @Index(name = "idx_project_team_repository_team", columnList = "team_id, workspace_id"),
            @Index(name = "idx_project_team_repository_item", columnList = "repository_item_id, workspace_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RepositoryMapping {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "UUID")
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(name = "repository_item_id", nullable = false)
    private long repositoryItemId;

    @Column(name = "mapped_at", nullable = false)
    private Instant mappedAt;
}
