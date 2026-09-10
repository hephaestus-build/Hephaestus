package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceApprovedAccessQuery;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
class WorkspaceApprovedAccessQueryAdapter implements WorkspaceApprovedAccessQuery {
    private final WorkspaceAccessRequestRepository requests;

    @Override
    @Transactional(readOnly = true)
    public List<Approval> currentApprovals(Long workspaceId, Instant now) {
        return requests.findCurrentApprovals(workspaceId, now).stream()
                .filter(current -> current.request().getApprovedDetails() != null
                        && current.request().getApprovedDetails().expiresAt().isAfter(now))
                .map(current -> {
                    var request = current.request();
                    var details = java.util.Objects.requireNonNull(request.getApprovedDetails());
                    var expiry = details.expiresAt().isBefore(current.membershipExpiry())
                            ? details.expiresAt()
                            : current.membershipExpiry();
                    return new Approval(
                            request.getId(),
                            request.getAccountId(),
                            expiry,
                            details.teamIds(),
                            request.getSubmission().identityLinkIds());
                })
                .toList();
    }
}
