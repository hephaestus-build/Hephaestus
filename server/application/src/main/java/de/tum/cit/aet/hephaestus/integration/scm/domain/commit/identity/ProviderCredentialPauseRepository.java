package de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** The provider deadlines of throttled credentials, by the workspace that holds each one. */
@Repository
public interface ProviderCredentialPauseRepository extends JpaRepository<ProviderCredentialPause, Long> {

    /** The deadline of the workspace's credential, while it lasts. The database clock decides for every server. */
    @Query(value = """
        SELECT paused_until FROM provider_credential_pause
        WHERE workspace_id = :workspaceId AND paused_until > clock_timestamp()
        """, nativeQuery = true)
    Optional<Instant> pausedUntil(@Param("workspaceId") long workspaceId);

    /** Pauses the credential until {@code until}; a later deadline that another server stored stays. */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO provider_credential_pause (workspace_id, paused_until) VALUES (:workspaceId, :until)
        ON CONFLICT (workspace_id) DO UPDATE
        SET paused_until = GREATEST(provider_credential_pause.paused_until, EXCLUDED.paused_until)
        """, nativeQuery = true)
    int pause(@Param("workspaceId") long workspaceId, @Param("until") Instant until);
}
