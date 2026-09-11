package de.tum.cit.aet.hephaestus.practices.model;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Immutable assessment of one practice on one work artifact. Presence and assessment are separate axes;
 * later reviews append a new row linked by {@link #recurrenceKey}.
 */
@Entity
@Immutable
@Table(
        name = "observation",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_observation_occurrence",
                    columnNames = {"occurrence_key"})
        },
        indexes = {
            @Index(name = "idx_observation_practice_observed", columnList = "practice_id, observed_at DESC"),
            @Index(name = "idx_observation_agent_job", columnList = "agent_job_id"),
            @Index(name = "idx_observation_workspace", columnList = "workspace_id"),
            @Index(name = "idx_observation_target", columnList = "artifact_kind, artifact_id"),
            @Index(
                    name = "idx_observation_target_run",
                    columnList = "artifact_kind, artifact_id, agent_job_id, observed_at DESC"),
            @Index(name = "idx_observation_correlation", columnList = "recurrence_key"),
            // Observations are filed against the subject (about_user_id); index for subject dashboards.
            @Index(name = "idx_observation_subject", columnList = "about_user_id"),
        })
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Observation {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    /**
     * Per-occurrence dedup grain: identifies this one detection event so {@code insertIfAbsent} is
     * idempotent. Enforced unique by {@code uk_observation_occurrence}; distinct from the cross-run
     * {@link #recurrenceKey}.
     */
    @NotNull
    @Column(name = "occurrence_key", nullable = false, length = 255)
    private String occurrenceKey;

    /** The producing job, stored as a scalar to keep the persistence model independent of the agent module. */
    @NotNull
    @Column(name = "agent_job_id", nullable = false, columnDefinition = "UUID")
    private UUID agentJobId;

    @NotNull
    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    /**
     * The practice measured. Deliberately not cascade-deleted: an observation is immutable and the
     * substrate for longitudinal research, so pruning a practice must not erase everyone's history
     * against it — retire it instead.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "practice_id", nullable = false, foreignKey = @ForeignKey(name = "fk_observation_practice"))
    private Practice practice;

    /** Read-only view of the tenancy key: the practice must belong to the observation's workspace. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns(
            value = {
                @JoinColumn(name = "practice_id", referencedColumnName = "id", insertable = false, updatable = false),
                @JoinColumn(
                        name = "workspace_id",
                        referencedColumnName = "workspace_id",
                        insertable = false,
                        updatable = false),
            },
            foreignKey = @ForeignKey(name = "fk_observation_practice_workspace"))
    @Getter(AccessLevel.NONE)
    private @Nullable Practice tenantOwnedPractice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "practice_revision_id", foreignKey = @ForeignKey(name = "fk_observation_revision"))
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private @Nullable PracticeRevision practiceRevision;

    @NotNull
    @Column(name = "artifact_kind", length = ArtifactKind.MAX_LENGTH, nullable = false)
    private ArtifactKind artifactKind;

    @NotNull
    @Column(name = "artifact_id", nullable = false)
    private Long artifactId;

    /**
     * Whose conduct the observation is ABOUT — always populated (ADR 0022 §3): the author for author-side
     * practices, the reviewer for reviewer-side ones. Visibility and the feedback recipient key off this
     * column, not a static role — contrast with the delivery's {@code recipient_user_id} (who feedback
     * goes TO).
     *
     * <p>Raw {@code Long} FK, no {@code @ManyToOne}: DB FK {@code sfk_observation_subject}, whose
     * {@code sfk_} prefix marks it a deliberate scalar FK so the Liquibase schema-drift gate treats it as
     * intentional rather than Hibernate drift. No {@code ON DELETE} because the column is {@code NOT NULL}
     * — a referenced user delete must be blocked, not silently nulled.
     */
    @NotNull
    @Column(name = "about_user_id", nullable = false)
    private Long aboutUserId;

    /**
     * Cross-run locus grain: a deterministic hash of WHAT the observation is about (practice + target +
     * subject + a content anchor), never of WHEN, computed by
     * {@link de.tum.cit.aet.hephaestus.practices.observation.ObservationFingerprint}. Lets a
     * {@code Feedback} supersede rather than re-post and lets a reaction follow one locus across
     * re-detections. NULL means the observation predates the fingerprint, not a missing reference.
     */
    @Column(name = "recurrence_key", length = 64)
    private String recurrenceKey;

    @NotNull
    @Column(name = "summary", nullable = false, length = 255)
    private String summary;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "assessment_status", length = 16, nullable = false)
    private AssessmentStatus assessmentStatus;

    /** The fixed target's presence; null unless assessed. */
    @Enumerated(EnumType.STRING)
    @Column(name = "presence", length = 16)
    private Presence presence;

    /** The judgment of the work; null unless assessed. */
    @Enumerated(EnumType.STRING)
    @Column(name = "assessment", length = 8)
    private Assessment assessment;

    /**
     * How this measurement was occasioned — see {@link ObservationOrigin}. NOT NULL with a {@code LIVE}
     * default so the column can be added to an {@code @Immutable} table without a rewrite pass: every row
     * that existed before the column did was produced by the event-driven path, which is exactly LIVE.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "origin", length = 16, nullable = false)
    @ColumnDefault("'LIVE'")
    @Builder.Default
    private ObservationOrigin origin = ObservationOrigin.LIVE;

    /**
     * Impact band — required for a {@link Outcome#NEGATIVE} outcome; NULL on a positive or
     * unassessed row. The database and {@link AssessmentStatus#validate} enforce the same invariant.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 16)
    private Severity severity;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence", columnDefinition = "jsonb")
    private JsonNode evidence;

    @Column(name = "evidence_rationale", columnDefinition = "TEXT")
    private String evidenceRationale;

    @NotNull
    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    /** The result is derived, never persisted separately from its axes. */
    @jakarta.persistence.Transient
    public @org.jspecify.annotations.Nullable Outcome getOutcome() {
        return Outcome.of(presence, assessment);
    }

    /**
     * JPA-path safety net only: the production write path is the native
     * {@code ObservationRepository.insertIfAbsent} (this entity is {@code @Immutable}; nothing calls
     * {@code save()}), so this never fires in prod. Kept so the coherence invariants documented on
     * {@link #assessment} and {@link #severity} stay enforced for any future caller that does persist
     * through JPA.
     */
    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (observedAt == null) {
            observedAt = Instant.now();
        }
        if (origin == null) {
            origin = ObservationOrigin.LIVE;
        }
        if (assessmentStatus == null) {
            throw new IllegalStateException("Assessment status is required");
        }
        assessmentStatus.validate(presence, assessment, severity);
    }
}
