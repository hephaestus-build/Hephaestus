package de.tum.cit.aet.hephaestus.practices.model;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.PracticeAutomatedReviewPolicy;
import de.tum.cit.aet.hephaestus.practices.PracticeBinding;
import de.tum.cit.aet.hephaestus.practices.ReviewRuleFingerprint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * An immutable, append-only snapshot of a {@link Practice}: SCD-2 over the whole definition, not only
 * over {@code criteria}. {@code Observation.practiceRevision} pins each observation to the revision the
 * detector saw, and an observation written before versioning pins {@code null}.
 *
 * <p><strong>The snapshot must stay complete.</strong> Every field a review reads off a practice is
 * copied here by the constructor; a field added to {@link Practice} and not added here reproduces a
 * past observation against today's value of it, silently. {@code revisionNumber} is 1-based and
 * monotonic per practice ({@code uk_practice_revision_practice_number}).
 */
@Entity
@Immutable
@Table(
        name = "practice_revision",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_practice_revision_practice_number",
                    columnNames = {"practice_id", "revision_number"}),
        },
        indexes = {@Index(name = "idx_practice_revision_practice", columnList = "practice_id")})
@Getter
@NoArgsConstructor
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class PracticeRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private @Nullable Long id;

    /** The practice this revision belongs to. CASCADE: deleting a practice removes its revision history. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "practice_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_practice_revision_practice"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private Practice practice;

    @Column(name = "revision_number", nullable = false)
    private int revisionNumber;

    @Column(name = "slug", length = 64)
    private String slug;

    @Column(name = "name", length = 128)
    private String name;

    @Column(name = "applies_to", length = ArtifactKind.MAX_LENGTH)
    private ArtifactKind artifactKind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "bindings", columnDefinition = "jsonb")
    @ToString.Exclude
    private List<PracticeBinding> bindings;

    @Column(name = "criteria", columnDefinition = "TEXT", nullable = false)
    @ToString.Exclude
    private String criteria;

    @Column(name = "precompute_script", columnDefinition = "TEXT")
    @ToString.Exclude
    private @Nullable String precomputeScript;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "automated_review_policy", columnDefinition = "jsonb")
    @ToString.Exclude
    private PracticeAutomatedReviewPolicy automatedReviewPolicy;

    @Column(name = "why_it_matters", columnDefinition = "TEXT")
    @ToString.Exclude
    private @Nullable String whyItMatters;

    @Column(name = "what_good_looks_like", columnDefinition = "TEXT")
    @ToString.Exclude
    private @Nullable String whatGoodLooksLike;

    @Column(name = "group_slug", length = 64)
    private @Nullable String groupSlug;

    @Column(name = "group_name", length = 128)
    private @Nullable String groupName;

    @Column(name = "group_description", columnDefinition = "TEXT")
    private @Nullable String groupDescription;

    @Column(name = "group_icon", length = 64)
    private @Nullable String groupIcon;

    @Column(name = "group_color", length = 32)
    private @Nullable String groupColor;

    @Column(name = "review_rule_fingerprint", length = 96)
    private String reviewRuleFingerprint;

    @Column(name = "created_at", nullable = false, updatable = false)
    private @Nullable Instant createdAt;

    public PracticeRevision(Practice practice, int revisionNumber) {
        this.practice = Objects.requireNonNull(practice, "practice");
        if (revisionNumber < 1) {
            throw new IllegalArgumentException("revisionNumber must be >= 1, got " + revisionNumber);
        }
        this.revisionNumber = revisionNumber;
        this.slug = Objects.requireNonNull(practice.getSlug(), "practice.slug");
        this.name = Objects.requireNonNull(practice.getName(), "practice.name");
        this.artifactKind = Objects.requireNonNull(practice.getArtifactKind(), "practice.artifactKind");
        this.bindings = List.copyOf(Objects.requireNonNull(practice.getBindings(), "practice.bindings"));
        this.criteria = Objects.requireNonNull(practice.getCriteria(), "practice.criteria");
        this.precomputeScript = practice.getPrecomputeScript();
        this.automatedReviewPolicy =
                Objects.requireNonNull(practice.getAutomatedReviewPolicy(), "practice.automatedReviewPolicy");
        this.whyItMatters = practice.getWhyItMatters();
        this.whatGoodLooksLike = practice.getWhatGoodLooksLike();
        PracticeGroup group = practice.getGroup();
        if (group != null) {
            this.groupSlug = group.getSlug();
            this.groupName = group.getName();
            this.groupDescription = group.getDescription();
            this.groupIcon = group.getIcon();
            this.groupColor = group.getColor();
        }
        this.reviewRuleFingerprint = calculateReviewRuleFingerprint();
    }

    public String computeReviewRuleFingerprint() {
        return calculateReviewRuleFingerprint();
    }

    private String calculateReviewRuleFingerprint() {
        return ReviewRuleFingerprint.of(
                slug, name, bindings, criteria, precomputeScript, automatedReviewPolicy, groupSlug);
    }

    @PrePersist
    void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}
