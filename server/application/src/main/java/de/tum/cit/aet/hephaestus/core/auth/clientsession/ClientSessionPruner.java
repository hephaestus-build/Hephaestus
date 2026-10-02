package de.tum.cit.aet.hephaestus.core.auth.clientsession;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes expired sign-in handoffs, and sessions that are past their deadline or were ended more than a
 * day ago together with every token row of their family. Until then those rows are what let an old
 * refresh secret or session-list entry resolve the session it belongs to.
 */
@ConditionalOnServerRole
@Service
@WorkspaceAgnostic("Installed-client sessions are account-scoped, not workspace-scoped")
public class ClientSessionPruner {

    private static final Logger log = LoggerFactory.getLogger(ClientSessionPruner.class);

    static final Duration ENDED_RETENTION = Duration.ofDays(1);

    private static final int PAGE_SIZE = 500;

    private final ClientSessionRepository sessionRepository;
    private final ClientSignInHandoffRepository handoffRepository;
    private final IssuedJwtRepository issuedJwtRepository;
    private final Clock clock;

    public ClientSessionPruner(
            ClientSessionRepository sessionRepository,
            ClientSignInHandoffRepository handoffRepository,
            IssuedJwtRepository issuedJwtRepository,
            Clock clock) {
        this.sessionRepository = sessionRepository;
        this.handoffRepository = handoffRepository;
        this.issuedJwtRepository = issuedJwtRepository;
        this.clock = clock;
    }

    @Transactional
    public void prune() {
        Instant now = clock.instant();
        Instant revokedBefore = now.minus(ENDED_RETENTION);
        int handoffs = handoffRepository.deleteExpiredBefore(now);
        int sessions = 0;
        int tokens = 0;
        List<UUID> page;
        do {
            // Session rows first, then their tokens: the lock order refresh and revocation use. Rows a
            // refresh or revocation holds are skipped until the next pass rather than waited on. The
            // session_id foreign key would cascade too; deleting the family explicitly keeps the count.
            page = sessionRepository.lockEnded(now, revokedBefore, PAGE_SIZE);
            if (!page.isEmpty()) {
                tokens += issuedJwtRepository.deleteBySessionIds(page);
                sessions += sessionRepository.deleteByIds(page);
            }
        } while (page.size() == PAGE_SIZE);
        log.info(
                "ClientSessionPruner: pruned {} handoffs, {} sessions and {} of their token rows",
                handoffs,
                sessions,
                tokens);
    }
}
