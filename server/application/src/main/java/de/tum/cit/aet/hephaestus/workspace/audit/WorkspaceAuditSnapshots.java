package de.tum.cit.aet.hephaestus.workspace.audit;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditSnapshot;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceFeatures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Audit snapshots for workspace-administration changes. Records carry no secrets — a token rotation
 * snapshots only that a token is set, never its value.
 */
public final class WorkspaceAuditSnapshots {

    private WorkspaceAuditSnapshots() {}

    public record FeaturesSnapshot(
            @Nullable Boolean practicesEnabled,
            @Nullable Boolean mentorEnabled,
            @Nullable Boolean leaderboardEnabled,
            @Nullable Boolean progressionEnabled,
            @Nullable Boolean leaguesEnabled,
            @Nullable Boolean practiceReviewAutoTriggerEnabled,
            @Nullable Boolean practiceReviewManualTriggerEnabled)
            implements ConfigAuditSnapshot {
        public static FeaturesSnapshot of(WorkspaceFeatures f) {
            return new FeaturesSnapshot(
                    f.getPracticesEnabled(),
                    f.getMentorEnabled(),
                    f.getLeaderboardEnabled(),
                    f.getProgressionEnabled(),
                    f.getLeaguesEnabled(),
                    f.getPracticeReviewAutoTriggerEnabled(),
                    f.getPracticeReviewManualTriggerEnabled());
        }
    }

    public record VisibilitySnapshot(@Nullable Boolean publiclyViewable) implements ConfigAuditSnapshot {}

    /** Presence of a stored SCM token — never the token itself. */
    public record TokenSnapshot(
            boolean tokenSet,
            @Nullable String providerKind,
            @Nullable Instant rotatedAt) implements ConfigAuditSnapshot {}

    public record AccountMembershipSnapshot(
            Long accountId,
            WorkspaceRole role,
            WorkspaceAccountMembership.Source source,
            boolean suspended,
            @Nullable Instant expiresAt,
            @Nullable Long accessRequestId)
            implements ConfigAuditSnapshot {
        public static AccountMembershipSnapshot of(WorkspaceAccountMembership membership) {
            return new AccountMembershipSnapshot(
                    membership.getAccountId(),
                    membership.getRole(),
                    membership.getSource(),
                    membership.isSuspended(),
                    membership.getExpiresAt(),
                    membership.getAccessRequestId());
        }
    }

    public record RoleSnapshot(@Nullable String role, boolean hidden) implements ConfigAuditSnapshot {}

    public record StatusSnapshot(@Nullable String status) implements ConfigAuditSnapshot {
        public static StatusSnapshot of(Workspace w) {
            return new StatusSnapshot(
                    w.getStatus() == null ? null : w.getStatus().name());
        }
    }
}
