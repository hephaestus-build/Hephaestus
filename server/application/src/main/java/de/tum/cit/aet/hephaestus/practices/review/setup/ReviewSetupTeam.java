package de.tum.cit.aet.hephaestus.practices.review.setup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A team project that can use a team setup. The team is a plain id, because Practice Review does not depend on the
 * project module; the database refuses a team that is not a standing team project of the workspace.
 */
@Entity
@Table(
        name = "review_setup_team",
        indexes = @Index(name = "idx_review_setup_team_setup", columnList = "setup_id, workspace_id"))
@IdClass(ReviewSetupTeam.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewSetupTeam {

    @Id
    @Column(name = "setup_id", nullable = false, columnDefinition = "UUID")
    private UUID setupId;

    @Id
    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    public record Key(UUID setupId, UUID teamId) implements Serializable {}
}
