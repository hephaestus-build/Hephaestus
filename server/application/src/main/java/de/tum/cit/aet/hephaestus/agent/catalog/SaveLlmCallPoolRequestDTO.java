package de.tum.cit.aet.hephaestus.agent.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** The slug names the pool's metrics, so it is set once at creation and cannot change. */
@Schema(description = "Create or change a pool of provider capacity")
public record SaveLlmCallPoolRequestDTO(
        @NonNull
        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*")
        @Schema(description = "Unique slug, lowercase words joined by hyphens; set at creation only")
        String slug,

        @NonNull @NotBlank @Size(max = 128) @Schema(description = "Human-readable name")
        String displayName,

        @Nullable @Min(1) @Schema(description = "Most model calls in flight at once; null does not limit them")
        Integer maxConcurrentCalls,

        @Min(0) @Schema(description = "Calls only Heph conversations may use", requiredMode = RequiredMode.REQUIRED)
        int interactiveReserve,

        @Nullable @Min(1) Integer requestsPerMinute,
        @Nullable @Min(1) Long tokensPerMinute,
        @Nullable @Min(1) Long tokensPerCall) {

    @AssertTrue(message = "The interactive reserve must leave background capacity below the maximum, and needs one")
    @Schema(hidden = true)
    public boolean isReserveWithinMaximum() {
        return maxConcurrentCalls == null ? interactiveReserve == 0 : interactiveReserve < maxConcurrentCalls;
    }

    @AssertTrue(message = "A token rate needs a per-call token bound no larger than the rate")
    @Schema(hidden = true)
    public boolean isTokenBoundValid() {
        return tokensPerMinute == null
                ? tokensPerCall == null
                : tokensPerCall != null
                        && tokensPerCall > 0
                        && tokensPerMinute / tokensPerCall > interactiveReserve
                        && tokensPerCall <= backgroundTokenBudget();
    }

    private long backgroundTokenBudget() {
        if (tokensPerMinute == null) return 0;
        if (maxConcurrentCalls != null && maxConcurrentCalls <= 0) return 0;
        if (maxConcurrentCalls == null || interactiveReserve == 0) return tokensPerMinute;
        long background = Math.max(0, maxConcurrentCalls - interactiveReserve);
        return (tokensPerMinute / maxConcurrentCalls) * background
                + (tokensPerMinute % maxConcurrentCalls) * background / maxConcurrentCalls;
    }

    @AssertTrue(message = "A request rate must leave a background request after the interactive reserve")
    @Schema(hidden = true)
    public boolean isRequestReserveValid() {
        return requestsPerMinute == null || requestsPerMinute > interactiveReserve;
    }
}
