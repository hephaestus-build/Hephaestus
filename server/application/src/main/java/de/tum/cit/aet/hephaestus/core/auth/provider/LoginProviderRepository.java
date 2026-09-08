package de.tum.cit.aet.hephaestus.core.auth.provider;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Instance-scoped login providers. Not workspace-scoped — sign-in options are global to the
 * deployment, hence {@link WorkspaceAgnostic}.
 */
@Repository
@WorkspaceAgnostic("Login providers are instance-global sign-in options, not workspace-scoped")
public interface LoginProviderRepository extends JpaRepository<LoginProvider, Long> {
    Optional<LoginProvider> findByRegistrationId(String registrationId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query(
            "SELECT p FROM LoginProvider p WHERE p.registrationId = :registrationId")
    Optional<LoginProvider> findByRegistrationIdForUpdate(String registrationId);

    boolean existsByRegistrationId(String registrationId);

    /** One login app per SCM instance (uq on {@code type + base_url}) — guards seeding against duplicates. */
    boolean existsByTypeAndBaseUrl(LoginProvider.ProviderType type, String baseUrl);

    /** Enabled providers, ordered for a stable login-page display. */
    List<LoginProvider> findByEnabledTrueOrderByDisplayNameAsc();

    boolean existsByEnabledTrueAndTypeIn(Collection<LoginProvider.ProviderType> types);
}
