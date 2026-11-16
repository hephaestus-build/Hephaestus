package de.tum.cit.aet.hephaestus.practices.curated.adoption;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.practices.CanonicalDigest;
import de.tum.cit.aet.hephaestus.practices.GroupDefinition;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogPracticeDocument;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import org.jspecify.annotations.Nullable;

public record CatalogAdoptionPlan(
        String slug,
        CatalogPracticeDocument document,
        CatalogAdoptionAvailability availability,
        CatalogGroupDisposition groupDisposition,
        @Nullable String groupSlug,
        @Nullable GroupDefinition groupDefinition,
        int groupPosition,
        FeedbackMode initialFeedbackMode,
        String offeredDigest,
        String etag) {
    static CatalogAdoptionPlan create(
            String slug,
            CatalogPracticeDocument document,
            CatalogAdoptionAvailability availability,
            CatalogGroupDisposition disposition,
            @Nullable String groupSlug,
            @Nullable GroupDefinition group,
            int position,
            String offeredDigest) {
        String tag = new CanonicalDigest()
                .add(slug)
                .add(offeredDigest)
                .add(availability.name())
                .add(disposition.name())
                .addNullable(groupSlug)
                .addNullable(group == null ? null : group.name())
                .addNullable(group == null ? null : group.description())
                .addNullable(group == null ? null : group.icon())
                .addNullable(group == null ? null : group.color())
                .addInt(position)
                .hex();
        return new CatalogAdoptionPlan(
                slug,
                document,
                availability,
                disposition,
                groupSlug,
                group,
                position,
                FeedbackMode.APPROVE_FIRST,
                offeredDigest,
                tag);
    }

    public CatalogPracticePreviewDTO preview() {
        return new CatalogPracticePreviewDTO(
                slug,
                document.kind().value(),
                document.definition(),
                availability,
                new CatalogAdoptionGroupDTO(groupDisposition, groupSlug, groupDefinition),
                initialFeedbackMode,
                offeredDigest,
                EntityTagPrecondition.format(etag));
    }
}
