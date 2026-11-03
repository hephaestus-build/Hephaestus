package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.WorkspaceAdminAssurance;
import de.tum.cit.aet.hephaestus.core.exception.AccessForbiddenException;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.RoleHolder;
import de.tum.cit.aet.hephaestus.practices.CanonicalDigest;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewRequestOutcomeDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequest;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequestStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.authorization.WorkspaceAccessService;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ReviewRequestService {
    private final ProductItemQuery items;
    private final AccountIdentityQuery identities;
    private final WorkspaceAccessService access;
    private final ReviewService reviews;
    private final ReviewRequestPolicy policy;
    private final MembershipRepository memberships;
    private final WorkspaceAdminAssurance adminAssurance;

    public boolean mayRequest(long workspaceId, ItemSnapshot snapshot, long accountId) {
        return mayRequest(workspaceId, snapshot, accountId, access::isAdmin);
    }

    private boolean mayRequest(long workspaceId, ItemSnapshot snapshot, long accountId, BooleanSupplier admin) {
        if (snapshot.item().workspaceId() != workspaceId) return false;
        // Own work first: only the admin fallback carries the admin passkey policy.
        return snapshot.roles().stream()
                        .filter(RoleHolder::human)
                        .filter(r -> r.role() == AboutRole.AUTHOR || r.role() == AboutRole.ASSIGNEE)
                        .anyMatch(r -> identities
                                .resolveActiveAccountId(
                                        r.identity().providerId(),
                                        r.identity().subject(),
                                        r.identity().teamId())
                                .filter(id -> id == accountId)
                                .isPresent())
                || admin.getAsBoolean();
    }

    @Transactional
    public ReviewRequestOutcomeDTO request(long workspaceId, long itemId, long accountId) {
        return request(workspaceId, itemId, accountId, this::assuredAdmin, null, null);
    }

    // A browser request for another person's work uses admin authority, so it needs the admin passkey proof.
    private boolean assuredAdmin() {
        if (!access.isAdmin()) return false;
        adminAssurance.require(true);
        return true;
    }

    /**
     * A request a provider comment makes. The provider names the commenter, so they must be a current member of the
     * workspace through the account that identity is linked to; admin standing comes from that membership, since no
     * session can lend elevation. The comment id keys the request, so a redelivered comment returns its review.
     */
    @Transactional
    public ReviewRequestOutcomeDTO requestByComment(
            long workspaceId,
            long itemId,
            PersonIdentity commenter,
            UUID requestKey,
            ReviewRequest.@Nullable CommentAnswer answer) {
        long accountId = identities
                .resolveActiveAccountId(commenter.providerId(), commenter.subject(), commenter.teamId())
                .orElseThrow(() -> new AccessForbiddenException("The commenter has no linked account."));
        var role = memberships
                .findByWorkspace_IdAndId_AccountId(workspaceId, accountId)
                .filter(member -> member.getLeftAt() == null)
                .map(Membership::getRole)
                .orElseThrow(() -> new AccessForbiddenException("The commenter is not a member of this workspace."));
        return request(
                workspaceId,
                itemId,
                accountId,
                () -> role.isAtLeast(Membership.WorkspaceRole.ADMIN),
                requestKey,
                answer);
    }

    private ReviewRequestOutcomeDTO request(
            long workspaceId,
            long itemId,
            long accountId,
            BooleanSupplier admin,
            @Nullable UUID requestKey,
            ReviewRequest.@Nullable CommentAnswer answer) {
        var item = items.require(workspaceId, itemId);
        var pinned = items.pin(item, null);
        if (!mayRequest(workspaceId, pinned, accountId, admin))
            throw new AccessForbiddenException(
                    "Only the author or an assignee, or a workspace admin, can request this review.");
        var refusal = policy.refusal(workspaceId, itemId, accountId);
        if (refusal.isPresent())
            return new ReviewRequestOutcomeDTO(ReviewRequestStatus.REFUSED, null, refusal.orElseThrow());
        var id = reviews.request(ReviewRequest.builder()
                .workspaceId(workspaceId)
                .itemId(itemId)
                .kind(ReviewKind.ITEM)
                .version(pinned.version())
                .origin(ReviewOrigin.REQUEST)
                .requestedById(accountId)
                .requestKey(requestKey)
                .requestDigest(requestKey == null ? null : CanonicalDigest.sha256Hex(itemId + ":" + accountId))
                .practiceIds(List.of())
                .commentAnswer(answer)
                .build());
        return new ReviewRequestOutcomeDTO(ReviewRequestStatus.SUBMITTED, id, null);
    }
}
