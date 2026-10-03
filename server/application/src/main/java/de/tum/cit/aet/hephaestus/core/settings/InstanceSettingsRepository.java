package de.tum.cit.aet.hephaestus.core.settings;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@WorkspaceAgnostic("Singleton instance-wide settings row (id = 1) — no tenant dimension exists")
interface InstanceSettingsRepository extends JpaRepository<InstanceSettings, Long> {
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
