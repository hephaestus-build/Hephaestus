package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditSnapshot;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import org.jspecify.annotations.Nullable;

/**
 * @param autonomy null means the group holds no autonomy and follows the workspace default — a real state, so it is
 *     serialized
 */
record PracticeGroupSnapshot(
        String slug,
        String name,
        @Nullable String description,
        boolean visibleInPracticeDashboards,
        @Nullable String icon,
        @Nullable String color,
        @Nullable PracticeAutonomy autonomy)
        implements ConfigAuditSnapshot {
    static PracticeGroupSnapshot of(PracticeGroup group) {
        return new PracticeGroupSnapshot(
                group.getSlug(),
                group.getName(),
                group.getDescription(),
                group.isVisibleInPracticeDashboards(),
                group.getIcon(),
                group.getColor(),
                group.getAutonomy());
    }
}
