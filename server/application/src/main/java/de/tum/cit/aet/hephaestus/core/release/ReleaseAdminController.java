package de.tum.cit.aet.hephaestus.core.release;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@ConditionalOnServerRole
@RestController
@WorkspaceAgnostic("Instance release identity carries no tenant data — app_admin only")
@RequestMapping("/admin/release")
@Tag(name = "Instance Release", description = "Running release identity and update checks")
@RecentSignInExempt(reason = "a credential-free read of public release metadata that changes no configuration")
@PreAuthorize("hasAuthority('app_admin')")
public class ReleaseAdminController {
    private final ReleaseCheckService service;

    ReleaseAdminController(ReleaseCheckService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Get the running release and the last update check", operationId = "adminGetRelease")
    public ResponseEntity<ReleaseStatusDTO> get() {
        return response(service.status());
    }

    @PostMapping("/checks")
    @AuditExempt(reason = "refreshes an in-memory read of public release metadata; changes no configuration")
    @Operation(summary = "Check GitHub for a newer release now", operationId = "adminCheckRelease")
    public ResponseEntity<ReleaseStatusDTO> check() {
        return response(service.check());
    }

    private static ResponseEntity<ReleaseStatusDTO> response(ReleaseStatusDTO status) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(status);
    }
}
