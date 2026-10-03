package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountErasureContributor;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Keeps connection lifecycle facts without the erased account's attribution or free text. */
@Component
@RequiredArgsConstructor
class ConnectionAuditAccountErasureAdapter implements AccountErasureContributor {
    private final JdbcTemplate jdbc;

    @Override
    public void eraseAccount(long accountId) {
        jdbc.update(
                "UPDATE connection_audit SET actor_account_id=NULL,actor_ref=NULL,detail=NULL WHERE actor_account_id=?",
                accountId);
    }
}
