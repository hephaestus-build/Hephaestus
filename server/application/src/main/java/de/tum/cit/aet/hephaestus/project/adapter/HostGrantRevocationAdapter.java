package de.tum.cit.aet.hephaestus.project.adapter;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountErasureContributor;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountSessionRevocationContributor;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.project.HostGrantRepository;
import de.tum.cit.aet.hephaestus.project.HostPairingRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Ends the host grants of an account whose sessions all end, and refuses its approved pairing requests (ADR 0166 rule
 * 6). The grant store has no link to account sessions, so this is the one place that connects them. An erasure keeps
 * the account as a tombstone, so the cascade on the approving account never fires: the erasure deletes the approved
 * requests here, and the end of the team memberships deletes the grants.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class HostGrantRevocationAdapter implements AccountSessionRevocationContributor, AccountErasureContributor {
    private final HostGrantRepository grants;
    private final HostPairingRepository pairings;

    @Override
    public void revokeAll(long accountId, Instant at) {
        grants.revokeAllFor(accountId, at);
        pairings.deleteApprovedBy(accountId);
    }

    @Override
    public void eraseAccount(long accountId) {
        pairings.deleteApprovedBy(accountId);
    }
}
