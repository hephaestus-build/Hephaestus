package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustodyQuery;
import de.tum.cit.aet.hephaestus.workspace.spi.MembershipReviewSelection;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ResultAdmissionPolicy {
    private final WorkspaceQueries workspaces;
    private final MembershipReviewSelection selections;
    private final HistoryCustodyQuery custody;

    public Assessment assess(long workspaceId, Review review, UUID jobId) {
        var workspace = workspaces.findById(workspaceId).orElseThrow();
        String scopeReason = !workspace.active()
                ? "WORKSPACE_INACTIVE"
                : workspace.reviewSettings().rolloutRevision() != review.getRolloutRevision()
                        ? "STALE_ROLLOUT_REVISION"
                        : null;
        var authority = custody.find(workspaceId, List.of(jobId));
        return new Assessment(
                authority,
                !authority.complete() || !authority.sourceJobIds().contains(jobId),
                scopeReason,
                selections.eligibleAccountIds(workspaceId));
    }

    public record Assessment(
            HistoryCustody copyAuthority,
            boolean authorityUnavailable,
            @Nullable String scopeReason,
            Set<Long> eligible) {
        public @Nullable String reason(@Nullable Long developerId) {
            return authorityUnavailable
                    ? "COPY_AUTHORITY_UNAVAILABLE"
                    : scopeReason != null
                            ? scopeReason
                            : developerId != null && !eligible.contains(developerId) ? "ROLE_UNAVAILABLE" : null;
        }
    }
}
