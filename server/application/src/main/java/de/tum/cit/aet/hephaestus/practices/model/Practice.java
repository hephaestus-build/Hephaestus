package de.tum.cit.aet.hephaestus.practices.model;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.PracticeAutomatedReviewPolicy;
import de.tum.cit.aet.hephaestus.practices.PracticeBinding;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * Updated column-by-column rather than whole-row, because a database trigger keys off which columns an
 * UPDATE names.
 *
 * <p>{@code practice_requires_current_revision_projection} is declared {@code AFTER UPDATE OF slug, name,
 * applies_to, bindings, criteria, …} — and Postgres fires an {@code UPDATE OF} trigger when a column
 * appears in the SET list, whether or not its value changed. Hibernate's default whole-row update names
 * every column on every save, so changing something as unrelated as {@code autonomy} re-asserted the
 * whole projection and had the deferred trigger re-check it at commit. Setting a practice's review autonomy
 * through the API therefore failed with a 409 while the identical write in SQL succeeded.
 *
 * <p>With this, an update names only what actually changed, so the trigger fires when the projection is
 * genuinely touched and stays silent otherwise — which is what it was written to mean.
 */
@DynamicUpdate
@Entity
@Table(
        name = "practice",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_practice_workspace_slug",
                    columnNames = {"workspace_id", "slug"}),
            @UniqueConstraint(
                    name = "uk_practice_workspace_id",
                    columnNames = {"id", "workspace_id"}),
        },
        indexes = {
            @Index(name = "idx_practice_workspace_autonomy", columnList = "workspace_id, autonomy"),
            @Index(name = "idx_practice_practice_group", columnList = "practice_group_id"),
            @Index(name = "idx_practice_group_order", columnList = "practice_group_id, display_order"),
            @Index(name = "idx_practice_source_curated_slug", columnList = "source_curated_slug"),
        })
@Getter
@Setter
@NoArgsConstructor
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Practice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    /**
     * Owning workspace (tenancy binding). {@code fk_practice_workspace} carries no DB cascade — a workspace
     * purge removes its practices explicitly in application code, keeping delete order over the dependent
     * observation/revision graph under the purge contributor's control.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false, foreignKey = @ForeignKey(name = "fk_practice_workspace"))
    @ToString.Exclude
    private Workspace workspace;

    /**
     * Read-only twin of {@link #workspace}'s column so the composite tenancy key on Observation can
     * reference it: Hibernate binds Observation before Practice and resolves a non-key
     * {@code referencedColumnName} only against columns already bound.
     */
    @Column(name = "workspace_id", nullable = false, insertable = false, updatable = false)
    @Setter(lombok.AccessLevel.NONE)
    private Long workspaceId;

    /** Stable machine key, unique per workspace ({@code uk_practice_workspace_slug}); survives a {@link #name} rename. */
    @Column(name = "slug", nullable = false, length = 64)
    private String slug;

    /** Admin-renameable display label. */
    @Column(name = "name", nullable = false, length = 128)
    private String name;

    /**
     * A projection of {@link #bindings}, not a fact — {@link #setBindings} derives it from the bound signals.
     * It stays a column, rather than folding into the JSONB, because repository queries filter on it and a
     * JSONB predicate would be neither indexable nor readable.
     */
    @Column(name = "applies_to", nullable = false, length = ArtifactKind.MAX_LENGTH)
    @ColumnDefault("'scm.pull_request'")
    @Setter(lombok.AccessLevel.NONE)
    private ArtifactKind artifactKind = ArtifactKinds.PULL_REQUEST;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "practice_group_id", foreignKey = @ForeignKey(name = "fk_practice_group"))
    @OnDelete(action = OnDeleteAction.SET_NULL)
    @ToString.Exclude
    private @Nullable PracticeGroup group;

    /** Catalog slug retained across workspace edits. */
    @Column(name = "source_curated_slug", length = 64)
    private @Nullable String sourceCuratedSlug;

    /** Catalog comparison fingerprint captured when the workspace copy is created. */
    @Column(name = "source_curated_fingerprint", length = 96)
    private @Nullable String sourceCuratedFingerprint;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_revision_id", foreignKey = @ForeignKey(name = "fk_practice_current_revision"))
    @ToString.Exclude
    private PracticeRevision currentRevision;

    @Column(name = "display_order", nullable = false)
    @ColumnDefault("0")
    private int displayOrder = 0;

    /**
     * The occasions this practice is reviewed on and the evidence each reads, stored as a JSONB array; the
     * detection gate starts a review only when the observed signal is bound here. Named {@code bindings}
     * rather than {@code on} because {@code ON} is reserved SQL (the authoring file still spells it {@code on}).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "bindings", columnDefinition = "jsonb", nullable = false)
    @ToString.Exclude
    @Setter(lombok.AccessLevel.NONE)
    private List<PracticeBinding> bindings = List.of();

    /**
     * The detection rubric the agent evaluates the artifact against — the rule's normative text, never shown
     * to developers. The {@code DEFECT-DETECTOR DISCIPLINE} marker token (see {@link #isDefectDetector()}) lives
     * in this text.
     */
    @Column(name = "criteria", columnDefinition = "TEXT", nullable = false)
    @ToString.Exclude
    private String criteria;

    /**
     * Developer-facing rationale for why this practice matters, in plain language — never the detection
     * rubric. MUST NOT leak detection vocabulary (PRESENT/ABSENT/GOOD/BAD/NOT_APPLICABLE), enforced by the
     * same authoring guard as {@link #whatGoodLooksLike}.
     */
    @Column(name = "why_it_matters", columnDefinition = "TEXT")
    @ToString.Exclude
    private @Nullable String whyItMatters;

    /**
     * Developer-facing exemplar: a concrete instance of doing this well, not the rubric. MUST NOT restate
     * {@link #criteria} or leak detection vocabulary (PRESENT/ABSENT/GOOD/BAD/NOT_APPLICABLE), enforced by
     * an authoring guard.
     */
    @Column(name = "what_good_looks_like", columnDefinition = "TEXT")
    @ToString.Exclude
    private @Nullable String whatGoodLooksLike;

    /**
     * Optional Node/TypeScript static-analysis script that runs before the AI agent and produces structured
     * hints (not observations) as starting points; {@code null} means no precomputation runs.
     */
    @Column(name = "precompute_script", columnDefinition = "TEXT")
    @ToString.Exclude
    private @Nullable String precomputeScript;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "automated_review_policy", columnDefinition = "jsonb", nullable = false)
    @ToString.Exclude
    private PracticeAutomatedReviewPolicy automatedReviewPolicy;

    /**
     * This practice's own answer to how much autonomy the system has over it, or {@code null} to inherit its
     * group's (and through it the workspace's) — an autonomy rather than a boolean so silencing a noisy practice
     * does not also cost its measurement.
     *
     * <p>Never read raw for a decision: {@code null} means "holds no opinion", not {@code OFF}. Resolve it
     * with {@link de.tum.cit.aet.hephaestus.practices.review.autonomy.AutonomyResolver}.
     */
    @Column(name = "autonomy", length = PracticeAutonomy.MAX_LENGTH)
    @Enumerated(EnumType.STRING)
    private @Nullable PracticeAutonomy autonomy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * Sets the occasions this practice is reviewed on, and re-derives {@link #artifactKind} from them.
     *
     * <p>The only writer of the kind, so the projection cannot drift from the bindings it projects.
     */
    public void setBindings(List<PracticeBinding> bindings) {
        this.bindings = List.copyOf(bindings);
        this.artifactKind = PracticeBinding.artifactKindOf(this.bindings);
    }

    @PrePersist
    public void prePersist() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }

    /** Whether the criteria declare an undesirable target; its bounded absence is a positive outcome. */
    public boolean isDefectDetector() {
        return declaredTargetAssessment(criteria) == Assessment.BAD;
    }
    /** The target declared by a criteria revision, independent of whether it was observed. */
    public static @Nullable Assessment declaredTargetAssessment(@Nullable String criteria) {
        if (criteria == null) return null;
        boolean good = criteria.contains("TARGET ASSESSMENT: GOOD");
        boolean bad = criteria.contains("TARGET ASSESSMENT: BAD");
        if (good && bad) throw new IllegalArgumentException("A practice must declare one fixed target assessment");
        if (good) return Assessment.GOOD;
        if (bad || criteria.contains("DEFECT-DETECTOR DISCIPLINE")) return Assessment.BAD;
        return null;
    }
}
