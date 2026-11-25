package de.tum.cit.aet.hephaestus.core.settings;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@WorkspaceAgnostic("Singleton instance-wide settings row (id = 1) — no tenant dimension exists")
interface InstanceSettingsRepository extends JpaRepository<InstanceSettings, Long> {
    /** The singleton row under a write lock, for a short change of the public usage page. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM InstanceSettings s WHERE s.id = :id")
    Optional<InstanceSettings> lockById(@Param("id") long id);

    /**
     * The lock that releases of the public usage page take in turn across replicas, until their transaction ends. It is
     * not the settings row, so a long release never holds back the silent-mode brake, the switch or a withdrawal.
     */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtext('hephaestus.public-usage'))", nativeQuery = true)
    int lockPublicUsage();

    /** The saved switch of the public usage page, or null when no admin saved one; a plain read of one column. */
    @Query(value = "SELECT public_usage_allowed FROM instance_settings WHERE id = 1", nativeQuery = true)
    @Nullable
    Boolean readPublicUsageAllowed();

    /** The saved switch of the public usage page and the revision of the row. */
    interface PublicUsageSwitch {
        long getVersion();

        @Nullable
        Boolean getPublicUsageAllowed();
    }

    /** The switch and the revision, read from the database and not from the session's cached row. */
    @Query(
            value = "SELECT version AS \"version\", public_usage_allowed AS \"publicUsageAllowed\""
                    + " FROM instance_settings WHERE id = 1",
            nativeQuery = true)
    Optional<PublicUsageSwitch> readPublicUsageSwitch();

    /**
     * The switch and the revision under a share lock that the transaction holds until it ends. A change in progress
     * is waited for and its result read; a change that starts after it waits for the commit.
     */
    @Query(
            value = "SELECT version AS \"version\", public_usage_allowed AS \"publicUsageAllowed\""
                    + " FROM instance_settings WHERE id = 1 FOR SHARE",
            nativeQuery = true)
    Optional<PublicUsageSwitch> readPublicUsageSwitchForShare();

    @Query(
            value = "SELECT COALESCE((SELECT silent_mode_engaged FROM instance_settings WHERE id = 1), TRUE)",
            nativeQuery = true)
    boolean readSilentModeEngaged();

    @Modifying
    @Query(
            value = "INSERT INTO instance_settings (id, silent_mode_engaged, version) VALUES (1, TRUE, 0) "
                    + "ON CONFLICT (id) DO NOTHING",
            nativeQuery = true)
    int insertFailSafeSingletonIfMissing();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value =
                    "UPDATE instance_settings SET silent_mode_engaged = TRUE, silent_mode_reason = :reason, "
                            + "silent_mode_changed_at = :changedAt, silent_mode_changed_by_account_id = :actor, version = version + 1 WHERE id = 1",
            nativeQuery = true)
    int engageSilentMode(
            @Param("reason") @Nullable String reason,
            @Param("changedAt") Instant changedAt,
            @Param("actor") @Nullable Long actor);
}
