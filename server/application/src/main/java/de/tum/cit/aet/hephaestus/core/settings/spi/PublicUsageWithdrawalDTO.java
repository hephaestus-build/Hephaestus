package de.tum.cit.aet.hephaestus.core.settings.spi;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** A withdrawn period, or one withdrawn figure of it. */
public record PublicUsageWithdrawalDTO(
        @NonNull @NotNull @Pattern(regexp = "\\d{4}-(04|10)") @Schema(example = "2026-04")
        String period,

        @Nullable UsageFigure figure) {}
