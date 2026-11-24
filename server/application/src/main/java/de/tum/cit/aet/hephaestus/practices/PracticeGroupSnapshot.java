package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditSnapshot;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import org.jspecify.annotations.Nullable;

record PracticeGroupSnapshot(
        String slug,
        String name,
        @Nullable String description,
        boolean shownToPeople,
        @Nullable String icon,
        @Nullable String color,
        int position,
        @Nullable FeedbackMode feedbackMode,
        @Nullable String catalogSlug,
        @Nullable String catalogBaseDigest)
        implements ConfigAuditSnapshot {
    static PracticeGroupSnapshot of(PracticeGroup group) {
        return new PracticeGroupSnapshot(
                group.getSlug(),
                group.getName(),
                group.getDescription(),
                group.isShownToPeople(),
                group.getIcon(),
                group.getColor(),
                group.getPosition(),
                group.getFeedbackMode(),
                group.getCatalogSlug(),
                group.getCatalogBase() == null
                        ? null
                        : new CanonicalDigest()
                                .add(group.getCatalogBase().name())
                                .addNullable(group.getCatalogBase().description())
                                .addNullable(group.getCatalogBase().icon())
                                .addNullable(group.getCatalogBase().color())
                                .hex());
    }
}
