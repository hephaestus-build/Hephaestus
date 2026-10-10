package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.practices.spi.PrecomputeRunDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(
        description =
                "What one practice's precompute script did in the latest attempt of one review that recorded precompute "
                        + "runs")
public record ReviewPrecomputeDTO(
        @NonNull String practiceSlug,

        @Schema(description = "The practice's current name. Absent when the practice no longer exists.") @Nullable
        String practiceName,

        @NonNull PrecomputeRunDTO run,

        @NonNull
        @Schema(
                description = "The decision, embedding and reranking models of the script, with the calls the proxy "
                        + "counted for each. Calls to the review's own model count toward the review.")
        List<ReviewPrecomputeModelDTO> models,

        @Schema(description = "The first line of the script's error. Set only when the script failed.") @Nullable
        String error,

        @Schema(
                description = "How long the script ran, in milliseconds. Absent when the script did not run or did not "
                        + "finish, or the runner did not say.")
        @Nullable
        Integer durationMs) {}
