package de.tum.cit.aet.hephaestus.core.auth.clientsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.auth.AccountPurger;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import de.tum.cit.aet.hephaestus.testconfig.TestCacheConfiguration;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The installed-client schema as Liquibase creates it, not as Hibernate's {@code ddl-auto} does: the
 * integration tier cannot see the scalar {@code sfk_*} foreign keys, so the cascade from a session to its
 * token rows, refresh-secret uniqueness, and the cleanup and account erasure that rely on them are proven
 * here against the migrated chain, with the entities validated against it.
 */
@DataJpaTest(
        properties = {
            "spring.liquibase.enabled=true",
            "spring.liquibase.change-log=classpath:db/master.xml",
            "spring.liquibase.contexts=dev,prod",
            "spring.jpa.hibernate.ddl-auto=validate",
            // Migrations own this schema; do not run the Hibernate-created-schema fixture.
            "spring.sql.init.mode=never",
            "spring.jpa.defer-datasource-initialization=false",
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    TestCacheConfiguration.class,
    ClientSessionPruner.class,
    AccountPurger.class,
    ClientSessionMigratedSchemaTest.ClockConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ActiveProfiles("test")
@Tag("database")
class ClientSessionMigratedSchemaTest {

    private static final TestDatabase DATABASE = PostgreSQLTestContainer.createDatabase("client_session_schema");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::jdbcUrl);
        registry.add("spring.datasource.username", DATABASE::username);
        registry.add("spring.datasource.password", DATABASE::password);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ClientSessionRepository sessionRepository;

    @Autowired
    private ClientSignInHandoffRepository handoffRepository;

    @Autowired
    private IssuedJwtRepository issuedJwtRepository;

    @Autowired
    private ClientSessionPruner pruner;

    @Autowired
    private AccountPurger accountPurger;

    @Test
    void shouldDeclareTheSessionAndAccountForeignKeysAsCascadingWhenTheChainIsMigrated() {
        List<@Nullable String> foreignKeys = jdbc.queryForList("""
            SELECT DISTINCT rc.constraint_name || ':' || child.table_name || '.' || child.column_name
                   || '->' || parent.table_name || ':' || rc.delete_rule
            FROM information_schema.referential_constraints rc
            JOIN information_schema.key_column_usage child
              ON child.constraint_schema = rc.constraint_schema AND child.constraint_name = rc.constraint_name
            JOIN information_schema.constraint_column_usage parent
              ON parent.constraint_schema = rc.unique_constraint_schema
             AND parent.constraint_name = rc.unique_constraint_name
            WHERE rc.constraint_schema = current_schema()
              AND child.table_name IN ('issued_jwt', 'client_session', 'client_sign_in_handoff')
            """, String.class);

        assertThat(foreignKeys)
                .contains(
                        "sfk_issued_jwt_session:issued_jwt.session_id->client_session:CASCADE",
                        "sfk_client_session_account:client_session.account_id->account:CASCADE",
                        "sfk_client_sign_in_handoff_account:client_sign_in_handoff.account_id->account:CASCADE");
        assertThat(foreignKeys)
                .noneMatch(foreignKey -> foreignKey.contains("client_session.") && foreignKey.contains("->issued_jwt"));
    }

    @Test
    void shouldDeleteEveryTokenRowOfASessionWhenTheSessionRowIsDeleted() {
        Long accountId = account("Cascade Cora");
        ClientSession session = session(accountId, Instant.now().plus(Duration.ofDays(7)));
        UUID rotated = token(accountId, session.getId(), true);
        UUID current = token(accountId, session.getId(), false);

        jdbc.update("DELETE FROM client_session WHERE id = ?", session.getId());

        assertThat(issuedJwtRepository.findAllById(List.of(rotated, current))).isEmpty();
    }

    @Test
    void shouldRejectASecondTokenWithTheSameRefreshHashButAllowManyBrowserTokens() {
        Long accountId = account("Unique Uma");
        ClientSession session = session(accountId, Instant.now().plus(Duration.ofDays(7)));
        String hash = Pkce.hash(Pkce.newSecret());
        issuedJwtRepository.saveAndFlush(row(accountId, session.getId(), hash));

        assertThatThrownBy(() -> issuedJwtRepository.saveAndFlush(row(accountId, session.getId(), hash)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_issued_jwt_refresh_token_hash");

        issuedJwtRepository.saveAndFlush(row(accountId, null, null));
        issuedJwtRepository.saveAndFlush(row(accountId, null, null));
    }

    @Test
    void shouldPruneEndedSessionsWithTheirFamilyAndKeepLiveOnesWhenTheCleanupRuns() {
        Long accountId = account("Prune Pia");
        ClientSession live = session(accountId, Instant.now().plus(Duration.ofDays(7)));
        ClientSession expired = session(accountId, Instant.now().plus(Duration.ofDays(7)));
        ClientSession endedLongAgo = session(accountId, Instant.now().plus(Duration.ofDays(7)));
        UUID liveToken = token(accountId, live.getId(), false);
        UUID expiredToken = token(accountId, expired.getId(), false);
        UUID endedToken = token(accountId, endedLongAgo.getId(), true);
        jdbc.update(
                "UPDATE client_session SET session_expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)),
                expired.getId());
        jdbc.update(
                "UPDATE client_session SET revoked_at = ?, revoked_reason = 'LOGOUT' WHERE id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(2))),
                endedLongAgo.getId());

        pruner.prune();

        assertThat(sessionRepository.findAllById(List.of(live.getId(), expired.getId(), endedLongAgo.getId())))
                .extracting(ClientSession::getId)
                .containsExactly(live.getId());
        assertThat(issuedJwtRepository.findAllById(List.of(liveToken, expiredToken, endedToken)))
                .extracting(IssuedJwt::getJti)
                .containsExactly(liveToken);
    }

    @Test
    void shouldEraseSessionsHandoffsAndTokensWhenTheAccountIsPurged() {
        Long accountId = account("Purge Paul");
        Long otherId = account("Kept Kim");
        ClientSession session = session(accountId, Instant.now().plus(Duration.ofDays(7)));
        token(accountId, session.getId(), true);
        token(accountId, session.getId(), false);
        issuedJwtRepository.saveAndFlush(row(accountId, null, null));
        handoff(accountId);
        ClientSession kept = session(otherId, Instant.now().plus(Duration.ofDays(7)));
        UUID keptToken = token(otherId, kept.getId(), false);

        accountPurger.purge(accountId);

        for (String table : List.of("client_session", "client_sign_in_handoff", "issued_jwt")) {
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM " + table + " WHERE account_id = ?", Long.class, accountId))
                    .as(table)
                    .isZero();
        }
        assertThat(sessionRepository.findById(kept.getId())).isPresent();
        assertThat(issuedJwtRepository.findById(keptToken)).isPresent();
        assertThat(accountRepository.findById(accountId).orElseThrow().getStatus())
                .isEqualTo(Account.Status.DELETED);
    }

    private Long account(String name) {
        return Objects.requireNonNull(accountRepository.save(new Account(name)).getId());
    }

    private ClientSession session(Long accountId, Instant deadline) {
        return sessionRepository.saveAndFlush(new ClientSession(
                UUID.randomUUID(),
                accountId,
                InstalledClientKind.BROWSER_EXTENSION,
                ClientSignInFlow.CLIENT_ID,
                deadline,
                Instant.now()));
    }

    private UUID token(Long accountId, UUID sessionId, boolean rotatedAway) {
        IssuedJwt row = row(accountId, sessionId, Pkce.hash(Pkce.newSecret()));
        if (rotatedAway) {
            row.setRevokedAt(Instant.now());
            row.setRevokedReason(IssuedJwt.RevokedReason.ROTATE);
        }
        return issuedJwtRepository.saveAndFlush(row).getJti();
    }

    private static IssuedJwt row(Long accountId, @Nullable UUID sessionId, @Nullable String refreshTokenHash) {
        IssuedJwt row =
                new IssuedJwt(UUID.randomUUID(), accountId, Instant.now().plus(Duration.ofHours(1)));
        row.setSessionId(sessionId);
        row.setRefreshTokenHash(refreshTokenHash);
        return row;
    }

    private void handoff(Long accountId) {
        Instant now = Instant.now();
        handoffRepository.saveAndFlush(new ClientSignInHandoff(
                Pkce.hash(Pkce.newSecret()),
                accountId,
                new InstalledClient(
                        InstalledClientKind.BROWSER_EXTENSION,
                        ClientSignInFlow.CLIENT_ID,
                        ClientSignInFlow.CALLBACK,
                        "chrome-extension://" + ClientSignInFlow.CLIENT_ID),
                ClientSignInFlow.challengeOf(Pkce.newSecret()),
                now.plus(Duration.ofDays(7)),
                now,
                now.plusSeconds(60)));
    }

    @TestConfiguration
    static class ClockConfiguration {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
