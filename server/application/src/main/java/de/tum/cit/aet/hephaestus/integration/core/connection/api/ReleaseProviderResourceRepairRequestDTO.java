package de.tum.cit.aet.hephaestus.integration.core.connection.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.jspecify.annotations.NonNull;

public record ReleaseProviderResourceRepairRequestDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @AssertTrue(message = "Confirm at the provider that the old resource is gone before releasing its fence")
        boolean confirmedGone,

        @NonNull @NotNull @Pattern(regexp = "[a-f0-9]{64}") String identityHash) {}
