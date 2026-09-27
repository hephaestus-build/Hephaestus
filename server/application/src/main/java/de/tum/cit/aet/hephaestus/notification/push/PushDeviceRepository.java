package de.tum.cit.aet.hephaestus.notification.push;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@WorkspaceAgnostic("Push devices belong to an account, which spans workspaces")
public interface PushDeviceRepository extends JpaRepository<PushDevice, UUID> {

    Optional<PushDevice> findByInstallationId(String installationId);

    Optional<PushDevice> findByExpoPushToken(String expoPushToken);

    @Transactional
    @Modifying
    @Query("DELETE FROM PushDevice d WHERE d.id = :id AND d.version = :version")
    int deleteRegistration(@Param("id") UUID id, @Param("version") long version);

    /** An installation can register concurrently after app startup and after a token refresh. */
    @Modifying
    @Query(value = """
        INSERT INTO push_device (id, account_id, installation_id, expo_push_token, platform,
                                 native_session_id, created_at, updated_at, version)
        VALUES (:id, :accountId, :installationId, :token, :platform, :sessionId, now(), now(), 0)
        ON CONFLICT (installation_id) DO UPDATE
           SET account_id = EXCLUDED.account_id, expo_push_token = EXCLUDED.expo_push_token,
               platform = EXCLUDED.platform, native_session_id = EXCLUDED.native_session_id,
               updated_at = now(), version = push_device.version + 1
        """, nativeQuery = true)
    void register(
            @Param("id") UUID id,
            @Param("accountId") Long accountId,
            @Param("installationId") String installationId,
            @Param("token") String token,
            @Param("platform") String platform,
            @Param("sessionId") UUID sessionId);

    @Transactional
    @Modifying
    @Query("DELETE FROM PushDevice d WHERE d.accountId = :accountId AND d.installationId = :installationId")
    int deleteOwned(@Param("accountId") Long accountId, @Param("installationId") String installationId);

    @Transactional
    @Modifying
    @Query("DELETE FROM PushDevice d WHERE d.accountId = :accountId")
    int deleteByAccountId(@Param("accountId") Long accountId);
}
