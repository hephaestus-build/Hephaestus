package de.tum.cit.aet.hephaestus.core.release;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnServerRole
@WorkspaceAgnostic("Instance release metadata contains no tenant data; instance administrators only")
@PreAuthorize("hasAuthority('app_admin')")
@RecentSignInExempt(reason = "Release discovery is a credential-free read and changes no configuration")
@RequestMapping("/admin/release")
public class ReleaseAdminController {
    private final ReleaseCheckService service;

    public ReleaseAdminController(ReleaseCheckService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(operationId = "adminGetRelease", summary = "Get running identity and cached update state")
    public ResponseEntity<ReleaseStatusDTO> get() {
        return response(service.status());
    }

    @PostMapping("/checks")
    @AuditExempt(reason = "Refreshes a bounded public-release read model; changes no configuration")
    @Operation(operationId = "adminCheckRelease", summary = "Check releases, respecting cache and rate-limit backoff")
    public ResponseEntity<ReleaseStatusDTO> check() {
        return response(service.refresh());
    }

    private static ResponseEntity<ReleaseStatusDTO> response(ReleaseStatusDTO value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
