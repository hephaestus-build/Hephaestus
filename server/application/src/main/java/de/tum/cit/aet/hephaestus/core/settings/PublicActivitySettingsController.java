package de.tum.cit.aet.hephaestus.core.settings;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.RequiresRecentSignIn;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnServerRole
@RequestMapping("/admin/settings/public-activity")
@WorkspaceAgnostic("Only instance administrators can change instance publication policy")
@PreAuthorize("hasAuthority('app_admin')")
@RequiredArgsConstructor
public class PublicActivitySettingsController {
    private final PublicActivityPolicyService policy;

    public record PublicActivityPolicyDTO(@NonNull @NotNull Boolean allowed) {}

    @GetMapping
    @Operation(operationId = "getPublicActivityPolicy", summary = "Get the instance public activity policy")
    public PublicActivityPolicyDTO get() {
        return new PublicActivityPolicyDTO(policy.allowed());
    }

    @PutMapping
    @RequiresRecentSignIn
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "PUBLIC_ACTIVITY_CHANGED")
    @Operation(operationId = "updatePublicActivityPolicy", summary = "Allow or stop public activity pages")
    public PublicActivityPolicyDTO update(@Valid @RequestBody PublicActivityPolicyDTO body) {
        return new PublicActivityPolicyDTO(policy.update(body.allowed()));
    }
}
