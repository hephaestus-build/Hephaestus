package de.tum.cit.aet.hephaestus.practices.curated;

import de.tum.cit.aet.hephaestus.practices.CanonicalDigest;
import de.tum.cit.aet.hephaestus.practices.CatalogDefinition;
import de.tum.cit.aet.hephaestus.practices.GroupDefinition;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record CatalogEntry<D extends CatalogDefinition>(
        String slug,
        D effective,
        @Nullable D shipped,
        @Nullable D overridden,
        @Nullable String acceptedBundledDigest,
        boolean retired,
        int position,
        @Nullable Instant updatedAt) {
    public CatalogEntry {
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(effective, "effective");
    }

    public static <D extends CatalogDefinition> CatalogEntry<D> shippedOnly(String slug, D shipped, int position) {
        return new CatalogEntry<>(slug, shipped, shipped, null, null, false, position, null);
    }

    public CatalogEntryState state() {
        if (overridden == null) {
            return shipped == null ? CatalogEntryState.NO_LONGER_SHIPPED : CatalogEntryState.FROM_HEPHAESTUS;
        }
        if (shipped == null) {
            return acceptedBundledDigest == null ? CatalogEntryState.YOURS : CatalogEntryState.NO_LONGER_SHIPPED;
        }
        if (CuratedDefinitionDigest.of(slug, shipped).equals(CuratedDefinitionDigest.of(slug, overridden))) {
            return CatalogEntryState.EDITED_HERE;
        }
        return CuratedDefinitionDigest.of(slug, shipped).equals(acceptedBundledDigest)
                ? CatalogEntryState.EDITED_HERE
                : CatalogEntryState.UPDATE_WAITING;
    }

    /** Classifies the consequence of applying the bundled definition, not the cause of the difference. */
    public CatalogChangeKind changeKind() {
        if (shipped == null || overridden == null) {
            return CatalogChangeKind.NONE;
        }
        if (CuratedDefinitionDigest.of(slug, shipped).equals(CuratedDefinitionDigest.of(slug, overridden))) {
            return CatalogChangeKind.NONE;
        }
        if (overridden instanceof GroupDefinition) {
            return CatalogChangeKind.PRESENTATION;
        }
        return shipped.provenanceFingerprint(slug).equals(overridden.provenanceFingerprint(slug))
                ? CatalogChangeKind.WORDING
                : CatalogChangeKind.REVIEW;
    }

    public boolean offered() {
        return !retired;
    }

    public String etag() {
        return new CanonicalDigest()
                .add(slug)
                .add(CuratedDefinitionDigest.of(slug, effective))
                .addNullable(shipped == null ? null : CuratedDefinitionDigest.of(slug, shipped))
                .addNullable(acceptedBundledDigest)
                .add(state().name())
                .add(changeKind().name())
                .addInt(retired ? 1 : 0)
                .hex();
    }
}
