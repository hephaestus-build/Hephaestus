package de.tum.cit.aet.hephaestus.core.settings;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@ConditionalOnServerRole
@RestController
@WorkspaceAgnostic("Instance-wide operator settings — deliberately cross-tenant, app_admin only")
@RequestMapping("/admin/settings")
@Tag(name = "Instance Settings", description = "Instance-wide operator settings")
@RecentSignInExempt(
        reason = "silent mode is the instance emergency brake; a sign-in prompt would delay stopping delivery")
@PreAuthorize("hasAuthority('app_admin')")
public class InstanceSettingsAdminController {

    private final InstanceSettingsService instanceSettingsService;

    InstanceSettingsAdminController(InstanceSettingsService instanceSettingsService) {
        this.instanceSettingsService = instanceSettingsService;
    }

    public record InstanceSettingsDTO(
            @NonNull Boolean silentModeEngaged,
            @NonNull String etag,
            @Nullable String silentModeReason,
            @Nullable Instant silentModeChangedAt,
            @Nullable String silentModeChangedBy) {
        static InstanceSettingsDTO from(InstanceSettings settings) {
            return new InstanceSettingsDTO(
                    settings.isSilentModeEngaged(),
                    EntityTagPrecondition.format(Long.toString(settings.getVersion())),
                    settings.getSilentModeReason(),
                    settings.getSilentModeChangedAt(),
                    settings.getSilentModeChangedBy());
        }
    }

    public record UpdateSilentModeRequestDTO(
            // Boxed on purpose: a primitive would let Jackson default a missing field to false, silently
            // releasing the brake on a malformed request.
            @NonNull @NotNull Boolean engaged,
            @Nullable @Size(max = 500) String reason) {}

    @GetMapping
    @Operation(summary = "Get instance settings", operationId = "adminGetInstanceSettings")
    public ResponseEntity<InstanceSettingsDTO> get() {
        return response(instanceSettingsService.get());
    }

    @PatchMapping("/silent-mode")
    @Operation(summary = "Engage or release the instance-wide silent mode", operationId = "adminUpdateSilentMode")
    @ApiResponse(
            responseCode = "200",
            description = "Silent Mode updated",
            content = @Content(schema = @Schema(implementation = InstanceSettingsDTO.class)))
    @ApiResponse(
            responseCode = "412",
            description = "The supplied ETag is stale",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "428",
            description = "If-Match is required when releasing Silent Mode",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "SILENT_MODE_CHANGED")
    public ResponseEntity<InstanceSettingsDTO> updateSilentMode(
            @Parameter(description = "Current settings ETag; required when releasing Silent Mode")
                    @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                    @Nullable
                    String ifMatch,
            @Valid @RequestBody UpdateSilentModeRequestDTO body) {
        InstanceSettings updated = instanceSettingsService.updateSilentMode(
                body.engaged(),
                body.reason(),
                CurrentAccount.preferredUsernameOrNull(),
                ifMatch == null ? null : EntityTagPrecondition.parse(ifMatch));
        return response(updated);
    }

    private static ResponseEntity<InstanceSettingsDTO> response(InstanceSettings settings) {
        InstanceSettingsDTO body = InstanceSettingsDTO.from(settings);
        return ResponseEntity.ok().eTag(body.etag()).body(body);
    }
}
