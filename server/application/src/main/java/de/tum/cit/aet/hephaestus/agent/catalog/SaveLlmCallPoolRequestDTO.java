package de.tum.cit.aet.hephaestus.agent.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Objects;
import java.util.stream.Stream;
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

        @Nullable @Min(1) @Schema(description = "Ceiling on the limit the pool learns; null leaves it uncapped")
        Integer maxConcurrentCalls,

        @Nullable @Min(1) Integer requestsPerMinute,

        @Nullable @Min(1) @Schema(description = "A bucket of input and output tokens together")
        Long tokensPerMinute,

        @Nullable @Min(1) Long tokensPerCall,

        @Nullable @Min(1) @Schema(description = "A bucket of input tokens alone, for a provider that has one")
        Long inputTokensPerMinute,

        @Nullable @Min(1) @Schema(description = "A bucket of output tokens alone, for a provider that has one")
        Long outputTokensPerMinute) {

    @AssertTrue(message = "A token rate needs a per-call token bound no larger than the rate")
    @Schema(hidden = true)
    public boolean isTokenBoundValid() {
        // The bound covers input and output together, so an output rate needs none and can be lower.
        var rates = Stream.of(tokensPerMinute, inputTokensPerMinute)
                .filter(Objects::nonNull)
                .toList();
        if (rates.isEmpty()) return tokensPerCall == null;
        return tokensPerCall != null && rates.stream().allMatch(rate -> tokensPerCall <= rate);
    }
}
