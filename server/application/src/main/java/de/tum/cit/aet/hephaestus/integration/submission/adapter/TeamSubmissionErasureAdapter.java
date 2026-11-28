package de.tum.cit.aet.hephaestus.integration.submission.adapter;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountErasureContributor;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.submission.TeamItemVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account erasure ends every version the account submitted. A workspace purge needs nothing here: the database deletes
 * items and versions with their project team.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class TeamSubmissionErasureAdapter implements AccountErasureContributor {
    private final TeamItemVersionRepository versions;

    @Override
    @Transactional
    public void eraseAccount(long accountId) {
        versions.eraseSubmitter(accountId);
    }
}
