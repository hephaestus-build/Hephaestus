package de.tum.cit.aet.hephaestus.integration.scm.github.installation.adoption;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The setup page that GitHub returns a person to after they install the app. */
@RestController
@ConditionalOnServerRole
@WorkspaceAgnostic("A GitHub App installation that has no workspace yet")
@RequestMapping("/github-installations")
public class GitHubInstallationClaimController {
    private final GitHubInstallationClaimService claims;

    public GitHubInstallationClaimController(GitHubInstallationClaimService claims) {
        this.claims = claims;
    }

    @PostMapping("/{installationId}/claim")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            operationId = "claimGitHubInstallation",
            summary = "Confirm the installation that the signed-in person set up on GitHub")
    public GitHubInstallationClaimDTO claim(
            @PathVariable long installationId, @Valid @RequestBody GitHubInstallationClaimRequestDTO request) {
        long accountId = SecurityUtils.getCurrentAccountId()
                .orElseThrow(() -> new AccessDeniedException("An account is required"));
        return claims.claim(installationId, request.code(), accountId);
    }
}
