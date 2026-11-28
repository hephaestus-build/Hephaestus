package de.tum.cit.aet.hephaestus.integration.submission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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

/**
 * One piece of a project team's work, of one team kind. It holds no content: everything that a member submitted, its
 * title included, belongs to a version.
 */
@Entity
@Table(
        name = "team_item",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_team_item_workspace",
                        columnNames = {"id", "workspace_id"}),
        indexes = @Index(name = "idx_team_item_team", columnList = "team_id, workspace_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TeamItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(name = "kind", nullable = false, length = 64)
    private String kind;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "removed_at")
    private @Nullable Instant removedAt;
}
