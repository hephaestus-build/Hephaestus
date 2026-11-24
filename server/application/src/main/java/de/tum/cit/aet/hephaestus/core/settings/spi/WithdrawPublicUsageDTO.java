package de.tum.cit.aet.hephaestus.core.settings.spi;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A withdrawal request: one figure of a period, or the whole period, said explicitly. A body that names neither, such
 * as one with a misspelled field, is refused, so a typo can never withdraw a whole period for good.
 */
public record WithdrawPublicUsageDTO(
        @NonNull @NotNull @Pattern(regexp = "\\d{4}-(04|10)") @Schema(example = "2026-04")
        String period,

        @Nullable UsageFigure figure,

        @Nullable @Schema(description = "true withdraws every figure of the period")
        Boolean wholePeriod) {

    @AssertTrue(message = "Name one figure, or set wholePeriod to true, but not both")
    @Schema(hidden = true)
    public boolean isOneScope() {
        return (figure != null) != Boolean.TRUE.equals(wholePeriod);
    }
}
