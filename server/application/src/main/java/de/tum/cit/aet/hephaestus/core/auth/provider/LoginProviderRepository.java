package de.tum.cit.aet.hephaestus.core.auth.provider;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Instance-scoped login providers. Not workspace-scoped — sign-in options are global to the
 * deployment, hence {@link WorkspaceAgnostic}.
 */
@Repository
@WorkspaceAgnostic("Login providers are instance-global sign-in options, not workspace-scoped")
public interface LoginProviderRepository extends JpaRepository<LoginProvider, Long> {
    Optional<LoginProvider> findByRegistrationId(String registrationId);

    boolean existsByRegistrationId(String registrationId);

    /**
     * Serializes the seed from the environment across servers that start at once, until the surrounding transaction
     * ends, so the second server finds the first one's rows instead of failing on their unique registration id. The
     * two-integer key space of ADR 0017 native auth ({@code classId=17}); {@code objId=2} is this seed.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(17, 2)", nativeQuery = true)
    void acquireSeedLock();

    /** One login app per SCM instance (uq on {@code type + base_url}) — guards seeding against duplicates. */
    boolean existsByTypeAndBaseUrl(LoginProvider.ProviderType type, String baseUrl);

    /** Enabled providers, ordered for a stable login-page display. */
    List<LoginProvider> findByEnabledTrueOrderByDisplayNameAsc();

    boolean existsByEnabledTrueAndTypeIn(Collection<LoginProvider.ProviderType> types);

    /** Every row, locked, for the system key rotation ({@code SystemKeyRotation}). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT x FROM LoginProvider x ORDER BY x.id")
    List<LoginProvider> lockAllForKeyRotation();

    /**
     * An update writes every column back, so it locks the row: a system key rotation that resealed the secret meanwhile
     * would otherwise get the value under the earlier key back.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM LoginProvider p WHERE p.registrationId = :registrationId")
    Optional<LoginProvider> findByRegistrationIdForUpdate(@Param("registrationId") String registrationId);
}
