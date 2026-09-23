package de.tum.cit.aet.hephaestus.practices.curated;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.AdoptedBaseSource;
import de.tum.cit.aet.hephaestus.practices.PracticeAutomatedReviewPolicy;
import de.tum.cit.aet.hephaestus.practices.PracticeBinding;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeDeliveryBehavior;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "curated_practice_override")
@Getter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class CuratedPracticeOverride {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "slug", nullable = false, length = 64, updatable = false)
    private String slug;

    @Column(name = "name", length = 128)
    private @Nullable String name;

    /** Projection of {@link #bindings}, kept for the same reason {@code practice.applies_to} is. */
    @Column(name = "applies_to", length = ArtifactKind.MAX_LENGTH)
    private @Nullable ArtifactKind artifactKind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "bindings", columnDefinition = "jsonb")
    private @Nullable List<PracticeBinding> bindings;

    @Column(name = "criteria", columnDefinition = "TEXT")
    private @Nullable String criteria;

    @Column(name = "precompute_script", columnDefinition = "TEXT")
    private @Nullable String precomputeScript;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "automated_review_policy", columnDefinition = "jsonb")
    private @Nullable PracticeAutomatedReviewPolicy automatedReviewPolicy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "delivery_behavior", columnDefinition = "jsonb")
    private @Nullable PracticeDeliveryBehavior deliveryBehavior;

    @Column(name = "why_it_matters", columnDefinition = "TEXT")
    private @Nullable String whyItMatters;

    @Column(name = "what_good_looks_like", columnDefinition = "TEXT")
    private @Nullable String whatGoodLooksLike;

    @Column(name = "group_slug", length = 64)
    private @Nullable String groupSlug;

    @Column(name = "position")
    private @Nullable Integer position;

    @Column(name = "based_on_digest", length = 128)
    private @Nullable String acceptedBundledDigest;

    /** Bundled definition on which this customization was based. Null for instance-authored entries. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "adopted_base", columnDefinition = "jsonb")
    private @Nullable PracticeDefinition adoptedBase;

    @Column(name = "adopted_base_source", length = 32)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private @Nullable AdoptedBaseSource adoptedBaseSource;

    @Column(name = "retired_at")
    private @Nullable Instant retiredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private @Nullable Long version;

    public CuratedPracticeOverride(String slug, Instant now) {
        this.slug = Objects.requireNonNull(slug, "slug");
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public @Nullable PracticeDefinition definition() {
        if (name == null
                || artifactKind == null
                || bindings == null
                || criteria == null
                || automatedReviewPolicy == null) {
            return null;
        }
        return new PracticeDefinition(
                name,
                bindings,
                criteria,
                precomputeScript,
                automatedReviewPolicy,
                whyItMatters,
                whatGoodLooksLike,
                groupSlug,
                deliveryBehavior == null ? PracticeDeliveryBehavior.DEFAULT : deliveryBehavior);
    }

    public void write(PracticeDefinition definition, @Nullable String acceptedBundledDigest, Instant now) {
        this.name = definition.name();
        this.artifactKind = definition.artifactKind();
        this.bindings = definition.bindings();
        this.criteria = definition.criteria();
        this.precomputeScript = definition.precomputeScript();
        this.automatedReviewPolicy = definition.automatedReviewPolicy();
        this.deliveryBehavior = definition.deliveryBehavior();
        this.whyItMatters = definition.whyItMatters();
        this.whatGoodLooksLike = definition.whatGoodLooksLike();
        this.groupSlug = definition.groupSlug();
        this.acceptedBundledDigest = acceptedBundledDigest;
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    public void clearDefinition(Instant now) {
        this.name = null;
        this.artifactKind = null;
        this.bindings = null;
        this.criteria = null;
        this.precomputeScript = null;
        this.automatedReviewPolicy = null;
        this.deliveryBehavior = null;
        this.whyItMatters = null;
        this.whatGoodLooksLike = null;
        this.groupSlug = null;
        this.acceptedBundledDigest = null;
        this.adoptedBase = null;
        this.adoptedBaseSource = null;
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    public void adoptBundledBase(PracticeDefinition bundled) {
        this.adoptedBase = bundled;
        this.adoptedBaseSource = AdoptedBaseSource.EXACT_ADOPTION;
        this.acceptedBundledDigest = CuratedDefinitionDigest.of(slug, bundled);
    }

    public void acceptBundledRelease(PracticeDefinition merged, PracticeDefinition bundled, Instant now) {
        write(merged, CuratedDefinitionDigest.of(slug, bundled), now);
        adoptBundledBase(bundled);
    }

    public void backfillBase(@Nullable PracticeDefinition bundled) {
        if (adoptedBase != null || acceptedBundledDigest == null) {
            return;
        }
        PracticeDefinition current = definition();
        if (current == null) {
            return;
        }
        if (bundled != null && CuratedDefinitionDigest.of(slug, bundled).equals(acceptedBundledDigest)) {
            adoptedBase = bundled;
            adoptedBaseSource = AdoptedBaseSource.BUNDLED_DIGEST_MATCH;
        } else {
            adoptedBase = current;
            adoptedBaseSource = AdoptedBaseSource.CURRENT_DEFINITION;
        }
    }

    public void acknowledge(@Nullable String shippedDigest, Instant now) {
        this.acceptedBundledDigest = shippedDigest;
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    public void setStatus(CuratedStatus status, Instant now) {
        this.retiredAt = status == CuratedStatus.RETIRED ? now : null;
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    public void setPosition(int position, Instant now) {
        this.position = position;
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    public void clearPosition(Instant now) {
        this.position = null;
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    public boolean isEmpty() {
        return definition() == null && retiredAt == null && position == null;
    }
}
