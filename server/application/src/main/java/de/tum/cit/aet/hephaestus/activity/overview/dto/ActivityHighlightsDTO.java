package de.tum.cit.aet.hephaestus.activity.overview.dto;

import java.util.List;
import org.jspecify.annotations.NonNull;

/** Identifiers refer only to the people in the same response. */
public record ActivityHighlightsDTO(
        @NonNull List<Long> firstContributors, @NonNull List<Long> mostPeopleHelped) {}
