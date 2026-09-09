package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.OrganizationMembershipProbe;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.audit.WorkspaceAuditSnapshots.AccountMembershipSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
class WorkspaceAccessAdmissionState {
    private final WorkspaceRepository workspaces;
    private final WorkspaceAccessPolicyRepository policies;
    private final WorkspaceAccountMembershipRepository memberships;
    private final AccountIdentityQuery identities;
    private final WorkspaceAccessCatalog catalog;
    private final ConfigAuditPort audit;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Optional<WorkspaceAccessAdmissionService.State> existing(Long workspaceId, Long accountId) {
        return readExisting(workspaceId, accountId);
    }

    private Optional<WorkspaceAccessAdmissionService.State> readExisting(Long workspaceId, Long accountId) {
        return memberships
                .findByWorkspace_IdAndAccountId(workspaceId, accountId)
                .map(member -> {
                    if (member.isActiveAt(clock.instant())) return WorkspaceAccessAdmissionService.State.ACTIVE;
                    return !member.isSuspended() && member.getSource() == WorkspaceAccountMembership.Source.REQUEST
                            ? WorkspaceAccessAdmissionService.State.RENEWAL
                            : WorkspaceAccessAdmissionService.State.MANAGED;
                });
    }

    @Transactional(readOnly = true)
    public Prepared prepare(Long workspaceId, Long accountId) {
        return readPreparation(workspaceId, accountId);
    }

    private Prepared readPreparation(Long workspaceId, Long accountId) {
        var workspace = workspaces.findById(workspaceId).orElseThrow();
        var policy = policies.findByWorkspace_Id(workspaceId)
                .filter(WorkspaceAccessPolicy::isEnabled)
                .orElseThrow(() -> WorkspaceAccessCatalog.conflict("This workspace is not accepting access requests"));
        var primary = catalog.primary(workspace, policy.getSettings());
        Long linkId = catalog.primaryLink(workspace, policy.getSettings(), accountId);
        var link = identities.activeLinksForAccount(accountId).stream()
                .filter(value -> value.identityLinkId().equals(linkId))
                .findFirst()
                .orElseThrow(() -> WorkspaceAccessCatalog.conflict("The primary identity changed; try again"));
        var organization = workspace.getOrganization();
        if (organization == null)
            throw WorkspaceAccessCatalog.conflict("The workspace organization has not been synchronized");
        final long nativeId;
        try {
            nativeId = Long.parseLong(link.subject());
        } catch (NumberFormatException invalid) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "The primary identity has no valid provider-native ID", invalid);
        }
        return new Prepared(
                workspaceId,
                accountId,
                policy.getVersion(),
                linkId,
                IntegrationKind.valueOf(primary.type()),
                new OrganizationMembershipProbe.Target(
                        workspaceId, primary.serverUrl(), organization.getNativeId(), nativeId));
    }

    @Transactional
    public WorkspaceAccessAdmissionService.State admit(Prepared proof, Instant checkedAt) {
        var workspace = workspaces
                .findByIdForUpdate(proof.workspaceId())
                .filter(value -> value.getStatus() == Workspace.WorkspaceStatus.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        identities
                .accountForUpdate(proof.accountId())
                .filter(AccountIdentityQuery.AccountView::active)
                .orElseThrow(() -> WorkspaceAccessCatalog.conflict("The account is no longer active"));
        var existing = readExisting(proof.workspaceId(), proof.accountId());
        if (existing.isPresent()) return existing.get();
        var current = readPreparation(proof.workspaceId(), proof.accountId());
        if (!current.equals(proof) || checkedAt.plus(Duration.ofSeconds(30)).isBefore(clock.instant())) {
            throw WorkspaceAccessCatalog.conflict(
                    "The workspace or identity changed while membership was being checked; try again");
        }
        var membership = new WorkspaceAccountMembership();
        membership.setWorkspace(workspace);
        membership.setAccountId(proof.accountId());
        membership.setSource(WorkspaceAccountMembership.Source.SCM);
        membership.setRole(WorkspaceRole.MEMBER);
        memberships.save(membership);
        audit.record(ConfigAuditEntry.created(
                ConfigAuditEntityType.WORKSPACE_ROLE,
                proof.accountId(),
                proof.workspaceId(),
                AccountMembershipSnapshot.of(membership)));
        return WorkspaceAccessAdmissionService.State.ACTIVE;
    }

    record Prepared(
            Long workspaceId,
            Long accountId,
            long policyVersion,
            Long primaryLinkId,
            IntegrationKind kind,
            OrganizationMembershipProbe.Target target) {}
}
