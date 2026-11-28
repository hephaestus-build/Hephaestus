package de.tum.cit.aet.hephaestus.integration.submission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The note that the submitter sends with a version, such as a rationale. It is context and never evidence: no citation
 * points into it. The database deletes it when its version ends.
 */
@Entity
@Table(
        name = "team_context",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_team_context_version",
                        columnNames = {"version_id", "workspace_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TeamContext {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "UUID")
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @Column(name = "version_id", nullable = false, columnDefinition = "UUID")
    private UUID versionId;

    @Column(name = "text", nullable = false, columnDefinition = "TEXT")
    private String text;

    /** Whether the sender let a review read the note. */
    @Column(name = "shared_with_review", nullable = false)
    private boolean sharedWithReview;
}
