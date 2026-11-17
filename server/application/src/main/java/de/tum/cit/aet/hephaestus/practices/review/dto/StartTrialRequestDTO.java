package de.tum.cit.aet.hephaestus.practices.review.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/** A request to try one practice on one item: name the item by its id or by its address, not both. */
public record StartTrialRequestDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,63}$")
        String practiceSlug,

        @Schema(type = "string", pattern = "^[1-9][0-9]*$") @Pattern(regexp = "^[1-9][0-9]*$") @Nullable
        String itemId,

        @Schema(description = "The address of a GitHub pull request or issue, or a GitLab merge request or issue")
        @Size(max = 2048)
        @Nullable
        String itemUrl) {

    @Schema(hidden = true)
    @AssertTrue(message = "Name the item by its id or by its address, not both.")
    public boolean isOneItem() {
        return (itemId == null) != (itemUrl == null);
    }
}
