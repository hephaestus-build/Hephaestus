package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Provider instances are registered when sources activate, including sources with no account login. */
@WorkspaceAgnostic(
        "IdentityProvider models a vendor instance (github.com, gitlab.lrz.de) shared across all workspaces; tenant scoping is enforced on the Connection aggregate.")
public interface IdentityProviderRepository extends JpaRepository<IdentityProvider, Long> {
    Optional<IdentityProvider> findByTypeAndServerUrl(IdentityProviderType type, String serverUrl);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = """
            INSERT INTO identity_provider(type,server_url,created_at) VALUES (:type,:serverUrl,now())
            ON CONFLICT(type,server_url) DO NOTHING
            """, nativeQuery = true)
    void registerSourceInstance(String type, String serverUrl);

    List<IdentityProvider> findAllByType(IdentityProviderType type);
}
