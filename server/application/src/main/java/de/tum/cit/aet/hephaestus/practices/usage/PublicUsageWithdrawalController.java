package de.tum.cit.aet.hephaestus.practices.usage;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.RequireInstanceAdmin;
import de.tum.cit.aet.hephaestus.core.RequiresRecentSignIn;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.settings.spi.PublicUsagePolicyDTO;
import de.tum.cit.aet.hephaestus.core.settings.spi.WithdrawPublicUsageDTO;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Withdraws a released half-year, or one figure of it, from the public usage page for good (ADR 0153). */
@RestController
@ConditionalOnServerRole
@RequireInstanceAdmin
@RequiredArgsConstructor
public class PublicUsageWithdrawalController {
    private final PublicUsageWithdrawals withdrawals;

    @PostMapping("/admin/settings/public-usage/withdrawals")
    @RequiresRecentSignIn
    @Audited(ledger = AuditLedger.AUTH_EVENT, type = "PUBLIC_USAGE_WITHDRAWN")
    @Operation(
            operationId = "withdrawPublicUsage",
            summary = "Withdraw a released period or one of its figures from the public usage page, for good")
    public PublicUsagePolicyDTO withdraw(@Valid @RequestBody WithdrawPublicUsageDTO body) {
        return PublicUsagePolicyDTO.of(withdrawals.withdraw(body.period(), body.figure()));
    }
}
