package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.practices.model.Observation;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * Immutable many-to-many join binding a synthesized {@code Feedback} unit to the
 * {@link Observation}s it was composed from.
 *
 * <p>A single feedback unit can fuse several observations: each problem ({@code BAD}) leads as a
 * {@link EvidenceRole#PRIMARY} row (so a unit routinely carries several PRIMARYs, one per problem),
 * and each strength ({@code GOOD}) is bound as a {@link EvidenceRole#SUPPORTING} row. A single
 * observation can be reused across feedback units that target different surfaces. The composite primary
 * key {@code (feedback_id, observation_id)} makes the binding idempotent.
 *
 * <p>Append-only: like {@link Observation}, this row is written via the native, race-safe
 * {@code insertIfAbsent} upsert ({@code FeedbackObservationRepository}) rather than ORM {@code save()} —
 * the {@code @EmbeddedId}/{@code @MapsId} entity is awkward to build for a plain save, and
 * {@code ON CONFLICT DO NOTHING} is idempotent on a recorder retry. This entity has no {@code @PrePersist}
 * and no defaulted column: its composite PK is simply the two endpoint FKs, which callers supply directly.
 * The {@code @ManyToOne} navigations are read-only mirrors of the key columns (mapped via {@link MapsId})
 * so loading the join never desynchronizes the PK.
 *
 * <p>Both sides live in the {@code practices} module ({@code feedback} and {@code model}
 * sub-packages), so real {@code @ManyToOne} associations are used rather than scalar raw-UUID FKs
 * — there is no Spring-Modulith cycle to dodge here. This is the WADM <em>target</em> edge (which
 * evidence a body of commentary is about); {@link FeedbackPlacement} is the orthogonal WADM
 * <em>selector</em> edge (where it is shown).
 *
 * <p>Invariants (changelog {@code 1781092589259}): both FKs are {@code ON DELETE CASCADE}, so the
 * binding never outlives either endpoint; {@code role} is constrained to {@code PRIMARY | SUPPORTING}
 * by {@code chk_feedback_observation_role}. The PK indexes {@code feedback_id}; the reverse direction
 * (which units an observation fed) is served by {@code idx_feedback_observation_observation}, which
 * also backs the observation-side {@code ON DELETE CASCADE}.
 *
 * @see Feedback for the synthesized unit being composed
 * @see Observation for the evidence being bound
 * @see EvidenceRole for the PRIMARY/SUPPORTING weighting
 */
@Entity
@Immutable
@Table(
        name = "feedback_observation",
        indexes = {@Index(name = "idx_feedback_observation_observation", columnList = "observation_id")})
@Getter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class FeedbackObservation {

    @EmbeddedId
    @EqualsAndHashCode.Include
    private Id id;

    /**
     * The synthesized feedback unit. Uses DB-level {@code ON DELETE CASCADE} so deleting a
     * feedback unit cleans up its immutable composition rows. Read-only mirror of
     * {@code id.feedbackId}.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("feedbackId")
    @JoinColumn(
            name = "feedback_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_feedback_observation_feedback"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Feedback feedback;

    /**
     * The observation bound into this feedback unit. Uses DB-level {@code ON DELETE CASCADE} so
     * deleting an observation cleans up its composition rows. Read-only mirror of {@code id.observationId}.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("observationId")
    @JoinColumn(
            name = "observation_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_feedback_observation_observation"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Observation observation;

    /**
     * Whether this observation anchors the unit's headline ({@code PRIMARY}) or reinforces it
     * ({@code SUPPORTING}). PRIMARY is what carries the unit's severity and the F-4 policy floor
     * (every blocking observation must surface under some PRIMARY row). Constrained to the two
     * values by {@code chk_feedback_observation_role}.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 16, nullable = false)
    private EvidenceRole role;

    /** Stable render order of this observation within its feedback unit (lower = earlier). */
    @NotNull
    @Column(name = "ordinal", nullable = false)
    private Integer ordinal;

    /**
     * Composite primary key {@code (feedback_id, observation_id)} for the join.
     */
    @Embeddable
    @Getter
    @NoArgsConstructor
    @EqualsAndHashCode
    public static class Id implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Column(name = "feedback_id", columnDefinition = "UUID")
        private UUID feedbackId;

        @Column(name = "observation_id", columnDefinition = "UUID")
        private UUID observationId;
    }
}
