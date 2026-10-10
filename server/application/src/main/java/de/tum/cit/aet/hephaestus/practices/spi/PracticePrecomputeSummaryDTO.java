package de.tum.cit.aet.hephaestus.practices.spi;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "What one practice's precompute script needs, as its newest review reported it")
public record PracticePrecomputeSummaryDTO(
        @NonNull String practiceSlug,
        @NonNull String practiceName,

        @Nullable
        @Schema(description = "The review that reported the needs; null when no review ran the current script yet")
        PrecomputeAsOfDTO asOf,

        @NonNull
        @Schema(
                description = "True when the newest review ran an earlier version of the script, so its needs "
                        + "are not shown")
        Boolean scriptChanged,

        @NonNull @Schema(description = "The models the script declared; empty when it declares none or asOf is null")
        List<PrecomputeNeedDTO> needs) {}
