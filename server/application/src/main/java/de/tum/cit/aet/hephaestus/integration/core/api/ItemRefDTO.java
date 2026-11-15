package de.tum.cit.aet.hephaestus.integration.core.api;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record ItemRefDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", pattern = "^[1-9][0-9]*$")
        String id,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String kind,

        @Schema(type = "string", pattern = "^[1-9][0-9]*$") @Nullable
        String parentId,

        @Nullable IntegrationKind provider,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String label,

        @Nullable String title,

        @Nullable String url,

        @Nullable String container,

        @Nullable String path,

        @Nullable Instant removedAt) {}
