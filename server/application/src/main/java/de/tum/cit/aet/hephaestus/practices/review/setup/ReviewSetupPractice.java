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

/** One practice that a review setup selects. */
@Entity
@Table(
        name = "review_setup_practice",
        indexes = {
            @Index(name = "idx_review_setup_practice_setup", columnList = "setup_id, workspace_id"),
            @Index(name = "idx_review_setup_practice_practice", columnList = "practice_id, workspace_id")
        })
@IdClass(ReviewSetupPractice.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewSetupPractice {

    @Id
    @Column(name = "setup_id", nullable = false, columnDefinition = "UUID")
    private UUID setupId;

    @Id
    @Column(name = "practice_id", nullable = false)
    private long practiceId;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    public record Key(UUID setupId, long practiceId) implements Serializable {}
}
