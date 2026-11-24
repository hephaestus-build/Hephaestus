package de.tum.cit.aet.hephaestus.core.settings;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.RequireInstanceAdmin;
import de.tum.cit.aet.hephaestus.core.RequiresRecentSignIn;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.settings.spi.PublicUsagePolicyDTO;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The instance admin's switch of the public usage page (ADR 0153 decision 16). Withdrawals live with the releases. */
@RestController
@ConditionalOnServerRole
@RequestMapping("/admin/settings/public-usage")
@RequireInstanceAdmin
@RequiredArgsConstructor
public class PublicUsageSettingsController {
    private final PublicUsagePolicyService policy;

    public record UpdatePublicUsagePolicyDTO(
            @NonNull @NotNull Boolean allowed) {}

    @GetMapping
    @Operation(operationId = "getPublicUsagePolicy", summary = "Get the instance public usage policy")
    public PublicUsagePolicyDTO get() {
        return PublicUsagePolicyDTO.of(policy.state());
    }

    @PutMapping
    @RequiresRecentSignIn
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "PUBLIC_USAGE_CHANGED")
    @Operation(operationId = "updatePublicUsagePolicy", summary = "Turn the public usage page on or off")
    public PublicUsagePolicyDTO update(@Valid @RequestBody UpdatePublicUsagePolicyDTO body) {
        return PublicUsagePolicyDTO.of(policy.update(body.allowed()));
    }
}
