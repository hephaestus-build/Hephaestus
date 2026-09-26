package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig.GitLabConfig.SigningMode;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

@Schema(description = "A GitLab connection's webhook signing mode, as GitLab reported its group webhook")
public record GitLabSigningModeDTO(
        @NonNull SigningMode signingMode,

        @NonNull @Schema(description = "The group webhook GitLab reported in this mode")
        Long webhookId) {}
