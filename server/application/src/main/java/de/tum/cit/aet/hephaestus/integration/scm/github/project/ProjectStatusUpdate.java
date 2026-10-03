package de.tum.cit.aet.hephaestus.integration.scm.github.project;

import de.tum.cit.aet.hephaestus.integration.scm.domain.common.BaseGitServiceEntity;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

/**
 * Represents a status update for a GitHub Projects V2 project.
 * Status updates track project health with statuses like ON_TRACK, AT_RISK, OFF_TRACK.
 */
@Entity
@Table(
        name = "project_status_update",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_project_status_update_node_id",
                    columnNames = {"node_id"}),
            @UniqueConstraint(
                    name = "uq_project_status_update_provider_native_id",
                    columnNames = {"provider_id", "native_id"}),
        },
        indexes = {@Index(name = "idx_project_status_update_created_at", columnList = "project_id, created_at DESC")})
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class ProjectStatusUpdate extends BaseGitServiceEntity {

    public enum Status {
        INACTIVE,
        ON_TRACK,
        AT_RISK,
        OFF_TRACK,
        COMPLETE,
    }

    /**
     * GitHub's node ID for the status update.
     */
    @Column(name = "node_id", length = 64)
    private String nodeId;

    /**
     * The project this status update belongs to.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    @ToString.Exclude
    private @Nullable Project project;

    /**
     * The body/content of the status update (markdown).
     */
    @Column(columnDefinition = "TEXT")
    private String body;

    /**
     * The body rendered to HTML for rich display.
     */
    @Column(name = "body_html", columnDefinition = "TEXT")
    @ToString.Exclude
    private String bodyHtml;

    /**
     * The start date for the status update period.
     */
    @Column(name = "start_date")
    private LocalDate startDate;

    /**
     * The target date for the status update period.
     */
    @Column(name = "target_date")
    private LocalDate targetDate;

    /**
     * The status of the project (ON_TRACK, AT_RISK, OFF_TRACK, INACTIVE).
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private Status status;

    /**
     * The user who created the status update.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "creator_id")
    @ToString.Exclude
    private User creator;
}
