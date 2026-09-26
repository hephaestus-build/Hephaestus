package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig.GitLabConfig.SigningMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Request to change how a GitLab connection's group webhook authenticates its deliveries")
public record GitLabSigningModeRequestDTO(
        @NotNull(message = "signingMode is required")
        @Schema(
                description = "WHSEC: a GitLab 19.1+ signing token (webhook-signature), which needs WEBHOOK_SECRET"
                        + " to be a whsec_ signing token. PLAINTEXT: the legacy secret token (X-Gitlab-Token).")
        SigningMode signingMode) {}
