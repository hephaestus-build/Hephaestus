package de.tum.cit.aet.hephaestus.integration.core.sync.api;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

@Schema(description = "What a workspace member may know about syncing")
public record WorkspaceSyncProgressDTO(
        @NonNull @Schema(description = "A sync job is pending or running, so more work may still arrive")
        Boolean syncing) {}
