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
 * One person's membership of a project team. The row exists only while it holds: leaving the team or the workspace
 * deletes it. The database refuses a row without a current workspace membership.
 */
@Entity
@Table(
        name = "project_team_member",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_project_team_member_account",
                        columnNames = {"team_id", "account_id"}),
        indexes = {
            @Index(name = "idx_project_team_member_team", columnList = "team_id, workspace_id"),
            @Index(name = "idx_project_team_member_account", columnList = "account_id, workspace_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectTeamMember {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "UUID")
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(name = "account_id", nullable = false)
    private long accountId;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;
}
