package de.tum.cit.aet.hephaestus.core.release;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record ReleaseStatusDTO(
        @NonNull RunningReleaseDTO running,

        @NonNull
        @Schema(
                allowableValues = {
                    "CURRENT",
                    "UPDATE_AVAILABLE",
                    "CHECK_FAILED",
                    "NEVER_CHECKED",
                    "UNSUPPORTED",
                    "DISABLED",
                    "STALE"
                })
        String status,

        @NonNull Boolean enabled,
        @Nullable Instant lastAttempt,
        @Nullable Instant lastSuccess,
        @Nullable Instant nextCheck,
        @Nullable String failureReason,
        @Nullable AvailableReleaseDTO available,
        @NonNull String backupRestoreStatus,
        @NonNull String upgradeGuideUrl) {
    public record RunningReleaseDTO(
            @NonNull String version,
            @NonNull String commit,

            @NonNull @Schema(allowableValues = {"stable", "prerelease", "unknown"})
            String channel,

            @NonNull @Schema(allowableValues = {"DEPLOYMENT_REPORTED", "MISMATCH", "INVALID", "UNKNOWN"})
            String identityStatus,

            @NonNull List<String> roles,
            @NonNull Map<String, String> images) {}

    public record AvailableReleaseDTO(
            @NonNull String version,
            @NonNull String notesUrl,

            @NonNull @Schema(allowableValues = {"REQUIRED", "NONE", "UNKNOWN"})
            String schemaMigrations,

            @NonNull String securityRelevance,
            @NonNull String operatorActions) {}
}
