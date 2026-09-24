package de.tum.cit.aet.hephaestus.practices.observation;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Filter and ordering for the developer observation feed, decoupled from the web layer so the service
 * signature stays readable as filters accumulate.
 *
 * <p>Distinct from {@link ObservationQueryFilter}, which is the admin/cross-developer query shape with
 * multi-valued facets. This record is the single-developer feed: at most one practice, one group, one
 * presence, plus the repeatable artifact-kind and severity facets the developer surfaces expose.
 *
 * @param mostSevereFirst only meaningful when {@code sort} is {@code SEVERITY}; the date sort carries its
 *     direction on the {@code Pageable} instead
 */
public record ObservationFeedQuery(
        @Nullable String practiceSlug,
        @Nullable String groupSlug,
        @Nullable AssessmentStatus assessmentStatus,
        @Nullable Presence presence,
        @Nullable List<ArtifactKind> artifactKinds,
        @Nullable List<Severity> severities,
        boolean displayableOnly,
        ObservationService.ObservationSort sort,
        boolean mostSevereFirst) {}
