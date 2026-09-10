package de.tum.cit.aet.hephaestus.workspace.access;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditSnapshot;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Policy and ownership facts only: handoff capabilities and private inventories never enter audit. */
public final class GitHubAccessAudit {
    private GitHubAccessAudit() {}

    public record Policy(
            GitHubAccessTarget.Source source,
            GitHubAccessTarget.Status status,
            boolean paused,
            boolean authorityHeld,
            long configurationVersion,
            @Nullable Long organizationId,
            @Nullable Long scopeId,
            Set<String> groups,
            @Nullable Long approvedByAccountId,
            @Nullable Long authorizedByAccountId)
            implements ConfigAuditSnapshot {
        public static Policy of(GitHubAccessTarget target) {
            var authorization = target.getAuthorization();
            return new Policy(
                    target.getSource(),
                    target.getStatus(),
                    target.isPaused(),
                    target.isAuthorityHeld(),
                    target.getConfigurationVersion(),
                    target.getOrganizationId(),
                    target.getScopeId(),
                    Set.copyOf(target.getDraftGroupIds()),
                    target.getApprovedByAccountId(),
                    authorization == null ? null : authorization.accountId());
        }
    }

    public record Membership(
            long targetId,
            long githubUserId,
            boolean enrolled,
            boolean managed,
            boolean manualException,
            @Nullable String exceptionReason,
            boolean revocationRequested)
            implements ConfigAuditSnapshot {
        public static Membership of(GitHubAccessMembership member) {
            return new Membership(
                    member.getTarget().getId(),
                    member.getGithubUserId(),
                    member.isEnrolled(),
                    member.isManaged(),
                    member.isManualException(),
                    member.getExceptionReason(),
                    member.isRevocationRequested());
        }
    }
}
