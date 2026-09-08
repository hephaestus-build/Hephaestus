package de.tum.cit.aet.hephaestus.workspace.access;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.access.GitHubAccessViewService.GitHubAccessOfferDTO;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnServerRole
@RequestMapping("/user/github-access")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class AccountGitHubAccessController {
    private final GitHubAccessViewService views;
    private final GitHubAccessEnrollmentService enrollment;

    public record GitHubAccessEnrollmentDTO(@NotNull Boolean enrolled) {}

    @GetMapping
    @Operation(
            operationId = "getMyGitHubAccess",
            summary = "Inspect your GitHub invitations, managed memberships and pending removals")
    public List<GitHubAccessOfferDTO> get() {
        return views.offers();
    }

    @PatchMapping("/{targetId}")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "GITHUB_ACCESS_MEMBERSHIP")
    @Operation(
            operationId = "changeMyGitHubAccessEnrollment",
            summary = "Leave or rejoin your offered target without expanding approved eligibility")
    public List<GitHubAccessOfferDTO> enroll(
            @PathVariable Long targetId, @Valid @RequestBody GitHubAccessEnrollmentDTO input) {
        enrollment.enroll(targetId, input.enrolled());
        return views.offers();
    }
}
