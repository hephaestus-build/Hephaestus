package de.tum.cit.aet.hephaestus.core.auth.mcp.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** A pending MCP client consent request, as the consent page shows it. */
public record McpConsentRequestDTO(
        @NonNull UUID id,

        @NonNull @Schema(description = "The host of the client's metadata document URL; the client cannot choose it")
        String clientHost,

        @NonNull @Schema(description = "The client's self-declared name, plain text")
        String clientName,

        @NonNull String redirectHost,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The code goes to this machine only")
        boolean loopbackRedirect,

        @NonNull String workspaceSlug,
        @NonNull String workspaceName,
        @NonNull List<String> scopes,

        @Schema(description = "Why this account cannot approve the request; absent when it can") @Nullable
        McpConsentRefusal refusal,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The account confirms with a passkey before it approves; the grant then ends one hour"
                        + " after that confirmation")
        boolean passkeyRequired) {
    public McpConsentRequestDTO {
        scopes = List.copyOf(scopes);
    }

    public enum McpConsentRefusal {
        NOT_ADMIN,
        PASSKEY_RECOVERY_PENDING,
        INSTANCE_ADMIN_PASSKEY_REQUIRED,
    }
}
