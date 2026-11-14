package de.tum.cit.aet.hephaestus.integration.scm.github.installation.adoption;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "A GitHub App installation and an instance admin's decision on it")
public record GitHubInstallationRequestDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long installationId,

        @Schema(
                description =
                        "Absent for a personal account whose adopted workspace was deleted, or whose person was erased")
        @Nullable
        String accountLogin,

        @NonNull GitHubInstallationAccountKind accountKind,
        @NonNull GitHubInstallationRequestStatus status,
        @NonNull Instant receivedAt,
        @Nullable Instant decidedAt,

        @Schema(description = "The workspace that the adoption created; absent once that workspace is deleted")
        @Nullable
        String workspaceSlug) {}
