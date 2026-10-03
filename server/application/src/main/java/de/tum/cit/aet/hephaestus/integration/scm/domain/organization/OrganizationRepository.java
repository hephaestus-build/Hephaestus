package de.tum.cit.aet.hephaestus.integration.scm.domain.organization;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository for git provider organization entities (GitHub organizations, GitLab groups).
 *
 * <p>Login is <strong>not</strong> globally unique: an organization {@code HephaestusTest} can
 * exist on github.com and a group {@code hephaestustest} can simultaneously exist on
 * gitlab.lrz.de. The bare {@code findByLoginIgnoreCase(login)} method is intentionally
 * absent — every lookup must provider-scope (by id or by type+server) to avoid
 * {@code NonUniqueResultException} (ADR 0012).
 */
public interface OrganizationRepository extends JpaRepository<Organization, Long> {
    Optional<Organization> findByNativeIdAndProviderId(Long nativeId, Long providerId);

    Optional<Organization> findByLoginIgnoreCaseAndProviderId(String login, Long providerId);

    /**
     * Provider-type-scoped lookup. Use this from kind-specific code paths (anything under
     * {@code integration/github/} or {@code integration/gitlab/}) where the kind is known
     * statically but the specific provider instance may not be (e.g. github.com is the only
     * GitHub instance today, so type alone disambiguates). For GitLab where multiple
     * instances may host the same login, prefer
     * {@link #findByLoginIgnoreCaseAndProviderId(String, Long)} with a workspace-derived
     * provider id.
     */
    Optional<Organization> findByLoginIgnoreCaseAndProvider_Type(String login, IdentityProviderType type);

    /**
     * Upsert an organization using PostgreSQL ON CONFLICT.
     * <p>
     * The {@code id} column is auto-generated on insert. On conflict (provider_id, native_id),
     * the existing row is updated.
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO organization (native_id, provider_id, login, name, avatar_url, html_url)
        VALUES (:nativeId, :providerId, :login, :name, :avatarUrl, :htmlUrl)
        ON CONFLICT (provider_id, native_id) DO UPDATE SET
            login = EXCLUDED.login,
            name = EXCLUDED.name,
            avatar_url = EXCLUDED.avatar_url,
            html_url = EXCLUDED.html_url
        """, nativeQuery = true)
    void upsert(
            @Param("nativeId") Long nativeId,
            @Param("providerId") Long providerId,
            @Param("login") String login,
            @Param("name") String name,
            @Param("avatarUrl") @Nullable String avatarUrl,
            @Param("htmlUrl") @Nullable String htmlUrl);
}
