package de.tum.cit.aet.hephaestus.workspace.dto;

import de.tum.cit.aet.hephaestus.core.auth.dto.AccountRefDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** One active MCP client grant of a workspace, as its admins see it (ADR 0110). */
public record McpClientGrantDTO(
        @NonNull UUID id,

        @NonNull @Schema(description = "The host of the client's metadata document URL")
        String clientHost,

        @NonNull @Schema(description = "The client's self-declared name, plain text")
        String clientName,

        @NonNull @Schema(description = "The account that approved the client")
        AccountRefDTO approvedBy,

        @NonNull List<String> scopes,
        @Nullable Instant approvedAt,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The approving admin confirmed with a passkey, as the workspace's admin passkey policy"
                        + " asked")
        boolean passkeyBacked,

        @Schema(
                description =
                        "When the client last signed in, refreshed, or made a call that succeeded, to within an hour")
        @Nullable
        Instant lastUsedAt,

        @NonNull
        @Schema(
                description = "When the client's access lapses unless the client renews it first: 90 days after its"
                        + " last renewal, so each use moves it")
        Instant expiresAt) {
    public McpClientGrantDTO {
        scopes = List.copyOf(scopes);
    }
}
