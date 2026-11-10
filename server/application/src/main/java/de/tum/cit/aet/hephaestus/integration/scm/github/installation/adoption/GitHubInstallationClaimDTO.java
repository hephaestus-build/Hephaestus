package de.tum.cit.aet.hephaestus.integration.scm.github.installation.adoption;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "What happens next with the GitHub App installation that the signed-in person set up")
public record GitHubInstallationClaimDTO(
        @NonNull Outcome outcome,

        @Schema(description = "The installation's workspace, when it has one") @Nullable
        String workspaceSlug) {

    public enum Outcome {
        /** The installation has a workspace. */
        READY,
        /** An instance admin must adopt the installation before Hephaestus uses it. */
        AWAITING_APPROVAL,
        /** The installation webhook creates the workspace; it has not arrived yet. */
        SETTING_UP,
    }
}
