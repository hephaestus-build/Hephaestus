package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Git provider rows are auto-created when workspaces are activated. */
@WorkspaceAgnostic(
        "IdentityProvider models a vendor instance (github.com, gitlab.lrz.de) shared across all workspaces; tenant scoping is enforced on the Connection aggregate.")
public interface IdentityProviderRepository extends JpaRepository<IdentityProvider, Long> {
    Optional<IdentityProvider> findByTypeAndServerUrl(IdentityProviderType type, String serverUrl);

    List<IdentityProvider> findAllByType(IdentityProviderType type);
}
