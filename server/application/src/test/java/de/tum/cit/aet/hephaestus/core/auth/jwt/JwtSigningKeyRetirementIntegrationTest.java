package de.tum.cit.aet.hephaestus.core.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The retirement and rotation queries against PostgreSQL. Each case rolls back: every server loads every active key,
 * so a row of this test must never become visible to another test.
 */
class JwtSigningKeyRetirementIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private JwtSigningKeyRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void shouldRetireOnlyAKeyThatAKeyCreatedBeforeTheCutoffReplaced() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        transactions.executeWithoutResult(status -> {
            String oldest = insert(now.minus(Duration.ofDays(30)));
            String replacedEarly = insert(now.minus(Duration.ofDays(29)));
            String newest = insert(now.plus(Duration.ofDays(30)));

            repository.retireSupersededBefore(now.minus(Duration.ofDays(28)));

            assertThat(active(oldest)).isFalse();
            assertThat(active(replacedEarly))
                    .as("its successor is newer than the cutoff")
                    .isTrue();
            assertThat(active(newest)).isTrue();
            status.setRollbackOnly();
        });
    }

    @Test
    void shouldFindAKeyCreatedAtOrAfterTheRequestedInstant() {
        Instant future = Instant.now().plus(Duration.ofDays(365)).truncatedTo(ChronoUnit.MILLIS);
        transactions.executeWithoutResult(status -> {
            assertThat(repository.existsByActiveTrueAndCreatedAtGreaterThanEqual(future))
                    .isFalse();
            insert(future);
            assertThat(repository.existsByActiveTrueAndCreatedAtGreaterThanEqual(future))
                    .isTrue();
            status.setRollbackOnly();
        });
    }

    private String insert(Instant createdAt) {
        String kid = "retire-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO jwt_signing_key (kid, algorithm, public_key_pem, private_key_pem, active, created_at)"
                        + " VALUES (?, 'ES256', 'pem', ?, true, ?)",
                kid,
                new byte[] {1},
                Timestamp.from(createdAt));
        return kid;
    }

    private boolean active(String kid) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT active FROM jwt_signing_key WHERE kid = ?", Boolean.class, kid));
    }
}
