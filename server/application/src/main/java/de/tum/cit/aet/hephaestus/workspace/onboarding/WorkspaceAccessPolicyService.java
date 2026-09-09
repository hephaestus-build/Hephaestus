package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditSnapshot;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.integration.core.email.SmtpEmailGateway;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
class WorkspaceAccessPolicyService {
    private final WorkspaceRepository workspaces;
    private final WorkspaceAccessPolicyRepository policies;
    private final WorkspaceAccountMembershipRepository memberships;
    private final WorkspaceAccessCatalog catalog;
    private final ConfigAuditPort audit;
    private final ObjectMapper mapper;
    private final SmtpEmailGateway email;

    @Transactional(readOnly = true)
    public WorkspaceAccessPolicyDTO get(Long workspaceId) {
        return policies.findByWorkspace_Id(workspaceId)
                .map(this::view)
                .orElse(new WorkspaceAccessPolicyDTO(null, false, null, email.isConfigured()));
    }

    @Transactional
    public WorkspaceAccessPolicyDTO configure(Long workspaceId, ConfigureWorkspaceAccessPolicyDTO update) {
        Workspace workspace = workspaces.findByIdForUpdate(workspaceId).orElseThrow();
        requireOwner(workspaceId);
        var policy = policies.findByWorkspace_Id(workspaceId).orElse(null);
        Long currentVersion = policy == null ? null : policy.getVersion();
        if (!Objects.equals(currentVersion, update.version())) {
            throw WorkspaceAccessCatalog.conflict("The access policy changed; reload it before saving");
        }
        // Pausing an unchanged policy remains possible during a provider outage; resuming revalidates it.
        if (update.enabled() || policy == null || !Objects.equals(policy.getSettings(), update.settings())) {
            catalog.validatePolicy(workspace, update.settings());
        }
        var before = policy == null ? null : snapshot(policy);
        if (policy == null) {
            policy = new WorkspaceAccessPolicy();
            policy.setWorkspace(workspace);
        }
        policy.setEnabled(update.enabled());
        policy.setSettings(update.settings());
        policies.saveAndFlush(policy);
        if (before == null) {
            audit.record(ConfigAuditEntry.created(
                    ConfigAuditEntityType.WORKSPACE_ACCESS_POLICY, policy.getId(), workspaceId, snapshot(policy)));
        } else {
            audit.record(ConfigAuditEntry.updated(
                    ConfigAuditEntityType.WORKSPACE_ACCESS_POLICY,
                    policy.getId(),
                    workspaceId,
                    before,
                    snapshot(policy)));
        }
        return view(policy);
    }

    private void requireOwner(Long workspaceId) {
        var owner = SecurityUtils.getCurrentAccountId()
                .flatMap(accountId -> memberships.findByWorkspace_IdAndAccountId(workspaceId, accountId))
                .filter(member -> member.isActive() && member.getRole() == WorkspaceRole.OWNER);
        if (owner.isEmpty())
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Only a workspace owner can configure access requests");
    }

    private PolicySnapshot snapshot(WorkspaceAccessPolicy policy) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsString(policy.getSettings()).getBytes(StandardCharsets.UTF_8));
            return new PolicySnapshot(
                    policy.getVersion(), policy.isEnabled(), HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
        }
    }

    private WorkspaceAccessPolicyDTO view(WorkspaceAccessPolicy policy) {
        return new WorkspaceAccessPolicyDTO(
                policy.getVersion(), policy.isEnabled(), policy.getSettings(), email.isConfigured());
    }

    record WorkspaceAccessPolicyDTO(
            @Nullable Long version,
            boolean enabled,
            @Nullable WorkspaceAccessPolicySettings settings,
            boolean emailConfigured) {}

    record ConfigureWorkspaceAccessPolicyDTO(
            @Nullable Long version,
            boolean enabled,
            @NonNull @NotNull @Valid WorkspaceAccessPolicySettings settings) {}

    record PolicySnapshot(long version, boolean enabled, String settingsDigest) implements ConfigAuditSnapshot {}
}
