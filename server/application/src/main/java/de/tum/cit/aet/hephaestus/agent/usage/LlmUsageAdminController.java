package de.tum.cit.aet.hephaestus.agent.usage;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Instance-admin LLM cost governance: the cross-workspace month rollup (spend totals only —
 * metadata, no tenant content) and the per-workspace monthly cap on host-funded spend.
 *
 * <p>Its twin at {@code PUT /workspaces/{workspaceSlug}/llm/budget} has the same body and the same
 * audit shape but writes a different column: a workspace admin must never be able to raise the host's
 * cap, so the path — not the caller's role — says whose money is being capped.
 */
@RestController
@RequestMapping("/admin")
@Tag(name = "Admin", description = "Instance-admin account management")
@RecentSignInExempt(reason = "sets a spending budget; grants no access and stores no credential")
@PreAuthorize("hasAuthority('app_admin')")
@ConditionalOnServerRole
@Validated
public class LlmUsageAdminController {

    private final LlmUsageAdminService llmUsageAdminService;

    public LlmUsageAdminController(LlmUsageAdminService llmUsageAdminService) {
        this.llmUsageAdminService = llmUsageAdminService;
    }

    @GetMapping("/llm/usage")
    @Operation(
            summary = "Per-workspace LLM spend rollup for one month (all workspaces)",
            operationId = "adminGetLlmUsageReport")
    public ResponseEntity<AdminLlmUsageReportDTO> getReport(
            @RequestParam(required = false)
                    @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])", message = "month must be ISO yyyy-MM")
                    @Nullable
                    String month) {
        YearMonth target = month != null ? YearMonth.parse(month) : YearMonth.now(ZoneOffset.UTC);
        return ResponseEntity.ok(llmUsageAdminService.getReport(target));
    }

    @PutMapping("/workspaces/{workspaceSlug}/llm/budget")
    @Operation(
            summary = "Set or clear a workspace's monthly cap on host-funded LLM spend",
            operationId = "adminUpdateWorkspaceLlmBudget")
    @ApiResponse(responseCode = "204", description = "Cap updated")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "WORKSPACE_INSTANCE_LLM_BUDGET")
    public ResponseEntity<Void> updateBudget(
            @PathVariable String workspaceSlug, @Valid @RequestBody UpdateLlmBudgetRequestDTO request) {
        llmUsageAdminService.updateBudget(workspaceSlug, request.monthlyBudgetUsd());
        return ResponseEntity.noContent().build();
    }
}
