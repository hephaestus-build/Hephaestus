package de.tum.cit.aet.hephaestus.workspace.access;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig.GitHubAccessConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import de.tum.cit.aet.hephaestus.workspace.directory.DirectoryPolicy;
import de.tum.cit.aet.hephaestus.workspace.directory.DirectoryPolicyRepository;
import de.tum.cit.aet.hephaestus.workspace.exception.InsufficientWorkspacePermissionsException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owner policy changes are serialized with reconciliation; GitHub I/O happens outside these transactions. */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class GitHubAccessPolicyService {
    private final GitHubAccessTargetRepository targets;
    private final GitHubAccessMembershipRepository members;
    private final GitHubAccessActionRepository actions;
    private final WorkspaceRepository workspaces;
    private final WorkspaceAccountMembershipRepository workspaceMembers;
    private final DirectoryPolicyRepository directories;
    private final GitHubAccessDirectoryEvidence evidence;
    private final ConnectionRepository connections;
    private final ConfigAuditPort audit;
    private final Clock clock;

    public record Configuration(
            String organization, @Nullable String team, long installationId, Set<String> groupIds) {}

    public record Handoff(GitHubAccessTarget target, String token) {
        @Override
        public String toString() {
            return "GitHubAccessHandoff[token=***]";
        }
    }

    @Transactional(readOnly = true)
    public List<GitHubAccessTarget> inspect(long workspaceId) {
        requirePermission(workspaceId, false);
        return targets.findByWorkspace_IdOrderById(workspaceId);
    }

    @Transactional
    public Handoff configure(long workspaceId, @Nullable Long targetId, Configuration input) {
        Workspace workspace = lockActive(workspaceId);
        requirePermission(workspaceId, true);
        if (!input.organization().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}")
                || (input.team() != null && !input.team().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}"))
                || input.installationId() <= 0)
            throw new IllegalArgumentException(
                    "Provide a GitHub organization login, optional team slug and Access App installation ID");
        var directory = directories
                .findByWorkspace_Id(workspaceId)
                .filter(value -> value.getStatus() == DirectoryPolicy.Status.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("Approve an active directory policy first"));
        if (input.groupIds().isEmpty() || !directory.getApprovedGroupIds().containsAll(input.groupIds()))
            throw new IllegalArgumentException("Select currently approved directory groups");
        GitHubAccessTarget target = targetId == null ? new GitHubAccessTarget() : required(workspaceId, targetId);
        var before = targetId == null ? null : GitHubAccessAudit.Policy.of(target);
        if (target.getStatus() == GitHubAccessTarget.Status.ENDED
                || target.getStatus() == GitHubAccessTarget.Status.ENDING)
            throw new IllegalArgumentException(
                    "An ending target cannot grant access; create a new target after teardown");
        if (target.isAuthorityHeld()
                && (!target.getDirectoryProviderId().equals(directory.getIdentityProviderId())
                        || !target.getRequestedOrganization().equalsIgnoreCase(input.organization())
                        || !Objects.equals(target.getRequestedTeam(), input.team())))
            throw new IllegalArgumentException(
                    "End the existing target before changing its organization, team or directory source");
        if (targetId == null) {
            target.setWorkspace(workspace);
            var connection = connections.saveAndFlush(new Connection(
                    workspace,
                    IntegrationKind.GITHUB_ACCESS,
                    UUID.randomUUID().toString(),
                    new GitHubAccessConfig(Set.of())));
            connection.setDisplayName("GitHub access: " + input.organization());
            target.setConnectionId(connection.getId());
            target.setRegistrationId(directory.getRegistrationId());
            target.setIssuer(directory.getIssuer());
            target.setDirectoryProviderId(directory.getIdentityProviderId());
        }
        target.setRequestedOrganization(input.organization());
        target.setRequestedTeam(input.team());
        target.setPendingInstallationId(input.installationId());
        target.setDraftGroupIds(Set.copyOf(input.groupIds()));
        target.setConfigurationVersion(target.getConfigurationVersion() + 1);
        target.setApprovedByAccountId(null);
        target.setApprovedAt(null);
        target.setPreview(null);
        String token = issueHandoff(target);
        targets.saveAndFlush(target);
        audit.record(new ConfigAuditEntry(
                ConfigAuditEntityType.GITHUB_ACCESS_POLICY,
                target.getId().toString(),
                workspaceId,
                before,
                GitHubAccessAudit.Policy.of(target)));
        return new Handoff(target, token);
    }

    /** Restore authorization or a reinstalled App without needing live directory evidence for pending removals. */
    @Transactional
    public Handoff renewApproval(long workspaceId, long targetId, long installationId) {
        lockActive(workspaceId);
        requirePermission(workspaceId, true);
        if (installationId <= 0) throw new IllegalArgumentException("Provide the Access App installation ID");
        var target = required(workspaceId, targetId);
        if (target.getStatus() == GitHubAccessTarget.Status.ENDED)
            throw new IllegalArgumentException("This target has ended; create a new target");
        var before = GitHubAccessAudit.Policy.of(target);
        target.setPendingInstallationId(installationId);
        target.setConfigurationVersion(target.getConfigurationVersion() + 1);
        target.setApprovedByAccountId(null);
        target.setApprovedAt(null);
        target.setPreview(null);
        String token = issueHandoff(target);
        record(target, before);
        return new Handoff(target, token);
    }

    @Transactional
    public GitHubAccessTarget approve(
            long workspaceId, long targetId, long configurationVersion, java.time.Instant previewCapturedAt) {
        lockActive(workspaceId);
        requirePermission(workspaceId, true);
        var target = required(workspaceId, targetId);
        var preview = target.getPreview();
        var authorization = target.getAuthorization();
        if (preview == null
                || authorization == null
                || !target.isAuthorityHeld()
                || configurationVersion != target.getConfigurationVersion()
                || !preview.github().capturedAt().equals(previewCapturedAt)
                || preview.configurationVersion() != target.getConfigurationVersion()
                || authorization.configurationVersion() != target.getConfigurationVersion()
                || clock.instant().isAfter(preview.github().capturedAt().plus(Duration.ofMinutes(10)))
                || !evidence.sameCapture(preview.directory(), evidence.read(target, true)))
            throw new IllegalArgumentException(
                    "Refresh the preview after organization-owner approval before approving this policy");
        if (target.getStatus() == GitHubAccessTarget.Status.ENDING
                || target.getStatus() == GitHubAccessTarget.Status.ENDED)
            throw new IllegalArgumentException("An ending target cannot be approved");
        var before = GitHubAccessAudit.Policy.of(target);
        target.setApprovedGroupIds(target.getDraftGroupIds());
        target.setApprovedByAccountId(CurrentAccount.requireId());
        target.setApprovedAt(clock.instant());
        target.setStatus(GitHubAccessTarget.Status.ACTIVE);
        record(target, before);
        return target;
    }

    @Transactional
    public GitHubAccessTarget pause(long workspaceId, long targetId, boolean paused) {
        lockActive(workspaceId);
        requirePermission(workspaceId, false);
        var target = required(workspaceId, targetId);
        var before = GitHubAccessAudit.Policy.of(target);
        target.setPaused(paused);
        target.setPreview(null);
        record(target, before);
        return target;
    }

    @Transactional
    public GitHubAccessTarget end(long workspaceId, long targetId) {
        lockActive(workspaceId);
        requirePermission(workspaceId, true);
        var target = required(workspaceId, targetId);
        if (target.getStatus() == GitHubAccessTarget.Status.ENDED) return target;
        var before = GitHubAccessAudit.Policy.of(target);
        target.setStatus(GitHubAccessTarget.Status.ENDING);
        target.setPreview(null);
        target.setHandoffHash(null);
        target.setHandoffExpiresAt(null);
        target.setHandoffIssuedByAccountId(null);
        var pending = actions.findByWorkspace_IdAndTarget_IdAndStatusInOrderById(
                workspaceId,
                targetId,
                Set.of(GitHubAccessAction.Status.PENDING, GitHubAccessAction.Status.MANUAL_RECOVERY));
        var existing = members.findByWorkspace_IdAndTarget_IdOrderById(workspaceId, targetId);
        for (var member : existing) {
            if (member.isManaged()
                    || pending.stream()
                            .anyMatch(action -> action.getMembership().getId().equals(member.getId())))
                member.setRevocationRequested(true);
        }
        if (pending.isEmpty()
                && existing.stream().noneMatch(member -> member.isManaged() || member.isRevocationRequested())) {
            target.setStatus(GitHubAccessTarget.Status.ENDED);
            target.setAuthorityHeld(false);
            target.setAuthorization(null);
        }
        record(target, before);
        return target;
    }

    @Transactional(readOnly = true)
    public long connectionForJob(long workspaceId, long targetId, boolean preview) {
        requirePermission(workspaceId, preview);
        return connectionId(workspaceId, targetId);
    }

    @Transactional(readOnly = true)
    public long scheduledConnection(long workspaceId, long targetId) {
        return connectionId(workspaceId, targetId);
    }

    private long connectionId(long workspaceId, long targetId) {
        return targets.findByIdAndWorkspace_Id(targetId, workspaceId)
                .filter(target -> target.getStatus() != GitHubAccessTarget.Status.ENDED)
                .orElseThrow(() -> new IllegalArgumentException("This GitHub target is unavailable or already ended"))
                .getConnectionId();
    }

    GitHubAccessTarget required(long workspaceId, long targetId) {
        return targets.lock(workspaceId, targetId)
                .orElseThrow(() -> new EntityNotFoundException("GitHubAccessTarget", targetId));
    }

    Workspace lockActive(long workspaceId) {
        var workspace = workspaces
                .findByIdForUpdate(workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceId));
        if (workspace.getStatus() != Workspace.WorkspaceStatus.ACTIVE)
            throw new IllegalArgumentException("Reactivate the workspace before changing GitHub access");
        return workspace;
    }

    void requirePermission(long workspaceId, boolean ownerRequired) {
        var context = WorkspaceContextHolder.getContext();
        var caller = SecurityUtils.getCurrentAccountId()
                .flatMap(id -> workspaceMembers.findByWorkspace_IdAndAccountId(workspaceId, id))
                .filter(member -> !member.isSuspended());
        boolean owner =
                caller.map(member -> member.getRole() == WorkspaceRole.OWNER).orElse(false);
        boolean admin =
                caller.map(member -> member.getRole() == WorkspaceRole.ADMIN).orElse(false)
                        || SecurityUtils.isSuperAdmin();
        if (context == null || context.id() != workspaceId || !(owner || (!ownerRequired && admin)))
            throw new InsufficientWorkspacePermissionsException(
                    context == null ? "unknown" : context.slug(),
                    "Only the workspace owner may expand GitHub policy; administrators may inspect, reconcile and pause approved access");
    }

    private String issueHandoff(GitHubAccessTarget target) {
        byte[] nonce = new byte[32];
        new SecureRandom().nextBytes(nonce);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        target.setHandoffHash(hash(token));
        target.setHandoffExpiresAt(clock.instant().plus(Duration.ofMinutes(10)));
        target.setHandoffIssuedByAccountId(CurrentAccount.requireId());
        return token;
    }

    boolean isCurrentOwner(long workspaceId, @Nullable Long accountId) {
        return accountId != null
                && workspaceMembers
                        .findByWorkspace_IdAndAccountId(workspaceId, accountId)
                        .filter(member -> !member.isSuspended() && member.getRole() == WorkspaceRole.OWNER)
                        .isPresent();
    }

    static String hash(String token) {
        if (!token.matches("[A-Za-z0-9_-]{43}"))
            throw new IllegalArgumentException("Invalid GitHub access approval link");
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private void record(GitHubAccessTarget target, GitHubAccessAudit.Policy before) {
        audit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.GITHUB_ACCESS_POLICY,
                target.getId(),
                target.getWorkspace().getId(),
                before,
                GitHubAccessAudit.Policy.of(target)));
    }
}
