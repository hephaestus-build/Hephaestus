package de.tum.cit.aet.hephaestus.activity.overview.dto;

import de.tum.cit.aet.hephaestus.activity.overview.ActivityNameOrder;
import java.util.List;
import org.jspecify.annotations.NonNull;

/** Identifiers refer only to the people in the same response. */
public record ActivityHighlightsDTO(
        @ActivityNameOrder @NonNull List<Long> firstContributors) {}
