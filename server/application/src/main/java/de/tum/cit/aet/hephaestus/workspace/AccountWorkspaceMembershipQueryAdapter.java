package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-{@code workspace}-module implementation of {@link AccountWorkspaceMembershipQuery}: the
 * interface is owned by {@code core.auth}, the implementation lives with the data owner so it can
 * touch {@link WorkspaceMembership} / {@link Workspace} directly.
 */
@Service
public class AccountWorkspaceMembershipQueryAdapter implements AccountWorkspaceMembershipQuery {

    private static final Logger log = LoggerFactory.getLogger(AccountWorkspaceMembershipQueryAdapter.class);

    private final WorkspaceMembershipRepository workspaceMembershipRepository;
    private final AccountIdentityQuery identities;

    public AccountWorkspaceMembershipQueryAdapter(
            WorkspaceMembershipRepository workspaceMembershipRepository, AccountIdentityQuery identities) {
        this.workspaceMembershipRepository = workspaceMembershipRepository;
        this.identities = identities;
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkspaceMembershipView> membershipsForAccount(long accountId) {
        return nativeMemberships(accountId);
    }

    private List<WorkspaceMembershipView> nativeMemberships(long accountId) {
        var actors = identities.activeLinksForAccount(accountId).stream()
                .map(AccountIdentityQuery.IdentityLinkView::externalActorId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (actors.isEmpty()) {
            return List.of();
        }
        return workspaceMembershipRepository.findByUser_IdIn(actors).stream()
                .map(membership -> {
                    var workspace = membership.getWorkspace();
                    return new WorkspaceMembershipView(
                            workspace.getId(),
                            workspace.getWorkspaceSlug(),
                            workspace.getDisplayName(),
                            membership.getRole().name(),
                            membership.getUser().getId());
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> administratorAccountIds(long workspaceId) {
        return workspaceMembershipRepository.findAllWithUserByWorkspaceId(workspaceId).stream()
                .filter(membership -> membership.getRole() == WorkspaceMembership.WorkspaceRole.ADMIN
                        || membership.getRole() == WorkspaceMembership.WorkspaceRole.OWNER)
                .map(membership ->
                        identities.resolveAccountIdForActor(membership.getUser().getId()))
                .flatMap(java.util.Optional::stream)
                .distinct()
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isAdministrator(long workspaceId, long accountId) {
        return nativeMemberships(accountId).stream()
                .anyMatch(membership -> membership.workspaceId() == workspaceId
                        && ("ADMIN".equals(membership.role()) || "OWNER".equals(membership.role())));
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkspaceMembershipView> membershipsForLogins(Set<String> logins) {
        if (logins == null || logins.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = logins.stream()
                .filter(l -> l != null && !l.isBlank())
                .map(l -> l.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        if (normalized.isEmpty()) {
            return List.of();
        }
        try {
            // Deduplicate by workspace: a principal may resolve to multiple logins that both map
            // to the same workspace membership graph; the export should list each workspace once.
            Map<Long, WorkspaceMembershipView> byWorkspace = new LinkedHashMap<>();
            for (WorkspaceMembership membership :
                    workspaceMembershipRepository.findAllWithWorkspaceByUserLoginInLowercase(normalized)) {
                Workspace workspace = membership.getWorkspace();
                if (workspace == null || workspace.getId() == null) {
                    continue;
                }
                byWorkspace.putIfAbsent(
                        workspace.getId(),
                        new WorkspaceMembershipView(
                                workspace.getId(),
                                workspace.getWorkspaceSlug(),
                                workspace.getDisplayName(),
                                membership.getRole() != null
                                        ? membership.getRole().name()
                                        : null,
                                membership.getUser() != null
                                        ? membership.getUser().getId()
                                        : null));
            }
            return List.copyOf(byWorkspace.values());
        } catch (RuntimeException e) {
            // Fail-soft: a partial export is preferable to a failed one for a self-service GDPR
            // request. The empty section is visible in the bundle, and the error is logged.
            log.error("auth.export: workspace-membership lookup failed for {} login(s)", normalized.size(), e);
            return List.of();
        }
    }
}
