package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewWriteGuard;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ReviewWriteGuardService implements ReviewWriteGuard {
    private final ReviewRepository reviews;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(long workspaceId, UUID reviewId) {
        if (reviews.lockByIdAndWorkspaceId(reviewId, workspaceId).isEmpty())
            throw new IllegalArgumentException("The review does not belong to this workspace.");
    }
}
