package de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** The deadline of the latest credential that a provider throttled on each connection. */
@Repository
public interface ProviderCredentialPauseRepository extends JpaRepository<ProviderCredentialPause, Long> {

    /** The deadline of this credential generation, while it lasts. */
    @Query(value = """
        SELECT paused_until FROM provider_credential_pause
        WHERE workspace_id = :workspaceId AND connection_id = :connectionId
          AND credentials_generation = :generation AND paused_until > clock_timestamp()
        """, nativeQuery = true)
    Optional<Instant> pausedUntil(
            @Param("workspaceId") long workspaceId,
            @Param("connectionId") long connectionId,
            @Param("generation") long generation);

    /** A late response can extend its own pause. It cannot replace the pause of a newer credential. */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO provider_credential_pause (workspace_id, connection_id, credentials_generation, paused_until)
        SELECT :workspaceId, :connectionId, :generation, :until
        WHERE public.scm_credential_is_active(:connectionId, :generation)
        ON CONFLICT (connection_id) DO UPDATE
        SET credentials_generation = EXCLUDED.credentials_generation,
            paused_until = CASE
                WHEN provider_credential_pause.credentials_generation = EXCLUDED.credentials_generation
                THEN GREATEST(provider_credential_pause.paused_until, EXCLUDED.paused_until)
                ELSE EXCLUDED.paused_until
            END
        WHERE provider_credential_pause.workspace_id = :workspaceId
          AND provider_credential_pause.credentials_generation <= EXCLUDED.credentials_generation
        """, nativeQuery = true)
    int pause(
            @Param("workspaceId") long workspaceId,
            @Param("connectionId") long connectionId,
            @Param("generation") long generation,
            @Param("until") Instant until);
}
