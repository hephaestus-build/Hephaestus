package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Instance-admin management of the instance-wide LLM governance settings: the egress allowlist and the
 * workspace-provider-connection flag.
 *
 * <p>Deliberately no class-level {@code @WorkspaceAgnostic} — a controller-wide tenancy bypass would
 * excuse every statement any handler ever reaches; the singleton's repository carries it.
 */
@RestController
@RequestMapping("/admin/llm/settings")
@Tag(name = "Admin LLM", description = "Instance-admin LLM connection and settings management")
@RecentSignInExempt(reason = "sets instance LLM defaults; grants no access and stores no credential")
@PreAuthorize("hasAuthority('app_admin')")
@ConditionalOnServerRole
@RequiredArgsConstructor
@Validated
public class InstanceLlmSettingsController {

    private final InstanceLlmSettingsService settingsService;

    @GetMapping
    @Operation(summary = "Get instance-wide LLM governance settings", operationId = "adminGetLlmSettings")
    public ResponseEntity<InstanceLlmSettingsDTO> get() {
        return ResponseEntity.ok(InstanceLlmSettingsDTO.from(settingsService.get()));
    }

    @PutMapping
    @Operation(summary = "Update instance-wide LLM governance settings", operationId = "adminUpdateLlmSettings")
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "LLM_SETTINGS_CHANGED")
    public ResponseEntity<InstanceLlmSettingsDTO> update(
            @Valid @RequestBody UpdateInstanceLlmSettingsRequestDTO request) {
        return ResponseEntity.ok(InstanceLlmSettingsDTO.from(settingsService.update(request)));
    }
}
