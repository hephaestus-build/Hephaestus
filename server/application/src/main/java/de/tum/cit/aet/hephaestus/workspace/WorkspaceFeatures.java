package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.workspace.dto.UpdateWorkspaceFeaturesRequestDTO;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

/**
 * Whether practice reviews run in a workspace, and which triggers start them. Practice reviews default
 * to {@code false} (a new workspace opts in once its practices and model are ready); the triggers default
 * to {@code true}, so turning reviews on activates both. Every {@code @ColumnDefault} mirrors the
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
        if (request.practiceReviewAutoTriggerEnabled() != null) {
            this.practiceReviewAutoTriggerEnabled = request.practiceReviewAutoTriggerEnabled();
        }
        if (request.practiceReviewManualTriggerEnabled() != null) {
            this.practiceReviewManualTriggerEnabled = request.practiceReviewManualTriggerEnabled();
        }
    }
}
