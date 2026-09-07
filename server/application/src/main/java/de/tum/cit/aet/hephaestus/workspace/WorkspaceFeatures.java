package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.workspace.dto.UpdateWorkspaceFeaturesRequestDTO;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

/**
 * Workspace-scoped feature flags, exposed by the admin UI. Top-level flags default to
 * {@code false} (new workspaces opt in); sub-feature trigger flags default to {@code true}
 * (enabling the parent activates all triggers). Every {@code @ColumnDefault} mirrors the
 * Liquibase default so Hibernate's hbm2ddl validation does not drift from the migration.
 *
 * @see UpdateWorkspaceFeaturesRequestDTO
 */
@Embeddable
@Getter
@Setter
public class WorkspaceFeatures {

    @NotNull
    @ColumnDefault("false")
    @Column(name = "practices_enabled", nullable = false)
    private Boolean practicesEnabled = false;

    @NotNull
    @ColumnDefault("false")
    @Column(name = "mentor_enabled", nullable = false)
    private Boolean mentorEnabled = false;

    // Storage-only during retirement; see the achievement package.
    @NotNull
    @ColumnDefault("false")
    @Column(name = "achievements_enabled", nullable = false)
    private Boolean achievementsEnabled = false;

    @NotNull
    @ColumnDefault("false")
    @Column(name = "leaderboard_enabled", nullable = false)
    private Boolean leaderboardEnabled = false;

    @NotNull
    @ColumnDefault("false")
    @Column(name = "progression_enabled", nullable = false)
    private Boolean progressionEnabled = false;

    @NotNull
    @ColumnDefault("false")
    @Column(name = "leagues_enabled", nullable = false)
    private Boolean leaguesEnabled = false;

    @NotNull
    @ColumnDefault("true")
    @Column(name = "practice_review_auto_trigger_enabled", nullable = false)
    private Boolean practiceReviewAutoTriggerEnabled = true;

    @NotNull
    @ColumnDefault("true")
    @Column(name = "practice_review_manual_trigger_enabled", nullable = false)
    private Boolean practiceReviewManualTriggerEnabled = true;

    /** PATCH semantics: null fields are ignored, non-null fields overwrite. */
    public void applyPatch(UpdateWorkspaceFeaturesRequestDTO request) {
        if (request.practicesEnabled() != null) this.practicesEnabled = request.practicesEnabled();
        if (request.mentorEnabled() != null) this.mentorEnabled = request.mentorEnabled();
        if (request.leaderboardEnabled() != null) this.leaderboardEnabled = request.leaderboardEnabled();
        if (request.progressionEnabled() != null) this.progressionEnabled = request.progressionEnabled();
        if (request.leaguesEnabled() != null) this.leaguesEnabled = request.leaguesEnabled();
        if (request.practiceReviewAutoTriggerEnabled() != null) {
            this.practiceReviewAutoTriggerEnabled = request.practiceReviewAutoTriggerEnabled();
        }
        if (request.practiceReviewManualTriggerEnabled() != null) {
            this.practiceReviewManualTriggerEnabled = request.practiceReviewManualTriggerEnabled();
        }
    }
}
