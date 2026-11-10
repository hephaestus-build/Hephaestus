package de.tum.cit.aet.hephaestus.integration.scm.github.installation.adoption;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "What happens next with the GitHub App installation that the signed-in person set up")
public record GitHubInstallationClaimDTO(
        @NonNull Outcome outcome,

        @Schema(description = "The installation's workspace, given only to its members and to instance admins")
        @Nullable
        String workspaceSlug) {

    public enum Outcome {
        /** The installation has a workspace. Its slug is given only to a member or an instance admin. */
        READY,
        /** An instance admin must adopt the installation before Hephaestus uses it. */
        AWAITING_APPROVAL,
        /** An instance admin declined the installation. An instance admin can still adopt it. */
        DECLINED,
        /** The installation webhook creates the workspace; it has not arrived yet. */
        SETTING_UP,
    }
}
