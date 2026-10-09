package de.tum.cit.aet.hephaestus.activity.overview.dto;

import java.time.Instant;
import org.jspecify.annotations.NonNull;

public record ActivityWeekDTO(
        @NonNull Instant start, @NonNull ActivityCountsDTO counts) {}
