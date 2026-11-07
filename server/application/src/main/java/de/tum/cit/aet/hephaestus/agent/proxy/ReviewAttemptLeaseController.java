package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.job.ReviewAttemptLease;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/llm/runtime/{id}/lease")
@PreAuthorize("hasAuthority('SCOPE_llm_proxy')")
@RequiredArgsConstructor
@ConditionalOnWorkerRole
@WorkspaceAgnostic("The verified review credential binds the workspace and exact attempt lease")
class ReviewAttemptLeaseController {
    private final ReviewAttemptLease leases;

    @PostMapping
    public ReviewAttemptLease.LeaseReceiptDTO renew(@PathVariable UUID id, Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof ProxyIdentity identity)
                || identity.job() == null
                || !identity.job().id().equals(id)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return leases.renew(identity.job())
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.CONFLICT, JobTokenAuthenticationFilter.ATTEMPT_ENDED));
    }
}
