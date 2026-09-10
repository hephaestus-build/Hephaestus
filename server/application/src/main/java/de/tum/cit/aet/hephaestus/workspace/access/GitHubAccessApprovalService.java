package de.tum.cit.aet.hephaestus.workspace.access;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.access.github.GitHubAccessClient;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/** Network authorization is separate from the transaction which consumes its one-use capability. */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class GitHubAccessApprovalService {
    private final GitHubAccessPolicyService policies;
    private final GitHubAccessAuthorizationService authorization;
    private final GitHubAccessReconciliation reconciliation;
    private final GitHubAccessClient github;

    public void authorize(String token) {
        var input = authorization.prepareApproval(token);
        // Never read a private team by an installation ID guessed by a workspace owner. First
        // establish that the person holding the handoff is actually a GitHub organization owner.
        var organization = github.open(input.installationId(), input.organizationId(), 0);
        github.requireOrganizationOwner(organization, input.githubUserId());
        var target = input.scopeId() == null
                ? github.describe(input.installationId(), input.organization(), input.team())
                : github.open(input.installationId(), input.organizationId(), input.scopeId());
        authorization.completeApproval(input, target);
    }

    public void decide(
            long workspaceId,
            long targetId,
            long githubUserId,
            GitHubAccessReconciliation.Decision decision,
            @Nullable String reason) {
        long connectionId = policies.connectionForJob(workspaceId, targetId, true);
        var input = reconciliation.snapshot(workspaceId, connectionId, false);
        var person = input.people().stream()
                .filter(value -> value.githubUserId() == githubUserId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Refresh the target membership inventory first"));
        var installation = reconciliation.installation(input);
        var session = github.open(
                installation.installationId(),
                Objects.requireNonNull(input.organizationId()),
                Objects.requireNonNull(input.scopeId()));
        reconciliation.decide(input, person, decision, reason, session);
    }
}
