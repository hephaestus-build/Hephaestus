package de.tum.cit.aet.hephaestus.account.adapter;

import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.RestoredRecordDeletions;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Profile("erasure-recovery")
@ConditionalOnServerRole
@OwnerScope.StoredOwnerOperation
public class PersonalGoalRecoveryAdapter implements RestoredRecordDeletions {
    private final JdbcTemplate jdbc;
    private final OwnerScope owners;
    private final TransactionTemplate transactions;

    public PersonalGoalRecoveryAdapter(JdbcTemplate jdbc, OwnerScope owners, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.owners = owners;
        this.transactions = new TransactionTemplate(manager);
    }

    @Override
    public void deleteAgain(long accountId, Set<UUID> ids) {
        transactions.executeWithoutResult(status -> {
            owners.ofJob(accountId);
            deleteOwnedMatch(accountId, ids);
        });
    }

    private void deleteOwnedMatch(long accountId, Set<UUID> ids) {
        jdbc
                .query(
                        "SELECT goal_id FROM personal_goal WHERE account_id = ? AND words IS NOT NULL",
                        (rs, row) -> Objects.requireNonNull(rs.getObject(1, UUID.class)),
                        accountId)
                .stream()
                .filter(ids::contains)
                .findFirst()
                .ifPresent(goalId -> jdbc.update("""
                        UPDATE personal_goal SET words = NULL, revision = revision + 1
                        WHERE account_id = ? AND goal_id = ? AND words IS NOT NULL
                        """, accountId, goalId));
    }
}
