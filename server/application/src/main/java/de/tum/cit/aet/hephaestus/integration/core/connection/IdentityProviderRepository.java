package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.spi.IdentityProviderLookup;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Provider instances are registered when sources activate, including sources with no account login. */
@WorkspaceAgnostic(
        "IdentityProvider models a vendor instance (github.com, gitlab.lrz.de) shared across all workspaces; tenant scoping is enforced on the Connection aggregate.")
public interface IdentityProviderRepository extends JpaRepository<IdentityProvider, Long> {
    @Query("""
            SELECT new de.tum.cit.aet.hephaestus.integration.core.spi.IdentityProviderLookup$Provider(
                p.id, p.type, p.serverUrl)
            FROM IdentityProvider p WHERE p.id = :providerId
            """)
    Optional<IdentityProviderLookup.Provider> findViewById(long providerId);

    @Query("""
            SELECT new de.tum.cit.aet.hephaestus.integration.core.spi.IdentityProviderLookup$Provider(
                p.id, p.type, p.serverUrl)
            FROM IdentityProvider p WHERE p.type = :type AND p.serverUrl = :serverUrl
            """)
    Optional<IdentityProviderLookup.Provider> findViewByTypeAndServerUrl(IdentityProviderType type, String serverUrl);

    @Query("""
            SELECT new de.tum.cit.aet.hephaestus.integration.core.spi.IdentityProviderLookup$Provider(
                p.id, p.type, p.serverUrl)
            FROM IdentityProvider p WHERE p.type = :type
            """)
    List<IdentityProviderLookup.Provider> findViewsByType(IdentityProviderType type);

    Optional<IdentityProvider> findByTypeAndServerUrl(IdentityProviderType type, String serverUrl);

    default void registerSourceInstance(String type, String serverUrl) {
        insertSourceInstance(type, serverUrl, IdentityProvider.outlineOrigin(type, serverUrl));
    }

    @Modifying
    @Query(value = """
            INSERT INTO identity_provider(type,server_url,outline_origin,created_at)
            VALUES (:type,:serverUrl,:outlineOrigin,now())
            ON CONFLICT(type,server_url) DO NOTHING
            """, nativeQuery = true)
    void insertSourceInstance(String type, String serverUrl, @Nullable String outlineOrigin);
}
