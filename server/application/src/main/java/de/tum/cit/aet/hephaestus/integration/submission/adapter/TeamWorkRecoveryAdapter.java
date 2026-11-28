package de.tum.cit.aet.hephaestus.integration.submission.adapter;

import de.tum.cit.aet.hephaestus.core.privacy.spi.RestoredTeamWork;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.submission.TeamItemVersionRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class TeamWorkRecoveryAdapter implements RestoredTeamWork {
    private final TeamItemVersionRepository versions;

    @Override
    public boolean withdrawAgain(long workspaceId, UUID versionId) {
        return versions.withdraw(workspaceId, versionId) > 0;
    }

    @Override
    public int withdrawDepartedAgain(long workspaceId, long accountId) {
        return versions.withdrawSubmittedBy(workspaceId, accountId);
    }
}
