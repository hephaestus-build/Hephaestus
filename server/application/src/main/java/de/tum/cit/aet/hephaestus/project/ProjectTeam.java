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
import org.jspecify.annotations.Nullable;

/** A team of people in a workspace who submit work together. It is not a team that a provider mirrors. */
@Entity
@Table(
        name = "project_team",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_project_team_workspace",
                        columnNames = {"id", "workspace_id"}),
        indexes = @Index(name = "idx_project_team_workspace", columnList = "workspace_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectTeam {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "UUID")
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** A removed team reaches nobody, and its members read nothing of it. */
    @Column(name = "removed_at")
    private @Nullable Instant removedAt;
}
