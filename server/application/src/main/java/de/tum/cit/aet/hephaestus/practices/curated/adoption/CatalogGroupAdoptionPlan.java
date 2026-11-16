package de.tum.cit.aet.hephaestus.practices.curated.adoption;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.practices.CanonicalDigest;
import de.tum.cit.aet.hephaestus.practices.GroupDefinition;
import java.util.List;

public record CatalogGroupAdoptionPlan(
        String slug,
        GroupDefinition definition,
        CatalogGroupDisposition disposition,
        int position,
        List<CatalogAdoptionPlan> practices,
        List<CatalogGroupPracticeActionDTO> actions,
        String etag) {
    static CatalogGroupAdoptionPlan create(
            String slug,
            GroupDefinition definition,
            CatalogGroupDisposition disposition,
            int position,
            List<CatalogAdoptionPlan> practices,
            List<CatalogGroupPracticeActionDTO> actions) {
        CanonicalDigest digest = new CanonicalDigest()
                .add(slug)
                .add(definition.name())
                .addNullable(definition.description())
                .addNullable(definition.icon())
                .addNullable(definition.color())
                .add(disposition.name())
                .addInt(position);
        practices.forEach(practice -> digest.add(practice.etag()));
        actions.forEach(action -> digest.add(action.slug()).add(action.action().name()));
        return new CatalogGroupAdoptionPlan(
                slug, definition, disposition, position, List.copyOf(practices), List.copyOf(actions), digest.hex());
    }

    public CatalogGroupAdoptionPreviewDTO preview() {
        return new CatalogGroupAdoptionPreviewDTO(
                slug,
                definition,
                disposition,
                practices.stream().map(CatalogAdoptionPlan::preview).toList(),
                actions,
                EntityTagPrecondition.format(etag));
    }
}
