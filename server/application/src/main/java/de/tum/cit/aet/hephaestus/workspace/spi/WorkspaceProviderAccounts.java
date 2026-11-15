package de.tum.cit.aet.hephaestus.workspace.spi;

import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** The source account that a workspace mirrors, and the organization it binds. */
public interface WorkspaceProviderAccounts {
    /** The source account that the workspace mirrors, and which of its repositories it monitors. */
    Optional<ProviderAccount> providerAccount(long workspaceId);

    /** Whether any workspace binds the organization. */
    boolean organizationBound(long organizationId);

    boolean bindsOrganization(long workspaceId, long organizationId);

    /** The active workspaces other than {@code workspaceId} that bind the organization. */
    long otherActiveWorkspacesOfOrganization(long organizationId, long workspaceId);

    record ProviderAccount(String accountLogin, @Nullable RepositorySelection repositorySelection) {}
}
