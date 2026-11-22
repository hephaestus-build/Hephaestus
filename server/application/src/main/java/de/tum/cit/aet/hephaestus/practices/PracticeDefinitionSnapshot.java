package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditSnapshot;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeEditedIn;
import de.tum.cit.aet.hephaestus.practices.model.PracticeState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the configuration audit keeps of a practice. {@code sourceCommit} is the commit of the practice repository that
 * the current version was read at, so a change that a sync makes names its commit.
 */
record PracticeDefinitionSnapshot(
        String slug,
        PracticeState state,
        PracticeEditedIn editedIn,
        String kind,
        Long versionId,
        String reviewDigest,
        @Nullable String sourceCommit,
        @Nullable String groupSlug,
        int position,
        @Nullable FeedbackMode feedbackMode,
        @Nullable String catalogSlug,
        @Nullable String catalogBaseDigest)
        implements ConfigAuditSnapshot {
    static PracticeDefinitionSnapshot of(Practice practice, JsonMapper mapper) {
        return new PracticeDefinitionSnapshot(
                practice.getSlug(),
                practice.getState(),
                practice.getEditedIn(),
                practice.getKind().value(),
                Objects.requireNonNull(practice.getCurrentVersion().getId()),
                practice.getCurrentVersion().getReviewDigest(),
                practice.getCurrentVersion().getSourceCommit(),
                practice.getGroup() == null ? null : practice.getGroup().getSlug(),
                practice.getPosition(),
                practice.getFeedbackMode(),
                practice.getCatalogSlug(),
                practice.getCatalogBase() == null
                        ? null
                        : new CanonicalDigest()
                                .add(mapper.writeValueAsString(practice.getCatalogBase()))
                                .hex());
    }
}
