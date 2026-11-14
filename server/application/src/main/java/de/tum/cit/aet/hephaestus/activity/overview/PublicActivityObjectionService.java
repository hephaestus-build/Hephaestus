package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserInfoDTO;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceMemberships;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class PublicActivityObjectionService {
    private final WorkspaceMemberships memberships;
    private final ActivityPeopleQueryRepository people;
    private final PublicRepositoryVisibility visibility;
    private final PersonDataCopyFence copyFence;
    private final PersonDataWriteFence writeFence;

    @Transactional(readOnly = true)
    public long hiddenPeople(long workspaceId) {
        return people.countPublicHiddenPeople(workspaceId, visibility.confirmedAfter());
    }

    /** The people an admin hid who have no membership: the only ones no other screen can show again. */
    @Transactional(readOnly = true)
    public List<UserInfoDTO> hiddenContributors(long workspaceId) {
        return people.findHiddenContributors(workspaceId).stream()
                .map(ActivityPeopleQueryRepository.ContributorRow::person)
                .toList();
    }

    @Transactional
    public void hide(long workspaceId, long userId, boolean hide) {
        copyFence.holdForCapture();
        if (!people.canClassify(workspaceId, userId)) throw new EntityNotFoundException("Contributor", userId);
        long accountId = memberships.accountIdForUser(userId);
        if (!writeFence.holdForAccountWrite(accountId)) throw new EntityNotFoundException("Contributor", userId);
        memberships.setContributorHidden(workspaceId, accountId, hide);
    }
}
