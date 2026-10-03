package de.tum.cit.aet.hephaestus.practices.curated.dto;

import de.tum.cit.aet.hephaestus.practices.curated.EffectiveCatalog;
import org.jspecify.annotations.NonNull;

public record CuratedCatalogSummaryDTO(
        @NonNull Integer total,
        @NonNull Integer updatesChangingReview,
        @NonNull Integer updatesChangingWordingOnly,
        @NonNull Integer updatesChangingPresentation,
        @NonNull Integer editedHere,
        @NonNull Integer yours,
        @NonNull Integer notOffered,
        @NonNull Integer noLongerShipped) {
    public static CuratedCatalogSummaryDTO from(EffectiveCatalog.CatalogSummary summary) {
        return new CuratedCatalogSummaryDTO(
                summary.total(),
                summary.updatesChangingReview(),
                summary.updatesChangingWordingOnly(),
                summary.updatesChangingPresentation(),
                summary.editedHere(),
                summary.yours(),
                summary.notOffered(),
                summary.noLongerShipped());
    }
}
