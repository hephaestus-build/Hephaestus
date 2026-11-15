package de.tum.cit.aet.hephaestus.workspace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** One active MCP client grant of a workspace, as its admins see it (ADR 0110). */
public record McpClientGrantDTO(
        @NonNull UUID id,
        @NonNull @Schema(description = "The host of the client's metadata document URL") String clientHost,
        @NonNull @Schema(description = "The client's self-declared name, plain text") String clientName,
        @NonNull @Schema(pattern = "^[1-9][0-9]*$", description = "The account that approved the client") String accountId,
        @NonNull List<String> scopes,
        @Nullable Instant approvedAt) {
    public McpClientGrantDTO {
        scopes = List.copyOf(scopes);
    }
}
