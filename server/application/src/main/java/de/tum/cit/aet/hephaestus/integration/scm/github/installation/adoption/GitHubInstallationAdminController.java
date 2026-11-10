package de.tum.cit.aet.hephaestus.integration.scm.github.installation.adoption;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.RequireInstanceAdmin;
import de.tum.cit.aet.hephaestus.core.RequiresRecentSignIn;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnServerRole
@RequireInstanceAdmin
@WorkspaceAgnostic("Instance-level GitHub App installations; an adoption is what creates their workspace")
@RequestMapping("/admin/github-installations")
public class GitHubInstallationAdminController {
    private final GitHubInstallationAdoptionService adoptions;

    public GitHubInstallationAdminController(GitHubInstallationAdoptionService adoptions) {
        this.adoptions = adoptions;
    }

    @GetMapping
    @Operation(operationId = "listGitHubInstallations", summary = "List GitHub App installations and their decisions")
    public List<GitHubInstallationRequestDTO> list() {
        return adoptions.list();
    }

    @PostMapping("/{installationId}/adopt")
    @Operation(
            operationId = "adoptGitHubInstallation",
            summary = "Create the installation's workspace, with the signed-in admin as its owner")
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "GITHUB_INSTALLATION_ADOPTED")
    @RequiresRecentSignIn
    public GitHubInstallationRequestDTO adopt(@PathVariable long installationId) {
        return adoptions.adopt(installationId, actorId());
    }

    @PostMapping("/{installationId}/decline")
    @Operation(operationId = "declineGitHubInstallation", summary = "Decline a GitHub App installation")
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "GITHUB_INSTALLATION_DECLINED")
    @RequiresRecentSignIn
    public GitHubInstallationRequestDTO decline(@PathVariable long installationId) {
        return adoptions.decline(installationId, actorId());
    }

    private static long actorId() {
        return SecurityUtils.getCurrentAccountId()
                .orElseThrow(() -> new AccessDeniedException("An account is required"));
    }
}
